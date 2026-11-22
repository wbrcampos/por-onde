"""Acesso às fontes externas: Open-Meteo (clima e altitude) e Wikipedia/Wikimedia."""
from datetime import timedelta
from urllib.parse import quote, unquote, urlparse

import httpx

from . import config, db

_http = httpx.Client(timeout=10, headers={"User-Agent": config.USER_AGENT})

# --------------------------------------------------------------------------- #
# Open-Meteo                                                                  #
# --------------------------------------------------------------------------- #
_METEO = (
    "https://customer-api.open-meteo.com" if config.OPEN_METEO_API_KEY
    else "https://api.open-meteo.com"
)

# Códigos WMO usados pela Open-Meteo → texto curto em português
_TEMPO = {
    0: "céu limpo", 1: "sol", 2: "parcialmente nublado", 3: "nublado",
    45: "neblina", 48: "neblina",
    51: "garoa fraca", 53: "garoa", 55: "garoa forte",
    61: "chuva fraca", 63: "chuva", 65: "chuva forte",
    66: "chuva gelada", 67: "chuva gelada",
    80: "pancadas fracas", 81: "pancadas de chuva", 82: "pancadas fortes",
    95: "trovoadas", 96: "trovoadas com granizo", 99: "trovoadas com granizo",
}


def _params_meteo(extra: dict) -> dict:
    if config.OPEN_METEO_API_KEY:
        extra["apikey"] = config.OPEN_METEO_API_KEY
    return extra


def altitude(lat: float, lon: float) -> float | None:
    """Altitude do terreno (modelo digital de 90 m). Cache por ~100 m."""
    chave = f"alt:{lat:.3f}:{lon:.3f}"

    def buscar():
        r = _http.get(f"{_METEO}/v1/elevation",
                      params=_params_meteo({"latitude": lat, "longitude": lon}))
        r.raise_for_status()
        return r.json()["elevation"][0]

    return db.em_cache(chave, timedelta(minutes=config.CACHE_ALTITUDE_MIN), buscar)


def clima(lat: float, lon: float, chave_local: str) -> dict:
    """Clima atual + próximas 12 horas + mín/máx do dia."""
    chave = f"clima:{chave_local}"

    def buscar():
        r = _http.get(
            f"{_METEO}/v1/forecast",
            params=_params_meteo({
                "latitude": lat, "longitude": lon,
                "current": "temperature_2m,weather_code,precipitation",
                "hourly": "temperature_2m,weather_code,precipitation_probability",
                "daily": "temperature_2m_min,temperature_2m_max,precipitation_probability_max",
                "forecast_hours": 12, "forecast_days": 1, "timezone": "auto",
            }),
        )
        r.raise_for_status()
        d = r.json()
        cur, h, dia = d["current"], d["hourly"], d["daily"]
        return {
            "temperatura": round(cur["temperature_2m"]),
            "condicao": _TEMPO.get(cur["weather_code"], "—"),
            "codigo": cur["weather_code"],
            "minima": round(dia["temperature_2m_min"][0]),
            "maxima": round(dia["temperature_2m_max"][0]),
            "chance_chuva": dia["precipitation_probability_max"][0],
            "proximas_horas": [
                {"hora": t[-5:], "temperatura": round(temp),
                 "condicao": _TEMPO.get(cod, "—"), "chance_chuva": prob}
                for t, temp, cod, prob in zip(
                    h["time"], h["temperature_2m"], h["weather_code"],
                    h["precipitation_probability"])
            ][::3],  # de 3 em 3 horas
            "fonte": "Open-Meteo (CC BY 4.0)",
        }

    return db.em_cache(chave, timedelta(minutes=config.CACHE_CLIMA_MIN), buscar)


# --------------------------------------------------------------------------- #
# Wikipedia (pt) e Wikimedia Commons                                          #
# --------------------------------------------------------------------------- #
_WIKI = "https://pt.wikipedia.org"


def _resumo(titulo: str) -> dict | None:
    r = _http.get(f"{_WIKI}/api/rest_v1/page/summary/{quote(titulo.replace(' ', '_'))}")
    if r.status_code != 200:
        return None
    d = r.json()
    return None if d.get("type") == "disambiguation" else d


def _arquivo_commons(url_imagem: str) -> str | None:
    """Nome do arquivo no Commons a partir da URL da imagem.

    Formatos vistos:
      upload.wikimedia.org/wikipedia/commons/b/b6/Arquivo.png
      upload.wikimedia.org/wikipedia/commons/thumb/b/b6/Arquivo.png/330px-Arquivo.png
      thumb.wikimedia.org/wikipedia/commons/thumb/b/b6/Arquivo.png/330px-Arquivo.png?utm_source=...
    """
    partes = [p for p in urlparse(url_imagem).path.split("/") if p]  # sem ?query
    if "thumb" in partes:
        i = partes.index("thumb")
        # depois de "thumb" vêm as 2 pastas de hash (b/b6) e o nome original
        return unquote(partes[i + 3]) if len(partes) > i + 3 else None
    return unquote(partes[-1]) if partes else None


