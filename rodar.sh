#!/usr/bin/env bash
# Por Onde API — prepara o ambiente, importa/atualiza os dados e sobe a API.
#
# Uso:
#   ./rodar.sh                     # tudo: ambiente + dados (estados do .env) + API
#   ./rodar.sh PE PB AL            # tudo, importando só esses estados
#   ./rodar.sh dados [opções]      # só importa/atualiza os dados (repassa opções)
#   ./rodar.sh osm                 # só atualiza os lugares do OpenStreetMap
#   ./rodar.sh api                 # só sobe a API
#   ./rodar.sh atualizar           # git pull + dependências + reinicia a API
#
# Opções de dados (repassadas a scripts/importar_tudo.py):
#   --brasil  --rebaixar-ibge  --apagar-downloads
#
# Na VPS, se o serviço "porondeapi" do systemd estiver instalado, a API é
# reiniciada por ele; senão roda o uvicorn em primeiro plano (porta $PORTA).
set -euo pipefail

cd "$(dirname "$0")"
PORTA="${PORTA:-8010}"
PY=".venv/bin/python"
SERVICO="porondeapi"

log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
erro() { printf '\033[31mERRO: %s\033[0m\n' "$*" >&2; exit 1; }

preparar() {
  log "Ambiente Python"
  command -v python3 >/dev/null || erro "python3 não encontrado (sudo apt install python3-venv)"
  if [[ ! -x "$PY" ]]; then
    python3 -m venv .venv || erro "não consegui criar o .venv (sudo apt install python3-venv)"
  fi
  "$PY" -m pip install -q --upgrade pip
  "$PY" -m pip install -q -r requirements.txt
  echo "dependências ok"

  if [[ ! -f .env ]]; then
    cp .env.example .env
    chmod 600 .env
    erro ".env criado a partir do .env.example — preencha DB_HOST, DB_PASSWORD e USER_AGENT e rode de novo"
  fi
  grep -qE '^DB_PASSWORD=.+' .env || erro "preencha DB_PASSWORD no .env"

  log "Conexão com o banco"
  "$PY" - <<'PY' || erro "não consegui conectar no banco (confira o .env e o pg_hba.conf do servidor)"
import psycopg
from app import config
with psycopg.connect(config.dsn(), connect_timeout=10) as c:
    print(c.execute("select version()").fetchone()[0].split(",")[0])
PY
}

dados() {
  log "Importando/atualizando dados"
  "$PY" -u scripts/importar_tudo.py "$@"
}

servico_instalado() {
  command -v systemctl >/dev/null && systemctl list-unit-files "$SERVICO.service" 2>/dev/null | grep -q "$SERVICO"
}

api() {
  if servico_instalado; then
    log "Reiniciando o serviço $SERVICO"
    sudo systemctl restart "$SERVICO"
    for _ in $(seq 1 20); do
      if curl -fs "http://127.0.0.1:$PORTA/saude" >/dev/null; then
        echo "API no ar: http://127.0.0.1:$PORTA/docs"
        return
      fi
      sleep 1
    done
    erro "a API não respondeu; veja: journalctl -u $SERVICO -n 50"
  else
    if command -v lsof >/dev/null && lsof -iTCP:"$PORTA" -sTCP:LISTEN >/dev/null 2>&1; then
      erro "a porta $PORTA já está em uso (rode com PORTA=8020 ./rodar.sh api)"
    fi
    log "Subindo a API em http://127.0.0.1:$PORTA/docs (Ctrl+C para parar)"
    exec .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port "$PORTA"
  fi
}

case "${1:-}" in
  dados)     shift; preparar; dados "$@" ;;
  osm)       shift; preparar; dados --so-osm "$@" ;;
  api)       preparar; api ;;
  atualizar) log "Atualizando o código"; git pull --ff-only; preparar; api ;;
  -h|--help|help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//' ;;
  *)         preparar; dados "$@"; api ;;
esac
