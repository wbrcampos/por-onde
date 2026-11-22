"""Baixa e importa TODOS os dados de uma vez (para rodar na VPS após instalar).

Uso:
    .venv/bin/python scripts/importar_tudo.py                  # estados do .env (IMPORTAR_UFS)
    .venv/bin/python scripts/importar_tudo.py PE PB AL         # só esses estados
    .venv/bin/python scripts/importar_tudo.py --brasil         # Brasil inteiro (OSM ~2 GB)
    .venv/bin/python scripts/importar_tudo.py --so-osm         # só atualiza o OSM (cron mensal)

Opções:
    --rebaixar-ibge     baixa de novo os mapas do IBGE mesmo que já existam em dados/ibge
    --apagar-downloads  apaga dados/ (zips e .pbf) no fim, para economizar disco

Ordem: tabelas → municípios → mapas do IBGE (áreas, biomas, relevo) → OpenStreetMap.
Pode rodar de novo quando quiser: tudo é atualizado no lugar, sem duplicar.
"""
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ))
from app import config  # noqa: E402,F401  (carrega o .env)

PY = sys.executable
SCRIPTS = RAIZ / "scripts"
IBGE = RAIZ / "dados" / "ibge"

# Região da Geofabrik de cada UF (o OSM é baixado por região, não por estado)
REGIAO_UF = {
    **dict.fromkeys(["AC", "AM", "AP", "PA", "RO", "RR", "TO"], "norte"),
    **dict.fromkeys(["AL", "BA", "CE", "MA", "PB", "PE", "PI", "RN", "SE"], "nordeste"),
    **dict.fromkeys(["DF", "GO", "MS", "MT"], "centro-oeste"),
    **dict.fromkeys(["ES", "MG", "RJ", "SP"], "sudeste"),
    **dict.fromkeys(["PR", "RS", "SC"], "sul"),
}
NORDESTE = "PE PB AL RN CE BA SE PI MA"


def etapa(titulo: str, *args: str) -> None:
    print(f"\n=== {titulo} ===", flush=True)
    inicio = time.monotonic()
    r = subprocess.run([PY, "-W", "ignore", *args], cwd=RAIZ)
    if r.returncode != 0:
        sys.exit(f"\nFalhou em: {titulo} (código {r.returncode}). Corrija e rode de novo.")
    print(f"--- {titulo}: ok em {time.monotonic() - inicio:.0f} s", flush=True)


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opcoes = {a for a in sys.argv[1:] if a.startswith("--")}
    desconhecidas = opcoes - {"--brasil", "--so-osm", "--rebaixar-ibge", "--apagar-downloads"}
    if desconhecidas or {"-h", "--help"} & set(sys.argv):
        sys.exit(__doc__)

    if "--brasil" in opcoes:
        ufs: list[str] = []  # importar_municipios sem argumentos = Brasil todo
        regioes = ["brasil"]
    else:
        ufs = [u.upper() for u in (args or os.getenv("IMPORTAR_UFS", NORDESTE).replace(",", " ").split())]
        invalidas = [u for u in ufs if u not in REGIAO_UF]
        if invalidas:
            sys.exit(f"UF inválida: {invalidas}")
        regioes = sorted({REGIAO_UF[u] for u in ufs})

    print(f"Estados: {' '.join(ufs) or 'Brasil inteiro'} | OSM: {', '.join(regioes)}")

    if "--so-osm" not in opcoes:
        etapa("Tabelas", str(SCRIPTS / "criar_tabelas.py"))
        etapa("Municípios", str(SCRIPTS / "importar_municipios.py"), *ufs)

        faltam = [n for n in ("areas", "biomas", "relevo") if not (IBGE / f"{n}.zip").exists()]
        if faltam or "--rebaixar-ibge" in opcoes:
            etapa("Download dos mapas do IBGE", str(SCRIPTS / "importar_mapas.py"), "baixar")
        for nome in ("areas", "biomas", "relevo"):
            etapa(f"Mapa: {nome}", str(SCRIPTS / "importar_mapas.py"), nome, str(IBGE / f"{nome}.zip"))

    etapa("OpenStreetMap", str(SCRIPTS / "importar_osm.py"), *regioes)

    if "--apagar-downloads" in opcoes:
        shutil.rmtree(RAIZ / "dados", ignore_errors=True)
        print("\nDownloads apagados (dados/).")
    print("\nTudo importado.")


if __name__ == "__main__":
    main()
