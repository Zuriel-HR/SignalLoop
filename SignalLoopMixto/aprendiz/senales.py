"""Señal de falla: compara lo esperado con lo obtenido en cada corrida."""
from __future__ import annotations

SENALES = ("duplicado", "sin_cierre", "falso_negativo", "alerta_falsa")


def senales(corrida: dict) -> list[str]:
    s = set(corrida.get("senales") or [])
    e, o = corrida["esperado"], corrida["obtenido"]
    if e["tickets_creados"] == 0 and o["tickets_creados"] > 0:
        s.add("alerta_falsa")
    elif o["tickets_creados"] > e["tickets_creados"]:
        s.add("duplicado")
    if o["tickets_creados"] < e["tickets_creados"]:
        s.add("falso_negativo")
    if o["tickets_abiertos"] > e["tickets_abiertos"]:
        s.add("sin_cierre")
    return [x for x in SENALES if x in s]


def es_normal(lote: list[dict]) -> bool:
    c = lote[0]
    return not c.get("candidata") and not c.get("sin")


def lote_base(lotes: dict[str, list[dict]], activas: list[str]) -> tuple[str, list[dict]] | tuple[None, None]:
    """Último lote normal corrido exactamente con las lecciones activas actuales."""
    for nombre in reversed(list(lotes)):
        filas = lotes[nombre]
        if es_normal(filas) and sorted(filas[0]["lecciones_activas"]) == sorted(activas):
            return nombre, filas
    return None, None


def lote_candidata(lotes, activas, codigo) -> tuple[str, list[dict]] | tuple[None, None]:
    for nombre in reversed(list(lotes)):
        c = lotes[nombre][0]
        if c.get("candidata") == codigo and sorted(c["lecciones_activas"]) == sorted(activas):
            return nombre, lotes[nombre]
    return None, None


def lote_ablacion(lotes, codigo) -> tuple[str, list[dict]] | tuple[None, None]:
    for nombre in reversed(list(lotes)):
        c = lotes[nombre][0]
        if codigo in (c.get("sin") or []) and not c.get("candidata"):
            return nombre, lotes[nombre]
    return None, None


def por_caso(filas: list[dict]) -> dict[str, dict]:
    return {f["caso"]: f for f in filas}
