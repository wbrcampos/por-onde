"""Pool de conexões com o PostgreSQL e um cache simples guardado no próprio banco."""
import json
from datetime import timedelta
from typing import Any, Callable

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from . import config

pool = ConnectionPool(
    conninfo=config.dsn(),
    min_size=1,
    max_size=10,
    kwargs={"row_factory": dict_row},
    open=False,
)


def abrir() -> None:
    pool.open(wait=True)


def fechar() -> None:
    pool.close()


def um(sql: str, params: tuple | dict = ()) -> dict | None:
    with pool.connection() as con:
        return con.execute(sql, params).fetchone()


def varios(sql: str, params: tuple | dict = ()) -> list[dict]:
    with pool.connection() as con:
        return con.execute(sql, params).fetchall()


def executar(sql: str, params: tuple | dict = ()) -> None:
    with pool.connection() as con:
        con.execute(sql, params)


def em_cache(chave: str, validade: timedelta, buscar: Callable[[], Any]) -> Any:
    """Devolve o valor guardado se ainda for válido; senão busca, guarda e devolve.

    Se a fonte externa falhar e houver um valor vencido, devolve o vencido
    (melhor mostrar o clima de 40 minutos atrás do que nada, na estrada).
    """
    linha = um("SELECT dados, expira_em > now() AS valido FROM cache WHERE chave = %s", (chave,))
    if linha and linha["valido"]:
        return linha["dados"]
    try:
        valor = buscar()
    except Exception:
        if linha:
            return linha["dados"]
        raise
    executar(
        """
        INSERT INTO cache (chave, dados, expira_em) VALUES (%s, %s::jsonb, now() + %s)
        ON CONFLICT (chave) DO UPDATE SET dados = EXCLUDED.dados, expira_em = EXCLUDED.expira_em
        """,
        (chave, json.dumps(valor), validade),
    )
    return valor


def limpar_cache_vencido() -> None:
    executar("DELETE FROM cache WHERE expira_em < now() - interval '7 days'")
