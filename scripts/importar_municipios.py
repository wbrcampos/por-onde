"""Importa municípios do IBGE: nome, UF, contorno e população (Censo 2022).

Uso:
    python scripts/importar_municipios.py            # Brasil inteiro
    python scripts/importar_municipios.py PE PB AL   # só alguns estados

Fontes (gratuitas, oficiais):
  - Localidades: https://servicodados.ibge.gov.br/api/v1/localidades/municipios
  - Malhas:      https://servicodados.ibge.gov.br/api/v3/malhas/estados/{UF}
  - População:   API de agregados (SIDRA). Tabela e variável configuráveis no .env
                 (IBGE_TABELA_POP / IBGE_VARIAVEL_POP); confira em
                 https://servicodados.ibge.gov.br/api/docs/agregados?versao=3
"""
import json
import os
import sys
import time
from pathlib import Path

import httpx
import psycopg

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from app import config, geo  # noqa: E402

IBGE = "https://servicodados.ibge.gov.br/api"
TABELA_POP = os.getenv("IBGE_TABELA_POP", "4714")     # Censo 2022 — população residente
VARIAVEL_POP = os.getenv("IBGE_VARIAVEL_POP", "93")   # População residente (pessoas)
PERIODO_POP = os.getenv("IBGE_PERIODO_POP", "2022")


def _get(cli: httpx.Client, url: str, **kw) -> httpx.Response:
    """GET com até 5 tentativas: a API do IBGE às vezes corta a conexão no meio."""
    for tentativa in range(1, 6):
        try:
            r = cli.get(url, **kw)
            r.raise_for_status()
            return r
        except (httpx.TransportError, httpx.HTTPStatusError) as e:
            if tentativa == 5 or (isinstance(e, httpx.HTTPStatusError) and e.response.status_code < 500):
                raise
            print(f"  falha em {url} ({e.__class__.__name__}); tentando de novo ({tentativa}/5)")
            time.sleep(5 * tentativa)
    raise RuntimeError("inalcançável")


def uf_do_municipio(m: dict) -> tuple[str, str]:
    """A API às vezes traz microrregião nula; usa a região imediata como alternativa."""
    try:
        uf = m["microrregiao"]["mesorregiao"]["UF"]
    except (TypeError, KeyError):
        uf = m["regiao-imediata"]["regiao-intermediaria"]["UF"]
    return uf["sigla"], uf["nome"]


def buscar_municipios(cli: httpx.Client) -> list[dict]:
    r = _get(cli, f"{IBGE}/v1/localidades/municipios")
    return r.json()


def buscar_malha_uf(cli: httpx.Client, uf: str) -> dict[int, dict]:
    r = _get(
        cli,
        f"{IBGE}/v3/malhas/estados/{uf}",
        params={
            "formato": "application/vnd.geo+json",
            "intrarregiao": "municipio",
            "qualidade": "intermediaria",
        },
    )
    feats = r.json()["features"]
    return {int(f["properties"]["codarea"]): f["geometry"] for f in feats}


def buscar_populacao(cli: httpx.Client) -> dict[int, int]:
    url = (
        f"{IBGE}/v3/agregados/{TABELA_POP}/periodos/{PERIODO_POP}"
        f"/variaveis/{VARIAVEL_POP}"
    )
    try:
        r = _get(cli, url, params={"localidades": "N6[all]"})
        pops: dict[int, int] = {}
        for res in r.json()[0]["resultados"]:
            for serie in res["series"]:
                valor = serie["serie"].get(PERIODO_POP)
                if valor and valor.isdigit():
                    pops[int(serie["localidade"]["id"])] = int(valor)
        print(f"População: {len(pops)} municípios")
        return pops
    except Exception as e:  # a população é opcional; o resto continua
        print(f"AVISO: não foi possível ler a população ({e}). Confira a tabela no .env.")
        return {}


def main() -> None:
    filtro = {u.upper() for u in sys.argv[1:]}
    with httpx.Client(timeout=120, headers={"User-Agent": config.USER_AGENT}) as cli:
        municipios = buscar_municipios(cli)
        populacao = buscar_populacao(cli)

        por_uf: dict[str, list[dict]] = {}
        for m in municipios:
            sigla, nome_uf = uf_do_municipio(m)
            if filtro and sigla not in filtro:
                continue
            m["_uf"], m["_estado"] = sigla, nome_uf
            por_uf.setdefault(sigla, []).append(m)

        with psycopg.connect(config.dsn()) as con:
            for uf, lista in sorted(por_uf.items()):
                malha = buscar_malha_uf(cli, uf)
                with con.cursor() as cur:
                    for m in lista:
                        geom = malha.get(int(m["id"]))
                        # A malha do IBGE vem em SIRGAS 2000 (EPSG:4674), que na prática
                        # coincide com o WGS84 do GPS (diferença < 1 m).
                        bb = geo.caixa(geom) if geom else None
                        rep = geo.ponto_representativo(geom) if geom else None
                        cur.execute(
                            """
                            INSERT INTO municipios (codigo_ibge, nome, uf, estado, populacao,
                                                    geom, caixa, clat, clon)
                            VALUES (%s, %s, %s, %s, %s, %s::jsonb, %s::box, %s, %s)
                            ON CONFLICT (codigo_ibge) DO UPDATE SET
                                nome = EXCLUDED.nome, uf = EXCLUDED.uf, estado = EXCLUDED.estado,
                                populacao = COALESCE(EXCLUDED.populacao, municipios.populacao),
                                geom  = COALESCE(EXCLUDED.geom,  municipios.geom),
                                caixa = COALESCE(EXCLUDED.caixa, municipios.caixa),
                                clat  = COALESCE(EXCLUDED.clat,  municipios.clat),
                                clon  = COALESCE(EXCLUDED.clon,  municipios.clon)
                            """,
                            (
                                int(m["id"]), m["nome"], uf, m["_estado"],
                                populacao.get(int(m["id"])),
                                json.dumps(geom) if geom else None,
                                f"(({bb[0]},{bb[1]}),({bb[2]},{bb[3]}))" if bb else None,
                                rep[1] if rep else None,
                                rep[0] if rep else None,
                            ),
                        )
                con.commit()
                print(f"{uf}: {len(lista)} municípios importados")


if __name__ == "__main__":
    main()
