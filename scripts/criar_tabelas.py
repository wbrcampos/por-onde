"""Cria as tabelas do Por Onde no PostgreSQL (não precisa de psql nem de PostGIS).

Uso:  .venv/bin/python scripts/criar_tabelas.py
Pode rodar de novo sem problema (usa CREATE ... IF NOT EXISTS).
"""
import sys
from pathlib import Path

import psycopg

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ))
from app import config  # noqa: E402

with psycopg.connect(config.dsn()) as con:
    con.execute((RAIZ / "sql" / "001_schema.sql").read_text())
    tabelas = con.execute(
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema = 'public' ORDER BY 1"
    ).fetchall()
print("Tabelas:", ", ".join(t[0] for t in tabelas))
