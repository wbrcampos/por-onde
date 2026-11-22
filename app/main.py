"""API do app Por Onde — informações do lugar por onde você passa.

Rodar em desenvolvimento:  uvicorn app.main:app --reload
Documentação interativa:   http://localhost:8000/docs
"""
import os
from collections import OrderedDict
from contextlib import asynccontextmanager
from datetime import timedelta
from typing import Literal

from fastapi import FastAPI, HTTPException, Query

from . import config, db, fontes, geo

RAIO_PROXIMA_M = int(os.getenv("RAIO_PROXIMA_CIDADE_M", "80000"))
ANGULO_MAX = float(os.getenv("ANGULO_MAX_GRAUS", "45"))
VELOCIDADE_PADRAO = float(os.getenv("VELOCIDADE_PADRAO_KMH", "70"))


@asynccontextmanager
async def ciclo_de_vida(_: FastAPI):
    db.abrir()
    db.limpar_cache_vencido()
    yield
    db.fechar()


app = FastAPI(title="Por Onde API", version="0.1.0", lifespan=ciclo_de_vida)

Lat = Query(..., ge=-90, le=90, description="Latitude (GPS)")
Lon = Query(..., ge=-180, le=180, description="Longitude (GPS)")

# --------------------------------------------------------------------------- #
# Geometrias: o banco filtra pela caixa (índice GiST) e o Python confere o     #
# polígono exato. As geometrias lidas ficam num cache em memória (LRU).        #
# --------------------------------------------------------------------------- #
_CHAVE = {"municipios": "codigo_ibge", "areas_urbanas": "id", "biomas": "id", "relevo": "id"}
_GEOMS: "OrderedDict[tuple[str, int], dict]" = OrderedDict()
_GEOMS_MAX = 3000


def _geometrias(tabela: str, ids: list[int]) -> dict[int, dict]:
    chave = _CHAVE[tabela]
    faltam = [i for i in ids if (tabela, i) not in _GEOMS]
    if faltam:
        for r in db.varios(f"SELECT {chave} AS id, geom FROM {tabela} WHERE {chave} = ANY(%s)", (faltam,)):
            _GEOMS[(tabela, r["id"])] = r["geom"]
    saida = {}
    for i in ids:
        if (tabela, i) in _GEOMS:
            _GEOMS.move_to_end((tabela, i))
            saida[i] = _GEOMS[(tabela, i)]
    while len(_GEOMS) > _GEOMS_MAX:
        _GEOMS.popitem(last=False)
    return saida


def _que_contem(tabela: str, campos: str, lat: float, lon: float) -> list[dict]:
    """Linhas da tabela cuja área contém o ponto."""
    chave = _CHAVE[tabela]
    linhas = db.varios(
        f"SELECT {chave} AS _id, {campos} FROM {tabela} WHERE caixa @> point(%(lon)s, %(lat)s)",
        {"lat": lat, "lon": lon},
    )
    geoms = _geometrias(tabela, [r["_id"] for r in linhas])
    return [r for r in linhas if r["_id"] in geoms and geo.contem(geoms[r["_id"]], lon, lat)]


def _caixa_sql(lat: float, lon: float, raio_m: float) -> str:
    x0, y0, x1, y1 = geo.caixa_ao_redor(lat, lon, raio_m)
    return f"(({x0},{y0}),({x1},{y1}))"


def _diferenca_angulo(a: float, b: float) -> float:
    d = abs(a - b) % 360
    return 360 - d if d > 180 else d


def _clima_municipio(codigo: int, lat: float, lon: float) -> dict | None:
    try:
        return fontes.clima(lat, lon, f"mun:{codigo}")
    except Exception:
        return None  # sem clima não é motivo para derrubar a tela


@app.get("/saude")
def saude():
    db.um("SELECT 1")
    return {"ok": True}


