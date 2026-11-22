"""Importa os mapas do IBGE (shapefiles) para o PostgreSQL — sem PostGIS nem GDAL.

Uso (rode DEPOIS de importar os municípios; "localidades" antes de "areas"):
    .venv/bin/python scripts/importar_mapas.py localidades dados/ibge/localidades.zip
    .venv/bin/python scripts/importar_mapas.py areas  dados/ibge/areas.zip
    .venv/bin/python scripts/importar_mapas.py biomas dados/ibge/biomas.zip
    .venv/bin/python scripts/importar_mapas.py relevo dados/ibge/relevo.zip
    .venv/bin/python scripts/importar_mapas.py baixar   # baixa os arquivos em dados/ibge

Aceita o .zip do IBGE ou o .shp já descompactado. O shapefile precisa estar em
graus (SIRGAS 2000 ou WGS84), que é como o IBGE publica esses mapas.

Localidades: pontos oficiais das sedes municipais e das vilas (IBGE 2022).

Áreas urbanas: o IBGE publica a mancha urbana em pedaços (por densidade e
tipo). Os pedaços de cada município a até DIST_NUCLEO_M uns dos outros viram um
NÚCLEO. A sede é o núcleo que contém o ponto oficial da sede municipal (não o
maior: às vezes a ocupação ao longo da rodovia é maior que a cidade). Núcleos
sem sede nem vila (à beira da estrada, por exemplo) não fazem a API dizer "modo
cidade" nem "próxima cidade". Só entram municípios já importados;
"Loteamento vazio" é ignorado.
"""
import json
import math
import os
import sys
import time
import tempfile
import zipfile
from pathlib import Path

import httpx
import psycopg
import shapefile

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ))
from app import config, geo  # noqa: E402

GEOFTP = "https://geoftp.ibge.gov.br"
ARQUIVOS = {
    "localidades": "organizacao_do_territorio/estrutura_territorial/localidades/"
                   "Localidades_do_Brasil/2022/Localidades_Brasil_shp.zip",
    "areas": "organizacao_do_territorio/tipologias_do_territorio/areas_urbanizadas_do_brasil/"
             "2022/Shapefile/AreasUrbanizadas2022_Brasil.zip",
    "biomas": "informacoes_ambientais/estudos_ambientais/biomas/vetores/Biomas_250mil.zip",
    # Relevo em 1:5.000.000 (1 MB). Mais detalhe: geomorfologia/vetores/escala_250_mil (~530 MB)
    "relevo": "informacoes_ambientais/geomorfologia/vetores/brasil_5000_mil/Geomorfologia_5000mil.zip",
}

CAMPO_BIOMA = os.getenv("CAMPO_BIOMA", "Bioma")
CAMPO_RELEVO = os.getenv("CAMPO_RELEVO", "NOM_UNIDAD")
CAMPO_RELEVO_DESC = os.getenv("CAMPO_RELEVO_DESC", "DSC_COMPAR")
CAMPO_TIPO_URBANO = os.getenv("CAMPO_TIPO_URBANO", "Tipo")
TIPOS_URBANOS_IGNORADOS = {"Loteamento vazio"}
DIST_NUCLEO_M = float(os.getenv("DIST_NUCLEO_M", "200"))
# Localidade fora de qualquer núcleo: usa o núcleo mais próximo até esta distância
DIST_LOCALIDADE_M = float(os.getenv("DIST_LOCALIDADE_M", "500"))

# Tolerância de simplificação em graus (0,001° ≈ 110 m). Biomas têm contornos
# com centenas de milhares de pontos; simplificar deixa a consulta rápida.
TOLERANCIA = {"areas": 0.0, "biomas": 0.001, "relevo": 0.0}


def baixar_arquivo(url: str, destino: Path) -> None:
    """Baixa para um .parcial e só troca o arquivo no fim; até 5 tentativas."""
    parcial = destino.with_name(destino.name + ".parcial")
    for tentativa in range(1, 6):
        try:
            with httpx.Client(timeout=600, follow_redirects=True) as cli, cli.stream("GET", url) as r:
                r.raise_for_status()
                with open(parcial, "wb") as f:
                    for bloco in r.iter_bytes(1 << 20):
                        f.write(bloco)
            parcial.replace(destino)
            return
        except (httpx.TransportError, httpx.HTTPStatusError) as e:
            if tentativa == 5 or (isinstance(e, httpx.HTTPStatusError) and e.response.status_code < 500):
                raise
            print(f"  falha ao baixar ({e.__class__.__name__}); tentando de novo ({tentativa}/5)")
            time.sleep(10 * tentativa)


