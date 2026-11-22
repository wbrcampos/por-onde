"""Geometria em Python puro (sem PostGIS).

As geometrias ficam no banco como GeoJSON (Polygon ou MultiPolygon, coordenadas
[lon, lat] em WGS84). Aqui ficam as contas que o PostGIS fazia: ponto dentro do
polígono, distância até a borda, rumo, área e caixa envolvente.

Para distâncias curtas (até algumas centenas de km) usamos uma projeção plana
local, com erro bem abaixo de 1% — suficiente para "próxima cidade a 23 km".
"""
from math import atan2, cos, degrees, radians, sin, sqrt

RAIO_TERRA_M = 6_371_008.8
M_POR_GRAU_LAT = 111_320.0


def poligonos(geom: dict) -> list[list[list[list[float]]]]:
    """Normaliza Polygon/MultiPolygon para uma lista de polígonos (cada um = lista de anéis)."""
    if not geom:
        return []
    if geom["type"] == "Polygon":
        return [geom["coordinates"]]
    if geom["type"] == "MultiPolygon":
        return geom["coordinates"]
    return []


def _dentro_anel(lon: float, lat: float, anel: list[list[float]]) -> bool:
    dentro = False
    j = len(anel) - 1
    for i in range(len(anel)):
        xi, yi = anel[i][0], anel[i][1]
        xj, yj = anel[j][0], anel[j][1]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            dentro = not dentro
        j = i
    return dentro


def contem(geom: dict, lon: float, lat: float) -> bool:
    """Ponto dentro do (Multi)Polígono, respeitando buracos."""
    for poli in poligonos(geom):
        if poli and _dentro_anel(lon, lat, poli[0]) and not any(
            _dentro_anel(lon, lat, buraco) for buraco in poli[1:]
        ):
            return True
    return False


def caixa(geom: dict) -> tuple[float, float, float, float] | None:
    """(min_lon, min_lat, max_lon, max_lat)."""
    xs, ys = [], []
    for poli in poligonos(geom):
        for x, y, *_ in poli[0] if poli else []:
            xs.append(x)
            ys.append(y)
    if not xs:
        return None
    return min(xs), min(ys), max(xs), max(ys)


def _plano(lat0: float):
    kx = M_POR_GRAU_LAT * cos(radians(lat0))
    return kx, M_POR_GRAU_LAT


def area_m2(geom: dict) -> float:
    """Área aproximada em m² (fórmula do laço numa projeção plana local)."""
    bb = caixa(geom)
    if not bb:
        return 0.0
    kx, ky = _plano((bb[1] + bb[3]) / 2)

    def anel(a):
        s = 0.0
        for i in range(len(a) - 1):
            s += (a[i][0] * kx) * (a[i + 1][1] * ky) - (a[i + 1][0] * kx) * (a[i][1] * ky)
        return abs(s) / 2

    total = 0.0
    for poli in poligonos(geom):
        if poli:
            total += anel(poli[0]) - sum(anel(b) for b in poli[1:])
    return total


def ponto_representativo(geom: dict) -> tuple[float, float] | None:
    """(lon, lat) de um ponto dentro da área — usado para buscar o clima.

    Tenta o centroide do maior polígono; se cair fora (forma de "C"), usa o
    meio da maior faixa horizontal que cruza o polígono.
    """
    polis = [p for p in poligonos(geom) if p and len(p[0]) > 2]
    if not polis:
        return None
    maior = max(polis, key=lambda p: area_m2({"type": "Polygon", "coordinates": p}))
    anel = maior[0]
    a = cx = cy = 0.0
    for i in range(len(anel) - 1):
        x0, y0, x1, y1 = anel[i][0], anel[i][1], anel[i + 1][0], anel[i + 1][1]
        f = x0 * y1 - x1 * y0
        a += f
        cx += (x0 + x1) * f
        cy += (y0 + y1) * f
    if a:
        c = (cx / (3 * a), cy / (3 * a))
        if contem({"type": "Polygon", "coordinates": maior}, *c):
            return c
    # plano B: linha horizontal no meio da altura, pega o maior trecho interno
    ys = [p[1] for p in anel]
    y = (min(ys) + max(ys)) / 2
    cortes = sorted(
        anel[i][0] + (y - anel[i][1]) * (anel[i + 1][0] - anel[i][0]) / (anel[i + 1][1] - anel[i][1])
        for i in range(len(anel) - 1)
        if (anel[i][1] > y) != (anel[i + 1][1] > y)
    )
    trechos = [(cortes[i], cortes[i + 1]) for i in range(0, len(cortes) - 1, 2)]
    if trechos:
        x0, x1 = max(trechos, key=lambda t: t[1] - t[0])
        return (x0 + x1) / 2, y
    return anel[0][0], anel[0][1]


