"""Operaciones del Aprendiz. Cada una lee y escribe solo archivos del contrato."""
from __future__ import annotations

from . import motor
from .archivos import (Raiz, ahora, anexar_jsonl, cargar_banco, cargar_corridas, cargar_lecciones,
                       errores_esquema, escribir_json, leer_json, lotes, ruta_leccion,
                       siguiente_codigo, versiones)
from .proponer import proponer_plantilla
from .senales import (es_normal, lote_ablacion, lote_base, lote_candidata, por_caso, senales)


class Detenido(Exception):
    """Falta algo del otro lado del contrato. El mensaje dice qué hacer."""


def registrar(r: Raiz, accion: str, actor: str = "sistema", **detalle) -> None:
    anexar_jsonl(r.bitacora, {"ts": ahora(), "actor": actor, "componente": "aprendiz",
                              "accion": accion, **detalle})


def _partir(casos: dict) -> tuple[dict, dict]:
    banco = {k: v for k, v in casos.items() if v["conjunto"] == "banco"}
    reservados = {k: v for k, v in casos.items() if v["conjunto"] == "reservado"}
    return banco, reservados


def _activas(r: Raiz) -> list[dict]:
    return cargar_lecciones(r.activas)


def _codigos(ls: list[dict]) -> list[str]:
    return [l["codigo"] for l in ls]


# ---------------------------------------------------------------- validar (motor + pytest)

def validar_motor(r: Raiz, leccion: dict, casos: dict | None = None) -> dict:
    """Corre la lección en el motor de referencia sobre el banco. No mira los reservados para decidir."""
    casos = casos or cargar_banco(r)
    banco, reservados = _partir(casos)
    base = [l for l in _activas(r) if l["codigo"] != leccion["codigo"]]
    esquema = errores_esquema("leccion", leccion)
    if esquema:
        return {"ok": False, "esquema": esquema, "ts": ahora()}
    try:
        cmp_b = motor.comparar(motor.evaluar(banco, base), motor.evaluar(banco, base + [leccion]))
        cmp_r = motor.comparar(motor.evaluar(reservados, base), motor.evaluar(reservados, base + [leccion]))
    except Exception as e:  # una regla mal formada no debe tumbar el ciclo
        return {"ok": False, "esquema": [], "error": f"{type(e).__name__}: {e}", "ts": ahora()}
    objetivo = set(leccion["evidencia"]["casos"])
    ok = bool(objetivo & set(cmp_b["arregla"])) and not cmp_b["rompe"]
    return {"ok": ok, "esquema": [], "banco": cmp_b, "reservados": cmp_r, "ts": ahora()}


# ---------------------------------------------------------------- proponer

def _armar(r: Raiz, parcial: dict, corrida: dict, lote: str, sen: list[str], propuesta_por: str) -> dict:
    return {
        "codigo": siguiente_codigo(r),
        "titulo": parcial.get("titulo", ""),
        "hipotesis": parcial.get("hipotesis", ""),
        "tipo": parcial.get("tipo"),
        "aplica_a": parcial.get("aplica_a", {"origen": ["*"]}),
        "regla": parcial.get("regla", {}),
        "evidencia": {"lote": lote, "run_ids": [corrida["run_id"]], "casos": [corrida["caso"]], "senales": sen},
        "estado": "candidata",
        "creada_por": "sistema",
        "propuesta_por": propuesta_por,
        "version_origen": versiones(r)["actual"],
        "version_destino": None,
        "creada_en": ahora(),
    }


def _guardar_propuesta(r: Raiz, leccion: dict, casos: dict) -> dict:
    v = validar_motor(r, leccion, casos)
    leccion["validacion"] = {"motor": v}
    if v["ok"]:
        escribir_json(r.candidatas / f"{leccion['codigo']}.json", leccion)
        registrar(r, "proponer", codigo=leccion["codigo"], caso=leccion["evidencia"]["casos"][0],
                  tipo=leccion["tipo"], propuesta_por=leccion["propuesta_por"], arregla=v["banco"]["arregla"])
    else:
        leccion["estado"] = "rechazada"
        leccion["motivo_rechazo"] = ("no pasa el esquema: " + "; ".join(v["esquema"]) if v.get("esquema")
                                     else v.get("error") or
                                     f"motor: arregla {v['banco']['arregla']} y rompe {v['banco']['rompe']}")
        escribir_json(r.rechazadas / f"{leccion['codigo']}.json", leccion)
        registrar(r, "rechazar_propuesta", codigo=leccion["codigo"], motivo=leccion["motivo_rechazo"],
                  propuesta_por=leccion["propuesta_por"])
    return leccion


