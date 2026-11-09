"""Motor de referencia del DSL de lecciones.

No es el intérprete. Es la semántica ejecutable de las reglas: pytest lo usa para validar
una lección antes de pedir al intérprete real que la pruebe, y CONTRATO.md lo cita como
la definición de qué significa cada regla. Si el intérprete y este motor no coinciden en
un caso, uno de los dos tiene un error.

Reglas base (v0), sin lecciones:
  1. Cada elemento Alarma abre un ticket nuevo (dispositivo, sensor, motivo = status.condicion).
  2. Un elemento Status con status.status == 0 cierra los tickets abiertos del mismo
     dispositivo y sensor.
  3. Ningún otro evento abre ni cierra tickets.
"""
from __future__ import annotations

from datetime import datetime, timedelta

_FALTA = object()


def _t(ts: str) -> datetime:
    return datetime.fromisoformat(ts)


def valor_en(obj, ruta: str):
    """Lee 'status.evento' o 'grupo.0.sensor'. Devuelve _FALTA si no existe."""
    cur = obj
    for parte in ruta.split("."):
        if isinstance(cur, dict) and parte in cur:
            cur = cur[parte]
        elif isinstance(cur, list) and parte.isdigit() and int(parte) < len(cur):
            cur = cur[int(parte)]
        else:
            return _FALTA
    return cur


def _coincide(esperado, real) -> bool:
    if esperado in (None, "*"):
        return True
    if isinstance(esperado, list):
        return "*" in esperado or real in esperado
    return real == esperado


def cumple(cond: dict, item: dict) -> bool:
    """¿El elemento del grupo cumple todas las claves de la condición?"""
    if not _coincide(cond.get("tipo_evento"), item.get("tipo_evento")):
        return False
    if not _coincide(cond.get("sensor"), item.get("sensor")):
        return False
    if "campo" not in cond:
        return True
    v = valor_en(item, cond["campo"])
    if "existe" in cond and (v is not _FALTA) != cond["existe"]:
        return False
    hay_comparador = any(k in cond for k in ("igual", "en", "mayor_que", "menor_que", "existe"))
    if not hay_comparador:
        return v is not _FALTA
    if v is _FALTA:
        return "existe" in cond and cond["existe"] is False
    if "igual" in cond and v != cond["igual"]:
        return False
    if "en" in cond and v not in cond["en"]:
        return False
    try:
        if "mayor_que" in cond and not v > cond["mayor_que"]:
            return False
        if "menor_que" in cond and not v < cond["menor_que"]:
            return False
    except TypeError:
        return False
    return True


def aplica(leccion: dict, evento: dict) -> bool:
    origenes = leccion.get("aplica_a", {}).get("origen", ["*"])
    return "*" in origenes or evento.get("origen") in origenes


