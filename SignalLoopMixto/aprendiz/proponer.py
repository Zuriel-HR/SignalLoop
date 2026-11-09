"""Convierte una corrida fallida en una lección candidata.

Dos proponedores con la misma salida (tipo, titulo, hipotesis, aplica_a, regla):
  - plantilla: reglas deterministas que leen la evidencia del caso. Siempre disponible.
  - llm: un modelo de OpenAI investiga la evidencia (opcional, --llm).
Ninguno escribe en lecciones/activas: lo que propongan pasa primero por el motor de
referencia y después por la compuerta con las corridas del intérprete.
"""
from __future__ import annotations

import math
from collections import Counter
from datetime import datetime
from statistics import median

_t = datetime.fromisoformat


def _items(caso):
    for ev in sorted(caso["eventos"], key=lambda e: _t(e["ts"])):
        for it in ev.get("grupo", []):
            yield ev, it


def _escalares(status: dict):
    for k, v in (status or {}).items():
        if isinstance(v, (str, int, float, bool)):
            yield k, v


def proponer_plantilla(caso: dict, corrida: dict, senales: list[str]) -> dict | None:
    s = set(senales)
    items = list(_items(caso))

    # 1. El mismo event_id llega más de una vez -> deduplicar.
    if s & {"duplicado", "alerta_falsa"}:
        conteo = Counter(ev["event_id"] for ev in caso["eventos"])
        repetidos = [i for i, n in conteo.items() if n > 1]
        if repetidos:
            tiempos = sorted(_t(ev["ts"]) for ev in caso["eventos"] if ev["event_id"] == repetidos[0])
            hueco = max((b - a).total_seconds() for a, b in zip(tiempos, tiempos[1:]))
            return {
                "tipo": "deduplicacion",
                "titulo": "Un mismo event_id abre un solo ticket",
                "hipotesis": f"En {caso['caso']} el evento {repetidos[0]} llegó {conteo[repetidos[0]]} veces "
                             f"con {hueco:.0f} s de diferencia y cada copia abrió un ticket.",
                "aplica_a": {"origen": ["*"]},
                "regla": {"clave": ["event_id"], "ventana_s": max(300, math.ceil(hueco * 10))},
            }

    alarmas = [(ev, it) for ev, it in items if it["tipo_evento"] == "Alarma"]
    recups = [(ev, it) for ev, it in items if it["tipo_evento"] == "Recuperacion"]

    # 2. Alarma y recuperación que se alternan -> flapping.
    if alarmas and recups and s & {"duplicado", "sin_cierre"}:
        huecos = []
        for ev_r, it_r in recups:
            siguiente = next((ev for ev, it in alarmas if _t(ev["ts"]) > _t(ev_r["ts"])
                              and ev["dispositivo"] == ev_r["dispositivo"] and it["sensor"] == it_r["sensor"]), None)
            if siguiente:
                huecos.append((_t(siguiente["ts"]) - _t(ev_r["ts"])).total_seconds())
        reabre = max(300, math.ceil(2 * max(huecos, default=150) / 60) * 60)
        return {
            "tipo": "flapping",
            "titulo": "Una alarma intermitente es un solo incidente y la recuperación lo cierra",
            "hipotesis": f"En {caso['caso']} hubo {len(alarmas)} alarmas y {len(recups)} recuperaciones del mismo "
                         f"sensor; cada caída abrió un ticket nuevo y ninguna recuperación lo cerró.",
            "aplica_a": {"origen": ["*"]},
            "regla": {"abre": {"tipo_evento": "Alarma"},
                      "cierra_con": [{"tipo_evento": "Recuperacion"}],
                      "reabre_en_s": reabre},
        }

    # 3. El ticket queda abierto aunque después llegó algo que debió cerrarlo -> ciclo de vida.
    if "sin_cierre" in s and alarmas:
        abierto = next((tk for tk in corrida.get("tickets") or [] if tk.get("estado") == "abierto"), None)
        ev_a, it_a = next(((ev, it) for ev, it in alarmas
                           if abierto and ev["event_id"] == abierto.get("abierto_por_evento")), alarmas[0])
        cond = (it_a.get("status") or {}).get("condicion")
        posteriores = [(ev, it) for ev, it in items
                       if _t(ev["ts"]) > _t(ev_a["ts"]) and ev["dispositivo"] == ev_a["dispositivo"]
                       and it["tipo_evento"] not in ("Alarma", "Heartbeat")
                       and (it.get("status") or {}).get("status") != 0]
        pista = caso["esperado"].get("cierre_por")
        elegido = None
        for ev, it in posteriores:
            for k, v in _escalares(it.get("status")):
                if pista is not None and v == pista:
                    elegido = (ev, it, k, v)
                    break
            if elegido:
                break
        if not elegido:
            elegido = next(((ev, it, "evento", it["status"]["evento"]) for ev, it in posteriores
                            if "evento" in (it.get("status") or {})), None)
        if elegido:
            ev_c, it_c, k, v = elegido
            abre = {"tipo_evento": "Alarma", "sensor": it_a["sensor"]}
            if cond is not None:
                abre.update(campo="status.condicion", igual=cond)
            return {
                "tipo": "ciclo_de_vida",
                "titulo": f"{cond or 'La alarma'} se cierra con {it_c['tipo_evento']} {v}",
                "hipotesis": f"En {caso['caso']} el ticket de {cond} siguió abierto aunque a las "
                             f"{ev_c['ts'][11:16]} llegó {it_c['tipo_evento']} con status.{k} = {v} "
                             f"en el mismo dispositivo. v0 solo cierra con status = 0.",
                "aplica_a": {"origen": [caso["origen"]]},
                "regla": {"abre": abre,
                          "cierra_con": [{"tipo_evento": it_c["tipo_evento"], "sensor": it_c["sensor"],
                                          "campo": f"status.{k}", "igual": v}]},
            }

    # 4. Se esperaba un ticket y no hubo ninguno; el dispositivo dejó de latir -> silencio.
    if "falso_negativo" in s:
        fin = _t(caso["fin"])
        por_disp: dict[str, list[datetime]] = {}
        for ev, it in items:
            if it["tipo_evento"] == "Heartbeat":
                por_disp.setdefault(ev["dispositivo"], []).append(_t(ev["ts"]))
        for disp, ts in por_disp.items():
            if len(ts) < 2:
                continue
            intervalo = median((b - a).total_seconds() for a, b in zip(ts, ts[1:]))
            silencio = (fin - ts[-1]).total_seconds()
            if silencio > 3 * intervalo:
                return {
                    "tipo": "silencio",
                    "titulo": "El silencio de un dispositivo también es un incidente",
                    "hipotesis": f"En {caso['caso']} {disp} latía cada {intervalo:.0f} s y calló "
                                 f"{silencio:.0f} s sin que se abriera ningún ticket.",
                    "aplica_a": {"origen": ["*"]},
                    "regla": {"latido": {"tipo_evento": "Heartbeat"},
                              "max_silencio_s": int(3 * intervalo), "motivo": "SinLatido"},
                }
    return None