def proponer(r: Raiz, caso: str | None = None, usar_llm: bool = False) -> list[dict]:
    """Propone una lección para un caso fallido del banco. Devuelve las propuestas escritas
    (puede haber una rechazada del LLM seguida de la de plantilla)."""
    r.asegurar()
    casos = cargar_banco(r)
    banco, _ = _partir(casos)
    activas = _activas(r)
    nombre, filas = lote_base(lotes(cargar_corridas(r)), _codigos(activas))
    if not nombre:
        raise Detenido(f"No hay un lote normal corrido con las lecciones activas {_codigos(activas) or '[]'}. "
                       f"Pide al intérprete correr el banco con versión {versiones(r)['actual']}.")
    fallas = [f for f in filas if not f["pasa"] and f["caso"] in banco]
    if caso:
        if caso in casos and casos[caso]["conjunto"] == "reservado":
            raise Detenido(f"{caso} es reservado: no se usa para aprender.")
        fallas = [f for f in fallas if f["caso"] == caso]
        if not fallas:
            raise Detenido(f"{caso} no falla en el lote {nombre}.")
    else:
        pendientes = activas + cargar_lecciones(r.candidatas)
        cubiertos = motor.evaluar(banco, pendientes)
        fallas = [f for f in fallas if not cubiertos.get(f["caso"])]
    if not fallas:
        return []

    corrida = fallas[0]
    caso_d = casos[corrida["caso"]]
    sen = senales(corrida)
    escritas = []

    if usar_llm:
        from .llm import MODELO, proponer_llm
        try:
            parcial = proponer_llm(caso_d, corrida, sen, activas)
            l = _guardar_propuesta(r, _armar(r, parcial, corrida, nombre, sen, f"llm:{MODELO}"), casos)
            escritas.append(l)
            if l["estado"] == "candidata":
                return escritas
        except Exception as e:
            registrar(r, "llm_fallo", caso=corrida["caso"], error=f"{type(e).__name__}: {e}")

    parcial = proponer_plantilla(caso_d, corrida, sen)
    if parcial is None:
        registrar(r, "sin_plantilla", caso=corrida["caso"], senales=sen)
        if not escritas:
            raise Detenido(f"No hay plantilla para {corrida['caso']} con señales {sen}. "
                           f"Prueba --llm o que el equipo escriba la lección a mano.")
        return escritas
    escritas.append(_guardar_propuesta(r, _armar(r, parcial, corrida, nombre, sen, "plantilla"), casos))
    return escritas


# ---------------------------------------------------------------- compuerta

def _pasa(filas: list[dict], ids) -> dict[str, bool]:
    m = por_caso(filas)
    return {c: m[c]["pasa"] for c in ids if c in m}


def _siguiente_version(actual: str) -> str:
    return f"v{int(actual.lstrip('v')) + 1}" if actual.lstrip("v").isdigit() else f"{actual}+1"


