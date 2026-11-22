"""Importa os mapas do IBGE (shapefiles) para o PostgreSQL — sem PostGIS nem GDAL.

Uso (rode DEPOIS de importar os municípios):
    .venv/bin/python scripts/importar_mapas.py areas  dados/ibge/areas.zip
    .venv/bin/python scripts/importar_mapas.py biomas dados/ibge/biomas.zip
    .venv/bin/python scripts/importar_mapas.py relevo dados/ibge/relevo.zip
    .venv/bin/python scripts/importar_mapas.py baixar   # baixa os 3 arquivos em dados/ibge

Aceita o .zip do IBGE ou o .shp já descompactado. O shapefile precisa estar em
graus (SIRGAS 2000 ou WGS84), que é como o IBGE publica esses mapas.

Áreas urbanas: os fragmentos do IBGE são agrupados num único contorno por
município (só entram municípios já importados). "Loteamento vazio" é ignorado.
"""
import json
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

    with con.cursor() as cur:
        cur.execute("TRUNCATE areas_urbanas")
        for codigo, polis in por_mun.items():
            g = geo.simplificar({"type": "MultiPolygon", "coordinates": polis}, TOLERANCIA["areas"])
            if not g:
                continue
            rep = geo.ponto_representativo(g)
            cur.execute(
                """INSERT INTO areas_urbanas (codigo_ibge, geom, caixa, clat, clon, area_m2)
                   VALUES (%s, %s::jsonb, %s::box, %s, %s, %s)""",
                (codigo, json.dumps(g), geo.caixa_sql(geo.caixa(g)), rep[1], rep[0], geo.area_m2(g)),
            )


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
        else:
            importar_poligonos(tipo, r, con)
        con.execute(f"ANALYZE {'areas_urbanas' if tipo == 'areas' else tipo}")
    print(f"Importação de '{tipo}' concluída.")


if __name__ == "__main__":
    main()