def ejecutar(caso: dict, lecciones: list[dict] | None = None) -> dict:
    """Corre un caso del banco con las reglas base más las lecciones dadas."""
    lecciones = list(lecciones or [])
    por_tipo = lambda t: [l for l in lecciones if l["tipo"] == t]
    dedup, ciclos, flaps = por_tipo("deduplicacion"), por_tipo("ciclo_de_vida"), por_tipo("flapping")
    silencios, umbrales = por_tipo("silencio"), por_tipo("umbral")

    tickets: list[dict] = []
    vistos: dict[tuple, datetime] = {}
    latidos: dict[tuple, tuple] = {}  # (codigo, dispositivo) -> (ts, sensor)

    def abiertos(**filtro):
        return [tk for tk in tickets if tk["estado"] == "abierto"
                and all(tk[k] == v for k, v in filtro.items())]

    def abrir(ev, item, motivo, t, por=None):
        tk = {"id": f"T{len(tickets) + 1}", "dispositivo": ev["dispositivo"], "sensor": item["sensor"],
              "motivo": motivo, "estado": "abierto", "abierto_en": t.isoformat(), "cerrado_en": None,
              "abierto_por_evento": ev.get("event_id"), "cerrado_por_evento": None,
              "cierre_por": None, "reaperturas": 0, "repeticiones": 0,
              "_item": item, "_cerrado_dt": None, "_por": por}
        tickets.append(tk)
        return tk

    def cerrar(tk, t, ev, por):
        tk.update(estado="cerrado", cerrado_en=t.isoformat(), cerrado_por_evento=ev.get("event_id") if ev else None,
                  cierre_por=por, _cerrado_dt=t)

    def revisar_silencio(t):
        for (codigo, disp), (t_lat, sensor) in latidos.items():
            l = next(x for x in silencios if x["codigo"] == codigo)
            limite = t_lat + timedelta(seconds=l["regla"]["max_silencio_s"])
            if t > limite and not [tk for tk in abiertos(dispositivo=disp) if tk["_por"] == codigo]:
                abrir({"dispositivo": disp, "event_id": None}, {"sensor": sensor},
                      l["regla"]["motivo"], limite, por=codigo)

    eventos = sorted(caso["eventos"], key=lambda e: _t(e["ts"]))
    for ev in eventos:
        t = _t(ev["ts"])
        revisar_silencio(t)

        # Deduplicación: descarta el evento completo si su clave ya se vio dentro de la ventana.
        repetido = False
        for l in dedup:
            if not aplica(l, ev):
                continue
            clave = (l["codigo"],) + tuple(str(valor_en(ev, k)) for k in l["regla"]["clave"])
            previo = vistos.get(clave)
            if previo is not None and (t - previo).total_seconds() <= l["regla"]["ventana_s"]:
                repetido = True
            else:
                vistos[clave] = t
        if repetido:
            continue

        for item in ev.get("grupo", []):
            tipo, sensor, disp = item.get("tipo_evento"), item.get("sensor"), ev["dispositivo"]
            status = item.get("status") or {}

            # Latidos: alimentan las reglas de silencio y cierran su ticket al reanudarse.
            for l in silencios:
                if aplica(l, ev) and cumple(l["regla"]["latido"], item):
                    latidos[(l["codigo"], disp)] = (t, sensor)
                    for tk in abiertos(dispositivo=disp):
                        if tk["_por"] == l["codigo"]:
                            cerrar(tk, t, ev, f"{l['codigo']}: latido reanudado")

            if tipo == "Alarma":
                motivo = str(status.get("condicion", "Alarma"))
                lf = next((l for l in flaps if aplica(l, ev) and cumple(l["regla"]["abre"], item)), None)
                if lf:
                    mismo = abiertos(dispositivo=disp, sensor=sensor, motivo=motivo)
                    if mismo:
                        mismo[0]["repeticiones"] += 1
                        continue
                    ventana = timedelta(seconds=lf["regla"]["reabre_en_s"])
                    recientes = [tk for tk in tickets if tk["estado"] == "cerrado"
                                 and (tk["dispositivo"], tk["sensor"], tk["motivo"]) == (disp, sensor, motivo)
                                 and tk["_cerrado_dt"] and t - tk["_cerrado_dt"] <= ventana]
                    if recientes:
                        tk = recientes[-1]
                        tk.update(estado="abierto", cerrado_en=None, cerrado_por_evento=None,
                                  cierre_por=None, _cerrado_dt=None)
                        tk["reaperturas"] += 1
                        continue
                abrir(ev, item, motivo, t)

            for l in umbrales:
                if aplica(l, ev) and cumple(l["regla"]["dispara"], item):
                    if not abiertos(dispositivo=disp, sensor=sensor, motivo=l["regla"]["motivo"]):
                        abrir(ev, item, l["regla"]["motivo"], t, por=l["codigo"])

            # Cierre base de v0.
            if tipo == "Status" and status.get("status") == 0:
                for tk in abiertos(dispositivo=disp, sensor=sensor):
                    cerrar(tk, t, ev, "status=0")

            # Cierres aprendidos.
            for l in ciclos + flaps:
                if not aplica(l, ev) or not any(cumple(c, item) for c in l["regla"]["cierra_con"]):
                    continue
                for tk in abiertos(dispositivo=disp):
                    if l["tipo"] == "flapping" and tk["sensor"] != sensor:
                        continue
                    if tk["_por"] is None and cumple(l["regla"]["abre"], tk["_item"]):
                        cerrar(tk, t, ev, l["codigo"])

    revisar_silencio(_t(caso["fin"]))

    salida = [{k: v for k, v in tk.items() if not k.startswith("_")} for tk in tickets]
    return {
        "tickets_creados": len(salida),
        "tickets_abiertos": sum(tk["estado"] == "abierto" for tk in salida),
        "tickets": salida,
    }


def pasa(caso: dict, obtenido: dict) -> bool:
    e = caso["esperado"]
    return (obtenido["tickets_creados"] == e["tickets_creados"]
            and obtenido["tickets_abiertos"] == e["tickets_abiertos"])


def evaluar(casos: dict[str, dict], lecciones: list[dict]) -> dict[str, bool]:
    return {cid: pasa(c, ejecutar(c, lecciones)) for cid, c in casos.items()}


def comparar(antes: dict[str, bool], despues: dict[str, bool]) -> dict:
    comunes = sorted(set(antes) & set(despues))
    return {
        "arregla": [c for c in comunes if not antes[c] and despues[c]],
        "rompe": [c for c in comunes if antes[c] and not despues[c]],
        "antes": sum(antes[c] for c in comunes),
        "despues": sum(despues[c] for c in comunes),
        "total": len(comunes),
    }