def evaluar(r: Raiz, codigo: str) -> dict:
    """Compuerta: acepta la candidata solo si el intérprete mejora el banco sin regresiones."""
    p = r.candidatas / f"{codigo}.json"
    if not p.exists():
        raise Detenido(f"{codigo} no está en lecciones/candidatas/.")
    leccion = leer_json(p)
    casos = cargar_banco(r)
    banco, reservados = _partir(casos)
    activas = _activas(r)
    act = _codigos(activas)
    lts = lotes(cargar_corridas(r))
    n_base, f_base = lote_base(lts, act)
    n_cand, f_cand = lote_candidata(lts, act, codigo)
    if not n_base:
        raise Detenido(f"Falta un lote normal con las activas {act or '[]'}.")
    if not n_cand:
        raise Detenido(f"Falta el lote de prueba: pide al intérprete correr el banco con las activas "
                       f"{act or '[]'} más la candidata lecciones/candidatas/{codigo}.json (candidata = \"{codigo}\").")

    cmp_b = motor.comparar(_pasa(f_base, banco), _pasa(f_cand, banco))
    cmp_r = motor.comparar(_pasa(f_base, reservados), _pasa(f_cand, reservados))
    faltan = sorted(set(banco) - set(por_caso(f_cand)))
    motor_ok = leccion.get("validacion", {}).get("motor", {}).get("ok", False)
    discrep = _discrepancias(casos, activas + [leccion], f_cand)

    motivos = []
    if faltan:
        motivos.append(f"el lote {n_cand} no trae los casos {faltan}")
    if not cmp_b["arregla"]:
        motivos.append("no arregla ningún caso del banco")
    if cmp_b["rompe"]:
        motivos.append(f"rompe {cmp_b['rompe']}")
    if not motor_ok:
        motivos.append("no pasó la validación del motor de referencia")
    acepta = not motivos

    leccion.setdefault("validacion", {})["interprete"] = {
        "lote_base": n_base, "lote_candidata": n_cand, "banco": cmp_b, "reservados": cmp_r,
        "discrepancias_motor": discrep, "ts": ahora()}
    p.unlink()
    if acepta:
        v = versiones(r)
        nueva = _siguiente_version(v["actual"])
        v["historial"].append({"version": nueva, "lecciones": act + [codigo], "padre": v["actual"],
                               "creado_por": "sistema", "leccion": codigo, "ts": ahora()})
        v["actual"] = nueva
        leccion.update(estado="activa", version_destino=nueva)
        escribir_json(r.activas / f"{codigo}.json", leccion)
        escribir_json(r.versiones, v)
        registrar(r, "aceptar", codigo=codigo, version=nueva, arregla=cmp_b["arregla"],
                  banco=f"{cmp_b['antes']}→{cmp_b['despues']}/{cmp_b['total']}",
                  reservados=f"{cmp_r['antes']}→{cmp_r['despues']}/{cmp_r['total']}")
    else:
        leccion.update(estado="rechazada", motivo_rechazo="; ".join(motivos))
        escribir_json(r.rechazadas / f"{codigo}.json", leccion)
        registrar(r, "rechazar", codigo=codigo, motivo=leccion["motivo_rechazo"])
    return {"acepta": acepta, "motivos": motivos, "leccion": leccion, "banco": cmp_b,
            "reservados": cmp_r, "discrepancias": discrep, "version": versiones(r)["actual"]}


def rechazar(r: Raiz, codigo: str, motivo: str, actor: str = "equipo") -> dict:
    p = ruta_leccion(r, codigo)
    if not p or p.parent == r.rechazadas:
        raise Detenido(f"{codigo} no está en candidatas ni en activas.")
    l = leer_json(p)
    era_activa = p.parent == r.activas
    l.update(estado="rechazada", motivo_rechazo=f"{actor}: {motivo}")
    p.unlink()
    escribir_json(r.rechazadas / f"{codigo}.json", l)
    if era_activa:
        v = versiones(r)
        nueva = _siguiente_version(v["actual"])
        v["historial"].append({"version": nueva, "lecciones": _codigos(_activas(r)), "padre": v["actual"],
                               "creado_por": actor, "retira": codigo, "ts": ahora()})
        v["actual"] = nueva
        escribir_json(r.versiones, v)
    registrar(r, "retirar" if era_activa else "rechazar", actor=actor, codigo=codigo, motivo=motivo)
    return l


# ---------------------------------------------------------------- ablación y verificación

def ablacion(r: Raiz, codigo: str) -> dict:
    activas = _activas(r)
    act = _codigos(activas)
    if codigo not in act:
        raise Detenido(f"{codigo} no está activa.")
    casos = cargar_banco(r)
    lts = lotes(cargar_corridas(r))
    n_base, f_base = lote_base(lts, act)
    n_abl, f_abl = lote_ablacion(lts, codigo)
    sin_l = [l for l in activas if l["codigo"] != codigo]
    pred = motor.comparar(motor.evaluar(casos, activas), motor.evaluar(casos, sin_l))
    out = {"codigo": codigo, "motor": pred, "lote_base": n_base, "lote_ablacion": n_abl}
    if n_base and n_abl:
        out["interprete"] = motor.comparar(_pasa(f_base, casos), _pasa(f_abl, casos))
        registrar(r, "ablacion", codigo=codigo, vuelven_a_fallar=out["interprete"]["rompe"])
    return out