def baixar() -> None:
    destino = RAIZ / "dados" / "ibge"
    destino.mkdir(parents=True, exist_ok=True)
    for nome, caminho in ARQUIVOS.items():
        arq = destino / f"{nome}.zip"
        print(f"Baixando {nome} → {arq}")
        baixar_arquivo(f"{GEOFTP}/{caminho}", arq)


def abrir_shapefile(caminho: Path, tmp: Path) -> shapefile.Reader:
    if caminho.suffix.lower() == ".zip":
        with zipfile.ZipFile(caminho) as z:
            shps = [n for n in z.namelist() if n.lower().endswith(".shp")]
            if len(shps) != 1:
                sys.exit(f"Esperava 1 .shp dentro de {caminho}, achei: {shps}")
            base = shps[0][:-4]
            for n in z.namelist():
                if n.startswith(base + ".") or n.startswith(base.lower() + "."):
                    alvo = tmp / Path(n).name
                    with z.open(n) as src, open(alvo, "wb") as dst:
                        while bloco := src.read(1 << 20):
                            dst.write(bloco)
            caminho = tmp / Path(shps[0]).name
    # .cpg diz a codificação; sem ele o IBGE costuma usar latin1
    cpg = next((p for p in caminho.parent.glob(caminho.stem + ".[cC][pP][gG]")), None)
    enc = cpg.read_text().strip() if cpg else "latin1"
    enc = {"UTF-8": "utf-8", "UTF8": "utf-8"}.get(enc.upper(), enc)
    r = shapefile.Reader(str(caminho), encoding=enc)
    x0, y0, x1, y1 = r.bbox
    if not (-180 <= x0 <= 180 and -90 <= y0 <= 90 and -180 <= x1 <= 180 and -90 <= y1 <= 90):
        sys.exit("O shapefile não está em graus (lat/lon). Reprojete para EPSG:4674 ou 4326.")
    return r


def _geom(shape) -> dict | None:
    if shape.shapeType == shapefile.NULL or not shape.points:
        return None
    return shape.__geo_interface__


