"""Importa pontos de interesse do OpenStreetMap (Nordeste) para a tabela "pois".

Sem PostGIS nem GDAL: lê o .osm.pbf com pyosmium e classifica em Python.

Uso:
    .venv/bin/python scripts/importar_osm.py                   # regiões do .env (OSM_REGIOES)
    .venv/bin/python scripts/importar_osm.py nordeste sudeste  # baixa essas regiões da Geofabrik
    .venv/bin/python scripts/importar_osm.py arquivo.osm.pbf   # usa um arquivo já baixado

Regiões: norte, nordeste, centro-oeste, sudeste, sul (ou "brasil", ~2 GB).
Importe sempre todas as regiões desejadas numa mesma chamada: o que não
aparecer nos arquivos lidos é apagado da tabela.

Pode rodar de novo quando quiser atualizar (ex.: 1x por mês); quem sumiu do
OSM é apagado. Dados © colaboradores do OpenStreetMap, licença ODbL — cite no app.

Pontos (nós) e áreas fechadas (ways) entram; para áreas, a posição é a média
dos vértices. Relações (multipolígonos grandes) são ignoradas.
"""
import json
import os
import sys
import time
from pathlib import Path

import httpx
import osmium
import psycopg

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ))
from app import config  # noqa: E402

GEOFABRIK = "https://download.geofabrik.de/south-america/brazil"
REGIOES = ("norte", "nordeste", "centro-oeste", "sudeste", "sul", "brasil")


def url_regiao(regiao: str) -> str:
    if regiao == "brasil":
        return f"{GEOFABRIK}/brazil-latest.osm.pbf"
    return f"{GEOFABRIK}/{regiao}/{regiao}-latest.osm.pbf"
CHAVES = ("amenity", "tourism", "shop", "historic", "leisure")


def classificar(t: dict) -> str | None:
    amenity, tourism, shop = t.get("amenity"), t.get("tourism"), t.get("shop")
    historic, leisure = t.get("historic"), t.get("leisure")
    if amenity in ("restaurant", "cafe", "fast_food", "food_court", "ice_cream"):
        return "comer"
    if tourism in ("hotel", "motel", "guest_house", "hostel", "chalet", "camp_site", "apartment"):
        return "dormir"
    if amenity in ("fuel", "charging_station") or shop in ("tyres", "car_repair"):
        return "abastecer"
    if (tourism in ("attraction", "viewpoint", "museum", "artwork", "gallery", "theme_park", "zoo")
            or historic in ("monument", "memorial", "castle", "ruins", "archaeological_site", "fort")
            or leisure == "nature_reserve"):
        return "ver"
    return None


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


def baixar(regiao: str) -> Path:
    url = url_regiao(regiao)
    destino = RAIZ / "dados" / "osm" / f"{regiao}-latest.osm.pbf"
    destino.parent.mkdir(parents=True, exist_ok=True)
    print(f"Baixando {url} → {destino}")
    baixar_arquivo(url, destino)
    return destino


def arquivos(args: list[str]) -> list[Path]:
    if not args:
        args = os.getenv("OSM_REGIOES", "nordeste").replace(",", " ").split()
    saida = []
    for a in args:
        if a.lower() in REGIOES:
            saida.append(baixar(a.lower()))
        elif Path(a).is_file():
            saida.append(Path(a))
        else:
            sys.exit(f"'{a}' não é região ({', '.join(REGIOES)}) nem arquivo .osm.pbf")
    return saida


def ler_pois(pbf: Path):
    proc = (
        osmium.FileProcessor(str(pbf), osmium.osm.NODE | osmium.osm.WAY)
        .with_locations()
        .with_filter(osmium.filter.KeyFilter(*CHAVES))
    )
    for obj in proc:
        tags = {t.k: t.v for t in obj.tags}
        cat = classificar(tags)
        if not cat:
            continue
        if obj.is_node():
            if not obj.location.valid():
                continue
            osm_id, lat, lon = f"n{obj.id}", obj.location.lat, obj.location.lon
        else:
            locs = [n.location for n in obj.nodes if n.location.valid()]
            if not locs:
                continue
            if len(locs) > 1 and obj.is_closed():
                locs = locs[:-1]  # o último repete o primeiro
            osm_id = f"w{obj.id}"
            lat = sum(p.lat for p in locs) / len(locs)
            lon = sum(p.lon for p in locs) / len(locs)
        tipo = next((tags[k] for k in CHAVES if k in tags), None)
        yield (osm_id, cat, tipo, tags.get("name"), json.dumps(tags, ensure_ascii=False),
               round(lat, 7), round(lon, 7))


def main() -> None:
    pbfs = arquivos(sys.argv[1:])
    with psycopg.connect(config.dsn()) as con:
        con.execute("""CREATE TEMP TABLE stg_pois (
            osm_id text, categoria text, tipo text, nome text, tags jsonb,
            lat double precision, lon double precision) ON COMMIT DROP""")
        n = 0
        with con.cursor().copy(
            "COPY stg_pois (osm_id, categoria, tipo, nome, tags, lat, lon) FROM STDIN"
        ) as copy:
            for pbf in pbfs:
                print(f"Lendo {pbf.name}")
                for linha in ler_pois(pbf):
                    copy.write_row(linha)
                    n += 1
                    if n % 20000 == 0:
                        print(f"  {n} pontos lidos")
        print(f"{n} pontos lidos do OSM")
        if n == 0:
            sys.exit("Nenhum ponto lido; nada foi alterado na tabela pois.")
        con.execute("""
            INSERT INTO pois (osm_id, categoria, tipo, nome, tags, lat, lon)
            SELECT DISTINCT ON (osm_id) osm_id, categoria, tipo, nome, tags, lat, lon FROM stg_pois
            ON CONFLICT (osm_id) DO UPDATE SET
              categoria = EXCLUDED.categoria, tipo = EXCLUDED.tipo, nome = EXCLUDED.nome,
              tags = EXCLUDED.tags, lat = EXCLUDED.lat, lon = EXCLUDED.lon""")
        apagados = con.execute(
            "DELETE FROM pois p WHERE NOT EXISTS (SELECT 1 FROM stg_pois s WHERE s.osm_id = p.osm_id)"
        ).rowcount
        por_cat = con.execute("SELECT categoria, count(*) FROM pois GROUP BY 1 ORDER BY 1").fetchall()
        con.commit()
        con.execute("ANALYZE pois")
    print(f"Removidos (não existem mais no OSM): {apagados}")
    print("POIs por categoria:", dict(por_cat))


if __name__ == "__main__":
    main()