def _discrepancias(casos: dict, lecciones: list[dict], filas: list[dict]) -> list[dict]:
    out = []
    for f in filas:
        if f["caso"] not in casos:
            continue
        m = motor.ejecutar(casos[f["caso"]], lecciones)
        o = f["obtenido"]
        if (m["tickets_creados"], m["tickets_abiertos"]) != (o["tickets_creados"], o["tickets_abiertos"]):
            out.append({"caso": f["caso"], "interprete": [o["tickets_creados"], o["tickets_abiertos"]],
                        "motor": [m["tickets_creados"], m["tickets_abiertos"]]})
    return out


def verificar(r: Raiz, lote: str | None = None) -> dict:
    """¿El intérprete aplica las reglas igual que el motor de referencia? Útil al integrar."""
    lts = lotes(cargar_corridas(r))
    if not lts:
        raise Detenido("resultados/corridas.jsonl está vacío.")
    nombre = lote or list(lts)[-1]
    filas = lts[nombre]
    c = filas[0]
    codigos = [x for x in c["lecciones_activas"] if x not in (c.get("sin") or [])]
    if c.get("candidata"):
        codigos.append(c["candidata"])
    lecciones, faltan = [], []
    for cod in codigos:
        p = ruta_leccion(r, cod)
        (lecciones.append(leer_json(p)) if p else faltan.append(cod))
    return {"lote": nombre, "lecciones": codigos, "faltan": faltan,
            "discrepancias": _discrepancias(cargar_banco(r), lecciones, filas)}


# ---------------------------------------------------------------- reporte y estado

def reporte(r: Raiz, guardar: bool = True) -> dict:
    casos = cargar_banco(r)
    banco, reservados = _partir(casos)
    filas_rep = []
    for nombre, filas in lotes(cargar_corridas(r)).items():
        c = filas[0]
        pc = {f["caso"]: f["pasa"] for f in filas}
        filas_rep.append({
            "lote": nombre, "version": c["version"], "lecciones": c["lecciones_activas"],
            "candidata": c.get("candidata"), "sin": c.get("sin") or [],
            "banco": [sum(pc.get(k, False) for k in banco), len(banco)],
            "reservados": [sum(pc.get(k, False) for k in reservados), len(reservados)],
            "casos": {k: pc.get(k) for k in casos},
        })
    rep = {"generado": ahora(), "version_actual": versiones(r)["actual"],
           "casos": {k: {"nombre": v["nombre"], "conjunto": v["conjunto"], "origen": v["origen"]}
                     for k, v in casos.items()},
           "lotes": filas_rep, "versiones": versiones(r)["historial"],
           "lecciones": {"activas": cargar_lecciones(r.activas), "candidatas": cargar_lecciones(r.candidatas),
                         "rechazadas": cargar_lecciones(r.rechazadas)}}
    if guardar:
        escribir_json(r.resultados / "reporte.json", rep)
    return rep


def estado(r: Raiz) -> dict:
    r.asegurar()
    activas, cand = _activas(r), cargar_lecciones(r.candidatas)
    lts = lotes(cargar_corridas(r))
    n_base, f_base = lote_base(lts, _codigos(activas))
    if not lts or not n_base:
        siguiente = f"intérprete: correr el banco con versión {versiones(r)['actual']} y activas {_codigos(activas)}"
    elif cand:
        n_c, _ = lote_candidata(lts, _codigos(activas), cand[0]["codigo"])
        siguiente = (f"aprendiz: evaluar {cand[0]['codigo']}" if n_c else
                     f"intérprete: correr el banco con la candidata {cand[0]['codigo']}")
    elif any(not f["pasa"] for f in f_base if cargar_banco(r).get(f["caso"], {}).get("conjunto") == "banco"):
        siguiente = "aprendiz: proponer"
    else:
        siguiente = "banco completo: correr ablación o los reservados"
    return {"version": versiones(r)["actual"], "activas": activas, "candidatas": cand,
            "rechazadas": cargar_lecciones(r.rechazadas), "lote_base": n_base,
            "lotes": len(lts), "siguiente": siguiente,
            "ultimo_lote_normal": next((n for n in reversed(list(lts)) if es_normal(lts[n])), None)}