@app.get("/contexto")
def contexto(
    lat: float = Lat,
    lon: float = Lon,
    rumo: float | None = Query(None, ge=0, lt=360, description="Direção do carro em graus (0 = norte)"),
    velocidade: float | None = Query(None, ge=0, description="Velocidade atual em km/h"),
):
    """Chamada principal do app: onde estou, modo cidade/estrada, natureza, clima e próxima cidade."""
    achados = _que_contem("municipios", "codigo_ibge, nome, uf, estado, populacao, clat, clon", lat, lon)
    if not achados:
        raise HTTPException(404, "Ponto fora dos municípios importados")
    lugar = achados[0]
    # Núcleo urbano em que o ponto está. Conta como cidade a sede do município ou
    # uma vila oficial (IBGE); pedaço isolado à beira da estrada não conta.
    nucleos = _que_contem("areas_urbanas", "codigo_ibge, sede, localidade", lat, lon)
    urbanos = [n for n in nucleos if n["sede"] or n["localidade"]]
    lugar["urbano"] = bool(urbanos)
    lugar["localidade"] = urbanos[0]["localidade"] if urbanos else None
    lugar["sede_atual"] = next((n["codigo_ibge"] for n in urbanos if n["sede"]), None)
    biomas = _que_contem("biomas", "nome", lat, lon)
    relevos = _que_contem("relevo", "nome, descricao", lat, lon)
    # O ponto está num município (terra), mas o mapa de relevo 1:5 mi é grosseiro e
    # cobre a orla com "Massa d'água": nesse caso é melhor não dizer nada.
    relevos = [r for r in relevos if not r["nome"].lower().startswith(("massa d", "corpo d"))]
    lugar["bioma"] = biomas[0]["nome"] if biomas else None
    lugar["relevo"] = relevos[0]["nome"] if relevos else None
    lugar["relevo_desc"] = relevos[0]["descricao"] if relevos else None

    try:
        alt = fontes.altitude(lat, lon)
    except Exception:
        alt = None

    return {
        "modo": "cidade" if lugar["urbano"] else "estrada",
        "municipio": {
            "codigo_ibge": lugar["codigo_ibge"], "nome": lugar["nome"],
            "uf": lugar["uf"], "estado": lugar["estado"], "populacao": lugar["populacao"],
        },
        "zona": "urbana" if lugar["urbano"] else "rural",
        "localidade": lugar["localidade"],  # sede ou vila em que o ponto está (modo cidade)
        "altitude_m": round(alt) if alt is not None else None,
        "bioma": lugar["bioma"],
        "relevo": {"nome": lugar["relevo"], "descricao": lugar["relevo_desc"]} if lugar["relevo"] else None,
        "clima": _clima_municipio(lugar["codigo_ibge"], lugar["clat"], lugar["clon"]),
        "proxima_cidade": _proxima_cidade(lat, lon, rumo, velocidade, lugar),
        "fontes": ["IBGE", "Open-Meteo (CC BY 4.0)"],
    }


def _proxima_cidade(lat, lon, rumo, velocidade, lugar) -> dict | None:
    linhas = db.varios(
        """
        SELECT a.id, a.codigo_ibge, m.nome, m.uf, a.clat, a.clon
        FROM areas_urbanas a
        JOIN municipios m USING (codigo_ibge)
        WHERE a.caixa && %(caixa)s::box                       -- filtro rápido (índice)
          AND a.sede                                          -- só a cidade de fato
          AND a.codigo_ibge IS DISTINCT FROM %(atual)s        -- dentro da sede, ignora ela mesma
        """,
        {"caixa": _caixa_sql(lat, lon, RAIO_PROXIMA_M), "atual": lugar["sede_atual"]},
    )
    geoms = _geometrias("areas_urbanas", [r["id"] for r in linhas])
    candidatos = []
    for r in linhas:
        if r["id"] not in geoms:
            continue
        dist, _ = geo.ate_borda(geoms[r["id"]], lon, lat)
        if dist <= RAIO_PROXIMA_M:
            # Direção até o CENTRO da cidade: a borda mais próxima pode estar de lado
            # (cidade comprida ou a estrada passando rente) e fazer pular a cidade certa.
            azimute = geo.rumo(lat, lon, r["clat"], r["clon"])
            candidatos.append({**r, "dist_m": dist, "azimute": azimute})
    candidatos.sort(key=lambda c: c["dist_m"])
    for c in candidatos:
        if rumo is not None and _diferenca_angulo(rumo, c["azimute"]) > ANGULO_MAX:
            continue  # está atrás ou para o lado
        # Rota real pela estrada (OSRM) até o centro da cidade; se falhar, estimativa
        r = fontes.rota(lat, lon, c["clat"], c["clon"], str(c["codigo_ibge"]))
        if r:
            estrada_km, minutos, estimado = r["km"], r["min"], False
        else:
            estrada_km = c["dist_m"] * config.FATOR_ESTRADA / 1000
            vel = velocidade if velocidade and velocidade > 20 else VELOCIDADE_PADRAO
            minutos, estimado = estrada_km / vel * 60, True
        return {
            "codigo_ibge": c["codigo_ibge"], "nome": c["nome"], "uf": c["uf"],
            "distancia_reta_km": round(c["dist_m"] / 1000, 1),
            "distancia_estrada_km": round(estrada_km, 1),
            "minutos": max(1, round(minutos)),
            "estimado": estimado,  # True = linha reta × fator (OSRM indisponível)
            "clima": _clima_municipio(c["codigo_ibge"], c["clat"], c["clon"]),
        }
    return None


