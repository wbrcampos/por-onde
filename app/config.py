"""Configuração lida do arquivo .env (nunca coloque senhas no código)."""
import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parent.parent / ".env")


def _req(nome: str) -> str:
    valor = os.getenv(nome)
    if not valor:
        raise RuntimeError(f"Variável {nome} não definida no .env")
    return valor


DB_HOST = os.getenv("DB_HOST", "localhost")
DB_PORT = int(os.getenv("DB_PORT", "5432"))
DB_NAME = os.getenv("DB_NAME", "localizacao")
DB_USER = os.getenv("DB_USER", "localizacao")
DB_SSLMODE = os.getenv("DB_SSLMODE", "prefer")


def dsn() -> str:
    """Monta a string de conexão do PostgreSQL."""
    return (
        f"host={DB_HOST} port={DB_PORT} dbname={DB_NAME} user={DB_USER} "
        f"password={_req('DB_PASSWORD')} sslmode={DB_SSLMODE}"
    )


# Identificação exigida pela Wikipedia/Wikimedia para uso da API
USER_AGENT = os.getenv(
    "USER_AGENT", "PorOndeApp/0.1 (contato: defina USER_AGENT no .env)"
)

# Open-Meteo: deixe vazio para o plano gratuito (uso não comercial).
# Com assinatura comercial, preencha a chave; a URL passa a ser customer-api.
OPEN_METEO_API_KEY = os.getenv("OPEN_METEO_API_KEY", "")

# Tempos de cache (minutos)
CACHE_CLIMA_MIN = int(os.getenv("CACHE_CLIMA_MIN", "30"))
CACHE_ALTITUDE_MIN = int(os.getenv("CACHE_ALTITUDE_MIN", str(60 * 24 * 30)))
CACHE_CIDADE_DIAS = int(os.getenv("CACHE_CIDADE_DIAS", "30"))

# Fator para estimar distância por estrada a partir da distância em linha reta
FATOR_ESTRADA = float(os.getenv("FATOR_ESTRADA", "1.25"))
