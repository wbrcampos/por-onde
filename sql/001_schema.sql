-- Por Onde — esquema do banco "localizacao" (PostgreSQL puro, sem PostGIS)
-- Executar uma vez:  .venv/bin/python scripts/criar_tabelas.py
--
-- Geometrias:  coluna "geom" em jsonb com o GeoJSON (Polygon/MultiPolygon,
--              coordenadas [lon, lat] em WGS84 — o mesmo sistema do GPS).
-- Busca rápida: coluna "caixa" (tipo box nativo, x = lon, y = lat) com índice
--              GiST. O banco filtra pela caixa e o Python (app/geo.py) confere
--              o polígono exato.
-- clat/clon:   ponto representativo dentro da área (usado para clima).

-- Municípios do IBGE (nome, UF, população e contorno)
CREATE TABLE IF NOT EXISTS municipios (
    codigo_ibge  integer PRIMARY KEY,
    nome         text    NOT NULL,
    uf           char(2) NOT NULL,
    estado       text,
    populacao    integer,
    geom         jsonb,
    caixa        box,
    clat         double precision,
    clon         double precision
);
CREATE INDEX IF NOT EXISTS municipios_caixa_idx ON municipios USING gist (caixa);
CREATE INDEX IF NOT EXISTS municipios_uf_idx    ON municipios (uf);

-- Manchas urbanas (IBGE "Áreas Urbanizadas"): define modo CIDADE x ESTRADA
CREATE TABLE IF NOT EXISTS areas_urbanas (
    id           serial PRIMARY KEY,
    codigo_ibge  integer REFERENCES municipios (codigo_ibge),
    geom         jsonb NOT NULL,
    caixa        box   NOT NULL,
    clat         double precision,
    clon         double precision,
    area_m2      double precision
);
CREATE INDEX IF NOT EXISTS areas_urbanas_caixa_idx ON areas_urbanas USING gist (caixa);
CREATE INDEX IF NOT EXISTS areas_urbanas_mun_idx   ON areas_urbanas (codigo_ibge);

-- Biomas (IBGE) — vegetação
CREATE TABLE IF NOT EXISTS biomas (
    id     serial PRIMARY KEY,
    nome   text  NOT NULL,
    geom   jsonb NOT NULL,
    caixa  box   NOT NULL
);
CREATE INDEX IF NOT EXISTS biomas_caixa_idx ON biomas USING gist (caixa);

-- Unidades de relevo / geomorfologia (IBGE)
CREATE TABLE IF NOT EXISTS relevo (
    id         serial PRIMARY KEY,
    nome       text  NOT NULL,
    descricao  text,
    geom       jsonb NOT NULL,
    caixa      box   NOT NULL
);
CREATE INDEX IF NOT EXISTS relevo_caixa_idx ON relevo USING gist (caixa);

-- Pontos de interesse (OpenStreetMap): comer, dormir, abastecer, ver
CREATE TABLE IF NOT EXISTS pois (
    id         bigserial PRIMARY KEY,
    osm_id     text UNIQUE NOT NULL,
    categoria  text NOT NULL CHECK (categoria IN ('comer', 'dormir', 'abastecer', 'ver')),
    tipo       text,                -- ex.: restaurant, hotel, fuel, viewpoint
    nome       text,
    tags       jsonb,
    lat        double precision NOT NULL,
    lon        double precision NOT NULL,
    local      point GENERATED ALWAYS AS (point(lon, lat)) STORED
);
CREATE INDEX IF NOT EXISTS pois_local_idx ON pois USING gist (local);
CREATE INDEX IF NOT EXISTS pois_cat_idx   ON pois (categoria);

-- Textos e foto da cidade (Wikipedia / Wikimedia Commons), preenchidos sob demanda
CREATE TABLE IF NOT EXISTS cidade_info (
    codigo_ibge    integer PRIMARY KEY REFERENCES municipios (codigo_ibge),
    wiki_titulo    text,
    descricao      text,
    resumo         text,
    wiki_url       text,
    foto_url       text,
    foto_credito   text,
    foto_licenca   text,
    atualizado_em  timestamptz NOT NULL DEFAULT now()
);

-- Cache genérico (clima, altitude, respostas de APIs externas)
CREATE TABLE IF NOT EXISTS cache (
    chave      text PRIMARY KEY,
    dados      jsonb NOT NULL,
    expira_em  timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS cache_expira_idx ON cache (expira_em);