def _credito_foto(url_imagem: str) -> tuple[str | None, str | None]:
    """Autor e licença da foto no Commons (a licença exige dar crédito)."""
    arquivo = _arquivo_commons(url_imagem)
    if not arquivo:
        return None, None
    r = _http.get(
        "https://commons.wikimedia.org/w/api.php",
        params={"action": "query", "titles": f"File:{arquivo}", "prop": "imageinfo",
                "iiprop": "extmetadata", "format": "json"},
    )
    if r.status_code != 200:
        return None, None
    for pagina in r.json().get("query", {}).get("pages", {}).values():
        meta = (pagina.get("imageinfo") or [{}])[0].get("extmetadata", {})
        autor = meta.get("Artist", {}).get("value")
        licenca = meta.get("LicenseShortName", {}).get("value")
        if autor:  # o campo vem com HTML; guardamos só o texto
            import re
            autor = re.sub(r"<[^>]+>", "", autor).strip()
        return autor, licenca
    return None, None


def info_cidade(nome: str, estado: str, lat: float, lon: float) -> dict | None:
    """Acha o artigo da cidade e confere pela coordenada que é o lugar certo."""
    for titulo in (f"{nome} ({estado})", nome):
        d = _resumo(titulo)
        if not d:
            continue
        coord = d.get("coordinates")
        if coord and (abs(coord["lat"] - lat) > 0.6 or abs(coord["lon"] - lon) > 0.6):
            continue  # outro lugar com o mesmo nome
        foto = (d.get("originalimage") or d.get("thumbnail") or {}).get("source")
        autor, licenca = _credito_foto(foto) if foto else (None, None)
        return {
            "wiki_titulo": d.get("title"),
            "descricao": d.get("description"),
            "resumo": d.get("extract"),
            "wiki_url": d.get("content_urls", {}).get("mobile", {}).get("page"),
            "foto_url": (d.get("thumbnail") or {}).get("source") or foto,
            "foto_credito": autor,
            "foto_licenca": licenca,
        }
    return None


def artigos_proximos(lat: float, lon: float, raio_m: int = 10000, limite: int = 15) -> list[dict]:
    """Artigos da Wikipedia com coordenadas perto do ponto (máx. 10 km)."""
    chave = f"wikigeo:{lat:.2f}:{lon:.2f}:{raio_m}"

    def buscar():
        r = _http.get(
            f"{_WIKI}/w/api.php",
            params={"action": "query", "list": "geosearch", "gscoord": f"{lat}|{lon}",
                    "gsradius": min(raio_m, 10000), "gslimit": limite, "format": "json"},
        )
        r.raise_for_status()
        return [
            {"titulo": a["title"], "distancia_m": round(a["dist"]),
             "url": f"{_WIKI}/wiki/{quote(a['title'].replace(' ', '_'))}"}
            for a in r.json()["query"]["geosearch"]
        ]

    return db.em_cache(chave, timedelta(days=7), buscar)


# --------------------------------------------------------------------------- #
# OSRM — rota de carro pela estrada                                           #
# --------------------------------------------------------------------------- #
_http_rota = httpx.Client(timeout=5, headers={"User-Agent": config.USER_AGENT})


def rota(lat: float, lon: float, destino_lat: float, destino_lon: float, chave_destino: str) -> dict | None:
    """Distância (km) e tempo (min) de carro até o destino, pelo OSRM.

    Cache por ~500 m de origem e por destino: o carro andando gera no máximo
    uma consulta a cada meio quilômetro. Devolve None se o OSRM estiver
    desligado ou falhar (quem chama usa a estimativa).
    """
    if not config.OSRM_URL:
        return None
    chave = f"rota:{round(lat * 200) / 200:.3f}:{round(lon * 200) / 200:.3f}:{chave_destino}"

    def buscar():
        r = _http_rota.get(
            f"{config.OSRM_URL}/route/v1/driving/{lon:.5f},{lat:.5f};{destino_lon:.5f},{destino_lat:.5f}",
            params={"overview": "false"},
        )
        r.raise_for_status()
        d = r.json()
        if d.get("code") != "Ok" or not d.get("routes"):
            raise ValueError(f"OSRM: {d.get('code')}")
        melhor = d["routes"][0]
        return {"km": melhor["distance"] / 1000, "min": melhor["duration"] / 60}

    try:
        return db.em_cache(chave, timedelta(minutes=config.CACHE_ROTA_MIN), buscar)
    except Exception:
        return None