class IndiceMunicipios:
    """Grade simples (0,5°) para achar o município de um ponto rapidamente."""

    PASSO = 0.5

    def __init__(self, con: psycopg.Connection):
        self.celulas: dict[tuple[int, int], list[tuple[int, tuple, dict]]] = {}
        n = 0
        for codigo, geom in con.execute("SELECT codigo_ibge, geom FROM municipios WHERE geom IS NOT NULL"):
            bb = geo.caixa(geom)
            for cx in range(int(bb[0] // self.PASSO), int(bb[2] // self.PASSO) + 1):
                for cy in range(int(bb[1] // self.PASSO), int(bb[3] // self.PASSO) + 1):
                    self.celulas.setdefault((cx, cy), []).append((codigo, bb, geom))
            n += 1
        print(f"{n} municípios carregados para o cruzamento")

    def achar(self, lon: float, lat: float) -> int | None:
        for codigo, bb, geom in self.celulas.get((int(lon // self.PASSO), int(lat // self.PASSO)), []):
            if bb[0] <= lon <= bb[2] and bb[1] <= lat <= bb[3] and geo.contem(geom, lon, lat):
                return codigo
        return None


def _nucleos(polis: list) -> list[list]:
    """Agrupa polígonos vizinhos (vértices a até DIST_NUCLEO_M) — union-find + grade.

    Cada vértice vai para uma célula de DIST_NUCLEO_M metros; só compara com os
    vértices das 9 células vizinhas, então o custo cresce de forma linear.
    """
    n = len(polis)
    if n == 1:
        return [polis]
    lat0 = sum(geo.caixa({"type": "Polygon", "coordinates": p})[1] for p in polis) / n
    kx, ky = geo.M_POR_GRAU_LAT * math.cos(math.radians(lat0)), geo.M_POR_GRAU_LAT
    d = DIST_NUCLEO_M
    d2 = d * d
    pai = list(range(n))

    def raiz(i):
        while pai[i] != i:
            pai[i] = pai[pai[i]]
            i = pai[i]
        return i

    grade: dict[tuple[int, int], list[tuple[float, float, int]]] = {}
    for i, poli in enumerate(polis):
        for x, y, *_ in poli[0]:
            px, py = x * kx, y * ky
            cx, cy = int(px // d), int(py // d)
            ri = raiz(i)
            for vx in (cx - 1, cx, cx + 1):
                for vy in (cy - 1, cy, cy + 1):
                    for qx, qy, j in grade.get((vx, vy), ()):
                        rj = raiz(j)
                        if rj != ri and (px - qx) ** 2 + (py - qy) ** 2 <= d2:
                            pai[rj] = ri
            celula = grade.setdefault((cx, cy), [])
            # basta um vértice por pedaço em cada célula para achar os vizinhos
            if not any(j == i for _, _, j in celula):
                celula.append((px, py, i))
    grupos: dict[int, list] = {}
    for i in range(n):
        grupos.setdefault(raiz(i), []).append(polis[i])
    return list(grupos.values())


def importar_areas(r: shapefile.Reader, con: psycopg.Connection) -> None:
    indice = IndiceMunicipios(con)
    campos = [f[0] for f in r.fields[1:]]
    i_tipo = campos.index(CAMPO_TIPO_URBANO) if CAMPO_TIPO_URBANO in campos else None
    por_mun: dict[int, list] = {}
    lidos = usados = 0
    for sr in r.iterShapeRecords():
        lidos += 1
        if lidos % 20000 == 0:
            print(f"  {lidos} fragmentos lidos, {usados} dentro dos municípios importados")
        if i_tipo is not None and sr.record[i_tipo] in TIPOS_URBANOS_IGNORADOS:
            continue
        x0, y0, x1, y1 = sr.shape.bbox
        if not indice.celulas.get((int(x0 // indice.PASSO), int(y0 // indice.PASSO))) and \
           not indice.celulas.get((int(x1 // indice.PASSO), int(y1 // indice.PASSO))):
            continue  # fora da região importada
        g = _geom(sr.shape)
        rep = g and geo.ponto_representativo(g)
        if not rep:
            continue
        codigo = indice.achar(*rep)
        if codigo is None:
            continue
        por_mun.setdefault(codigo, []).extend(geo.poligonos(g))
        usados += 1
    print(f"{lidos} fragmentos lidos; {usados} agrupados em {len(por_mun)} municípios")

    locs: dict[int, list[dict]] = {}
    for codigo, nome, tipo, lat, lon in con.execute(
        "SELECT codigo_ibge, nome, tipo, lat, lon FROM localidades ORDER BY tipo = 'sede' DESC"
    ):
        locs.setdefault(codigo, []).append({"nome": nome, "tipo": tipo, "lat": lat, "lon": lon})
    if not locs:
        print("AVISO: tabela localidades vazia — rode 'localidades' antes. Usando o maior núcleo como sede.")

    total = sem_sede = 0
    linhas = []
    for codigo, polis in por_mun.items():
        nucleos = []
        for grupo in _nucleos(polis):
            g = geo.simplificar({"type": "MultiPolygon", "coordinates": grupo}, TOLERANCIA["areas"])
            if g:
                nucleos.append({"g": g, "area": geo.area_m2(g), "sede": False, "loc": None, "ponto": None})
        if not nucleos:
            continue
        nucleos.sort(key=lambda n: n["area"], reverse=True)
        # cada sede/vila marca o núcleo que a contém (ou o mais próximo, até DIST_LOCALIDADE_M)
        for loc in locs.get(codigo, []):
            alvo = next((n for n in nucleos if geo.contem(n["g"], loc["lon"], loc["lat"])), None)
            if alvo is None:
                dist, alvo = min(((geo.ate_borda(n["g"], loc["lon"], loc["lat"])[0], n) for n in nucleos),
                                 key=lambda t: t[0])
                if dist > DIST_LOCALIDADE_M:
                    continue
            if alvo["loc"] is None:  # sedes vêm primeiro: não são trocadas por vila
                alvo["loc"] = loc["nome"]
                alvo["sede"] = loc["tipo"] == "sede"
                alvo["ponto"] = (loc["lon"], loc["lat"])
        if not any(n["sede"] for n in nucleos):
            sem_sede += 1
            nucleos[0]["sede"] = True  # sem ponto oficial: o maior núcleo
        for n in nucleos:
            ponto = n["ponto"] or geo.ponto_representativo(n["g"])
            linhas.append((codigo, n["sede"], n["loc"], json.dumps(n["g"]),
                           geo.caixa_sql(geo.caixa(n["g"])), ponto[1], ponto[0], n["area"]))
        total += len(nucleos)
    # Um COPY só (gravar linha a linha num banco remoto leva uma ida e volta por linha)
    with con.cursor() as cur:
        cur.execute("TRUNCATE areas_urbanas")
        with cur.copy("COPY areas_urbanas (codigo_ibge, sede, localidade, geom, caixa, clat, clon, area_m2) "
                      "FROM STDIN") as copy:
            for linha in linhas:
                copy.write_row(linha)
    print(f"{total} núcleos urbanos gravados; {len(por_mun)} sedes"
          + (f" ({sem_sede} sem ponto oficial: usado o maior núcleo)" if sem_sede else ""))


def importar_localidades(r: shapefile.Reader, con: psycopg.Connection) -> None:
    campos = [f[0] for f in r.fields[1:]]
    faltam = {"CD_MUN", "CT_LOCALID", "SCT_LOCALI", "CD_LOCALID", "NM_LOCALID"} - set(campos)
    if faltam:
        sys.exit(f"Colunas {faltam} não existem. Colunas do arquivo: {campos}")
    municipios = {c for (c,) in con.execute("SELECT codigo_ibge FROM municipios")}
    linhas: dict[str, tuple] = {}
    for sr in r.iterShapeRecords():
        rec = sr.record.as_dict()
        if rec["CT_LOCALID"] == "Cidade" and rec["SCT_LOCALI"] == "Sede Municipal":
            tipo = "sede"
        elif rec["CT_LOCALID"] == "Vila":
            tipo = "vila"
        else:
            continue
        codigo = int(rec["CD_MUN"])
        if codigo not in municipios or not sr.shape.points:
            continue
        lon, lat = sr.shape.points[0][:2]
        linhas.setdefault(str(rec["CD_LOCALID"]), (str(rec["CD_LOCALID"]), codigo,
                                                   rec["NM_LOCALID"].strip(), tipo, lat, lon))
    with con.cursor() as cur:
        cur.execute("TRUNCATE localidades")
        with cur.copy("COPY localidades (codigo, codigo_ibge, nome, tipo, lat, lon) FROM STDIN") as copy:
            for linha in linhas.values():
                copy.write_row(linha)
    print(f"{len(linhas)} localidades (sedes e vilas) gravadas")


def importar_poligonos(tipo: str, r: shapefile.Reader, con: psycopg.Connection) -> None:
    campos = [f[0] for f in r.fields[1:]]
    nome_campo = CAMPO_BIOMA if tipo == "biomas" else CAMPO_RELEVO
    if nome_campo not in campos:
        sys.exit(f"Coluna '{nome_campo}' não existe. Colunas do arquivo: {campos}")
    desc_campo = CAMPO_RELEVO_DESC if tipo == "relevo" and CAMPO_RELEVO_DESC in campos else None
    n = 0
    with con.cursor() as cur:
        cur.execute(f"TRUNCATE {tipo}")
        for sr in r.iterShapeRecords():
            g = _geom(sr.shape)
            g = g and geo.simplificar(g, TOLERANCIA[tipo])
            if not g:
                continue
            rec = sr.record.as_dict()
            nome = str(rec[nome_campo]).strip()
            if not nome:
                continue
            caixa = geo.caixa_sql(geo.caixa(g))
            if tipo == "biomas":
                cur.execute(
                    "INSERT INTO biomas (nome, geom, caixa) VALUES (%s, %s::jsonb, %s::box)",
                    (nome, json.dumps(g), caixa),
                )
            else:
                desc = str(rec[desc_campo]).strip() if desc_campo else ""
                cur.execute(
                    "INSERT INTO relevo (nome, descricao, geom, caixa) VALUES (%s, %s, %s::jsonb, %s::box)",
                    (nome, desc or None, json.dumps(g), caixa),
                )
            n += 1
    print(f"{n} polígonos gravados em '{tipo}'")


def main() -> None:
    if len(sys.argv) == 2 and sys.argv[1] == "baixar":
        baixar()
        return
    if len(sys.argv) != 3 or sys.argv[1] not in ARQUIVOS:
        sys.exit(__doc__)
    tipo, caminho = sys.argv[1], Path(sys.argv[2])
    with tempfile.TemporaryDirectory() as tmp, psycopg.connect(config.dsn()) as con:
        r = abrir_shapefile(caminho, Path(tmp))
        print(f"{caminho.name}: {len(r)} feições, colunas {[f[0] for f in r.fields[1:]]}")
        if tipo == "areas":
            importar_areas(r, con)
        elif tipo == "localidades":
            importar_localidades(r, con)
        else:
            importar_poligonos(tipo, r, con)
        con.execute(f"ANALYZE {'areas_urbanas' if tipo == 'areas' else tipo}")
    print(f"Importação de '{tipo}' concluída.")


if __name__ == "__main__":
    main()