def distancia_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Haversine."""
    p1, p2 = radians(lat1), radians(lat2)
    dp, dl = p2 - p1, radians(lon2 - lon1)
    h = sin(dp / 2) ** 2 + cos(p1) * cos(p2) * sin(dl / 2) ** 2
    return 2 * RAIO_TERRA_M * atan2(sqrt(h), sqrt(1 - h))


def ate_borda(geom: dict, lon: float, lat: float) -> tuple[float, float]:
    """Distância (m) e rumo (graus, 0 = norte) do ponto até o ponto mais próximo da área.

    Se o ponto estiver dentro, a distância é 0.
    """
    if contem(geom, lon, lat):
        return 0.0, 0.0
    kx, ky = _plano(lat)
    melhor = (float("inf"), 0.0, 0.0)
    for poli in poligonos(geom):
        for anel in poli:
            for i in range(len(anel) - 1):
                ax, ay = (anel[i][0] - lon) * kx, (anel[i][1] - lat) * ky
                bx, by = (anel[i + 1][0] - lon) * kx, (anel[i + 1][1] - lat) * ky
                dx, dy = bx - ax, by - ay
                L = dx * dx + dy * dy
                t = 0.0 if L == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / L))
                px, py = ax + t * dx, ay + t * dy
                d2 = px * px + py * py
                if d2 < melhor[0]:
                    melhor = (d2, px, py)
    d2, px, py = melhor
    return sqrt(d2), degrees(atan2(px, py)) % 360


def caixa_ao_redor(lat: float, lon: float, raio_m: float) -> tuple[float, float, float, float]:
    """Caixa (min_lon, min_lat, max_lon, max_lat) que cobre um raio em metros."""
    dlat = raio_m / M_POR_GRAU_LAT
    dlon = raio_m / (M_POR_GRAU_LAT * max(cos(radians(lat)), 0.01))
    return lon - dlon, lat - dlat, lon + dlon, lat + dlat


def caixa_sql(bb: tuple[float, float, float, float] | None) -> str | None:
    """Caixa no formato do tipo box do PostgreSQL."""
    return f"(({bb[0]},{bb[1]}),({bb[2]},{bb[3]}))" if bb else None


def _simplificar_anel(anel: list, tol: float) -> list:
    """Douglas-Peucker (iterativo) num anel fechado; tol em graus."""
    n = len(anel)
    if n <= 4 or tol <= 0:
        return anel
    manter = [False] * n
    manter[0] = manter[-1] = True
    # anel fechado: divide no ponto mais distante do primeiro para não colapsar
    x0, y0 = anel[0][0], anel[0][1]
    meio = max(range(n), key=lambda i: (anel[i][0] - x0) ** 2 + (anel[i][1] - y0) ** 2)
    manter[meio] = True
    pilha = [(0, meio), (meio, n - 1)]
    tol2 = tol * tol
    while pilha:
        a, b = pilha.pop()
        if b <= a + 1:
            continue
        ax, ay = anel[a][0], anel[a][1]
        dx, dy = anel[b][0] - ax, anel[b][1] - ay
        L = dx * dx + dy * dy
        pior, idx = -1.0, -1
        for i in range(a + 1, b):
            px, py = anel[i][0] - ax, anel[i][1] - ay
            if L == 0:
                d2 = px * px + py * py
            else:
                t = max(0.0, min(1.0, (px * dx + py * dy) / L))
                ex, ey = px - t * dx, py - t * dy
                d2 = ex * ex + ey * ey
            if d2 > pior:
                pior, idx = d2, i
        if pior > tol2:
            manter[idx] = True
            pilha += [(a, idx), (idx, b)]
    saida = [p for p, m in zip(anel, manter) if m]
    return saida if len(saida) >= 4 else anel


def simplificar(geom: dict, tol: float = 0.0, casas: int = 6) -> dict | None:
    """Devolve um MultiPolygon simplificado e com coordenadas arredondadas.

    Anéis que viram menos de 4 pontos são descartados (buracos minúsculos).
    """
    polis = []
    for poli in poligonos(geom):
        aneis = []
        for i, anel in enumerate(poli):
            novo = _simplificar_anel(anel, tol)
            novo = [[round(p[0], casas), round(p[1], casas)] for p in novo]
            if len(novo) >= 4:
                aneis.append(novo)
            elif i == 0:
                break  # contorno externo sumiu: descarta o polígono inteiro
        if aneis and len(aneis[0]) >= 4:
            polis.append(aneis)
    return {"type": "MultiPolygon", "coordinates": polis} if polis else None