# Ordem dos destaques: atrativos de verdade primeiro; estátuas e obras de arte
# só entram depois (a não ser que tenham artigo na Wikipedia/Wikidata).
_PESO_TIPO = {
    "attraction": 0, "museum": 0, "viewpoint": 0, "theme_park": 0, "zoo": 0,
    "nature_reserve": 0, "castle": 0, "fort": 0, "ruins": 0, "archaeological_site": 0,
    "gallery": 1, "memorial": 2, "monument": 2, "artwork": 3,
}


def _destaques(codigo_ibge: int, caixa: str, clat: float, clon: float, limite: int = 10) -> list[dict]:
    contorno = _geometrias("municipios", [codigo_ibge]).get(codigo_ibge)
    if not contorno:
        return []
    achados = []
    for p in db.varios(
        """
        SELECT nome, tipo, lat, lon, tags ? 'wikipedia' OR tags ? 'wikidata' AS famoso FROM pois
        WHERE local <@ %s::box AND categoria = 'ver' AND nome IS NOT NULL
        """,
        (caixa,),
    ):
        if geo.contem(contorno, p["lon"], p["lat"]):
            p["distancia_m"] = round(geo.distancia_m(clat, clon, p["lat"], p["lon"]))
            famoso = p.pop("famoso")
            peso = _PESO_TIPO.get(p["tipo"], 2)
            if famoso:
                peso = min(peso, 1)
            # dentro do mesmo peso, quem tem artigo na Wikipedia/Wikidata vem antes
            p["_peso"] = (peso, not famoso)
            achados.append(p)
    achados.sort(key=lambda p: (p["_peso"], p["distancia_m"]))
    escolhidos: list[dict] = []
    for p in achados:
        # o mesmo lugar mapeado duas vezes (ponto + área, nomes parecidos)
        if any(geo.distancia_m(p["lat"], p["lon"], e["lat"], e["lon"]) < 150 for e in escolhidos):
            continue
        escolhidos.append(p)
        if len(escolhidos) == limite:
            break
    for p in escolhidos:
        del p["_peso"]
    return escolhidos


