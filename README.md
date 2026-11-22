# Onde Estou / Por Onde — API

Servidor que entrega ao app Android tudo sobre o lugar por onde você passa:
**modo cidade ou estrada**, município, altitude, bioma, relevo, clima, próxima
cidade (com tempo e clima) e lugares para comer, dormir, abastecer e visitar.

Python + FastAPI + PostgreSQL puro (sem PostGIS), rodando direto na VPS (sem Docker).
Os contornos ficam em GeoJSON (`jsonb`) com uma caixa envolvente (`box`, índice GiST);
o banco filtra pela caixa e o Python ([app/geo.py](app/geo.py)) confere o polígono exato.


O **app Android** (Kotlin + Jetpack Compose) está em [android/](android/README.md); cada push
que mexe nele gera o APK no GitHub Actions (artifact `por-onde-debug-apk`).

## Fontes de dados

| Dado | Fonte | Custo / licença |
|---|---|---|
| Municípios, contornos, população | API do IBGE | gratuito, oficial |
| Áreas urbanas (cidade x estrada), biomas, relevo | mapas do IBGE, guardados no PostgreSQL | gratuito |
| Restaurantes, hotéis, postos, atrativos | OpenStreetMap (recorte Nordeste) | gratuito, ODbL: citar "© OpenStreetMap" |
| Clima e altitude | Open-Meteo | grátis só **não comercial**; app comercial precisa de plano pago |
| Resumo e foto da cidade | Wikipedia + Wikimedia Commons | gratuito, CC BY-SA: mostrar crédito da foto |

## Endpoints

| Rota | O que devolve |
|---|---|
| `GET /contexto?lat=&lon=&rumo=&velocidade=` | Chamada principal: modo (`cidade`/`estrada`), município, zona, altitude, bioma, relevo, clima e **próxima cidade** na direção do carro (km, minutos, clima) |
| `GET /cidade/{codigo_ibge}` | Modo cidade: resumo, foto com crédito, destaques, artigos próximos, clima |
| `GET /lugares?lat=&lon=&cat=comer\|dormir\|abastecer\|ver` | Lugares por perto, do mais próximo ao mais longe |
| `GET /saude` | Teste de vida da API |

Documentação interativa: `https://SEU-SERVIDOR/docs`.

> `rumo` é a direção do GPS em graus (o Android dá em `Location.getBearing()`).
> Sem ele, a "próxima cidade" é só a mais perto. O tempo de chegada é **estimado**
> (distância em linha reta × 1,25 ÷ velocidade); com rota definida no app, troque
> pelo valor do Google Routes.

## Instalação na VPS (Ubuntu/Debian)

### 1. Pacotes

```bash
sudo apt install python3-venv
```

Não precisa de PostGIS, GDAL nem `psql`: tudo roda pelos scripts Python.

### 2. Banco

Um banco PostgreSQL (versão 12 ou mais nova) com um usuário que seja dono dele.
Nenhuma extensão é necessária.

### Atalho: `rodar.sh`

Um comando prepara o ambiente (venv + dependências), confere o banco,
importa/atualiza os dados e sobe a API:

```bash
./rodar.sh                 # tudo (estados de IMPORTAR_UFS no .env)
./rodar.sh PE PB AL        # tudo, só esses estados
./rodar.sh dados           # só importa/atualiza os dados (aceita --brasil, --apagar-downloads…)
./rodar.sh osm             # só atualiza os lugares do OpenStreetMap
./rodar.sh api             # só sobe a API
./rodar.sh atualizar       # git pull + dependências + reinicia a API
```

Com o serviço `porondeapi` do systemd instalado (passo 6), a API é reiniciada
por ele; sem o serviço, o uvicorn roda em primeiro plano na porta 8010
(`PORTA=8020 ./rodar.sh api` para trocar). Os passos abaixo detalham o que o
script faz.

### 3. Código e configuração

```bash
sudo git clone https://github.com/wbrcampos/por-onde.git /opt/ondeestou
cd /opt/ondeestou
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env && nano .env     # preencha DB_HOST, DB_PASSWORD e USER_AGENT
chmod 600 .env
```

### 4. Tabelas e dados (um comando só)

```bash
.venv/bin/python scripts/importar_tudo.py              # estados de IMPORTAR_UFS no .env (padrão: Nordeste)
.venv/bin/python scripts/importar_tudo.py PE PB AL     # ou escolha os estados
.venv/bin/python scripts/importar_tudo.py --brasil     # Brasil inteiro (OSM ~2 GB)
```

Cria as tabelas e baixa e importa, nesta ordem: municípios (IBGE), áreas
urbanizadas 2022, biomas 1:250 mil, relevo 1:5 mi e lugares do OpenStreetMap
da região de cada estado. No Nordeste leva uns 5 minutos e baixa ~510 MB em
`dados/` (use `--apagar-downloads` para apagar no fim). Pode rodar de novo a
qualquer momento: atualiza no lugar, sem duplicar.

### 5. Atualização mensal do OpenStreetMap (cron)

```bash
sudo crontab -u www-data -e
# todo dia 1º às 4h:
0 4 1 * * cd /opt/ondeestou && ./rodar.sh osm >> importacao.log 2>&1
```

Os scripts de cada etapa também podem ser rodados sozinhos:
`criar_tabelas.py`, `importar_municipios.py`, `importar_mapas.py` e `importar_osm.py`
(veja o cabeçalho de cada um).

### 6. Deixar rodando

```bash
sudo chown -R www-data:www-data /opt/ondeestou
sudo cp deploy/porondeapi.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now porondeapi
curl http://127.0.0.1:8010/saude          # {"ok":true}
```

Para acesso externo com HTTPS, use `deploy/nginx-porondeapi.conf` + certbot.

## Como funciona o modo cidade x estrada

O contorno do município inclui a zona rural, então não serve. A API usa as
**áreas urbanizadas do IBGE** junto com os **pontos oficiais das sedes e vilas**
(IBGE Localidades 2022):

1. Os pedaços da mancha urbana de cada município a até 200 m uns dos outros
   (`DIST_NUCLEO_M`) viram um **núcleo**.
2. O núcleo que contém o ponto da sede municipal é a **sede** — não o maior: às
   vezes a ocupação ao longo da rodovia é maior que a cidade (ex.: Sairé, PE).
   Núcleos com uma vila oficial são distritos.
3. GPS dentro da sede ou de uma vila = `cidade` (o campo `localidade` diz qual);
   em qualquer outro lugar, inclusive pedaços urbanos isolados à beira da
   estrada, = `estrada` (e a resposta diz "zona rural de Gravatá").
4. **Próxima cidade** considera só sedes: a distância é até a borda da sede e a
   direção é até o centro dela (a borda mais próxima pode estar de lado).

## Cache

Tudo que vem de fora fica guardado na tabela `cache`: clima 30 min, altitude
30 dias, textos da cidade 30 dias. Se a fonte cair, a API devolve o último
valor guardado em vez de erro.

## Desenvolvimento

```bash
.venv/bin/uvicorn app.main:app --reload --port 8010   # http://localhost:8010/docs
```