@app.get("/cidade/{codigo_ibge}")
def cidade(codigo_ibge: int):
    """Dados para o modo cidade: resumo, foto, destaques e clima."""
    m = db.um(
        """
        SELECT codigo_ibge, nome, uf, estado, populacao, clat, clon, caixa::text AS caixa
        FROM municipios WHERE codigo_ibge = %s
        """,
        (codigo_ibge,),
    )
    if not m:
        raise HTTPException(404, "Município não encontrado")

    info = db.um(
        """SELECT * FROM cidade_info WHERE codigo_ibge = %s AND atualizado_em > now() - %s
           AND (foto_url IS NULL OR foto_licenca IS NOT NULL)""",  # foto sem licença: busca de novo
        (codigo_ibge, timedelta(days=config.CACHE_CIDADE_DIAS)),
    )
    if not info:
        try:
            novo = fontes.info_cidade(m["nome"], m["estado"], m["clat"], m["clon"])
        except Exception:
            novo = None
        if novo:
            db.executar(
                """
                INSERT INTO cidade_info (codigo_ibge, wiki_titulo, descricao, resumo, wiki_url,
                                         foto_url, foto_credito, foto_licenca, atualizado_em)
                VALUES (%(codigo)s, %(wiki_titulo)s, %(descricao)s, %(resumo)s, %(wiki_url)s,
                        %(foto_url)s, %(foto_credito)s, %(foto_licenca)s, now())
                ON CONFLICT (codigo_ibge) DO UPDATE SET
                  wiki_titulo = EXCLUDED.wiki_titulo, descricao = EXCLUDED.descricao,
                  resumo = EXCLUDED.resumo, wiki_url = EXCLUDED.wiki_url,
                  foto_url = EXCLUDED.foto_url, foto_credito = EXCLUDED.foto_credito,
                  foto_licenca = EXCLUDED.foto_licenca, atualizado_em = now()
                """,
                {"codigo": codigo_ibge, **novo},
            )
            info = novo
        else:  # se a Wikipedia falhar, usa o que já tinha (mesmo antigo)
            info = db.um("SELECT * FROM cidade_info WHERE codigo_ibge = %s", (codigo_ibge,))

    # Centro da cidade = centro da sede urbana (o ponto do município pode cair na zona rural)
    sede = db.um("SELECT clat, clon FROM areas_urbanas WHERE codigo_ibge = %s AND sede", (codigo_ibge,))
    clat, clon = (sede["clat"], sede["clon"]) if sede else (m["clat"], m["clon"])
    destaques = _destaques(codigo_ibge, m["caixa"], clat, clon) if m["caixa"] and clat is not None else []
    try:
        artigos = fontes.artigos_proximos(m["clat"], m["clon"])
    except Exception:
        artigos = []

    return {
        "codigo_ibge": m["codigo_ibge"], "nome": m["nome"], "uf": m["uf"], "estado": m["estado"],
        "populacao": m["populacao"],
        "descricao": info and info.get("descricao"),
        "resumo": info and info.get("resumo"),
        "foto": info and info.get("foto_url") and {
            "url": info["foto_url"], "credito": info.get("foto_credito"),
            "licenca": info.get("foto_licenca"),
        },
        "wiki_url": info and info.get("wiki_url"),
        "destaques": destaques,
        "artigos_proximos": artigos,
        "clima": _clima_municipio(codigo_ibge, m["clat"], m["clon"]),
        "fontes": ["IBGE", "Wikipedia (CC BY-SA)", "OpenStreetMap (ODbL)", "Open-Meteo (CC BY 4.0)"],
    }


@app.get("/lugares")
def lugares(
    lat: float = Lat,
    lon: float = Lon,
    cat: Literal["comer", "dormir", "abastecer", "ver"] = Query(...),
    raio_m: int = Query(5000, ge=100, le=50000),
    limite: int = Query(20, ge=1, le=100),
):
    """Lugares por perto, do mais próximo ao mais distante."""
    return db.varios(
        """
        SELECT * FROM (
          SELECT nome, tipo, lat, lon,
                 round(2 * 6371008.8 * asin(sqrt(
                   sin(radians(lat - %(lat)s) / 2) ^ 2
                   + cos(radians(%(lat)s)) * cos(radians(lat)) * sin(radians(lon - %(lon)s) / 2) ^ 2
                 ))) AS distancia_m,
                 tags ->> 'opening_hours' AS horario,
                 COALESCE(tags ->> 'phone', tags ->> 'contact:phone') AS telefone,
                 tags ->> 'cuisine' AS cozinha
          FROM pois
          WHERE categoria = %(cat)s
            AND local <@ %(caixa)s::box                        -- filtro rápido (índice)
            -- sem nome só faz sentido para posto ("Posto de combustível a 300 m")
            AND (nome IS NOT NULL OR categoria IN ('abastecer', 'dormir'))
        ) t
        WHERE distancia_m <= %(raio)s
        ORDER BY distancia_m
        LIMIT %(limite)s
        """,
        {"lat": lat, "lon": lon, "cat": cat, "raio": raio_m, "limite": limite,
         "caixa": _caixa_sql(lat, lon, raio_m)},
    )
