"""python -m aprendiz <comando>"""
from __future__ import annotations

import argparse
import json
import sys

from . import ciclo
from .archivos import Raiz, cargar_banco, cargar_lecciones, leer_json, ruta_leccion

OK, NO = "✔", "✘"


def _marca(v):
    return "·" if v is None else (OK if v else NO)


def _cmp(c):
    return f"{c['antes']} → {c['despues']} de {c['total']}"


def cmd_estado(r, a):
    e = ciclo.estado(r)
    print(f"Versión actual: {e['version']}")
    activas = ", ".join(f"{l['codigo']} ({l['tipo']})" for l in e["activas"])
    print(f"Activas:    {activas or '—'}")
    print(f"Candidatas: {', '.join(l['codigo'] for l in e['candidatas']) or '—'}")
    print(f"Rechazadas: {len(e['rechazadas'])}")
    print(f"Lotes en corridas.jsonl: {e['lotes']} · lote base: {e['lote_base'] or '—'}")
    print(f"Siguiente: {e['siguiente']}")


def cmd_proponer(r, a):
    escritas = ciclo.proponer(r, caso=a.caso, usar_llm=a.llm)
    if not escritas:
        print("Todos los casos del banco que fallan ya están cubiertos por una lección activa o candidata.")
        return
    for l in escritas:
        v = l["validacion"]["motor"]
        print(f"{l['codigo']} · {l['tipo']} · {l['propuesta_por']} · {l['estado'].upper()}")
        print(f"  {l['titulo']}")
        print(f"  Evidencia: caso {l['evidencia']['casos'][0]}, corrida {l['evidencia']['run_ids'][0]}, "
              f"señales {l['evidencia']['senales']}")
        if l.get("hipotesis"):
            print(f"  Hipótesis: {l['hipotesis']}")
        if "banco" in v:
            print(f"  Motor de referencia, banco: {_cmp(v['banco'])} · arregla {v['banco']['arregla']}"
                  f" · rompe {v['banco']['rompe'] or 'nada'}")
        if l["estado"] == "rechazada":
            print(f"  Motivo: {l['motivo_rechazo']}")
    final = escritas[-1]
    if final["estado"] == "candidata":
        print(f"\nSiguiente: el intérprete corre el banco con lecciones/candidatas/{final['codigo']}.json "
              f"(candidata = \"{final['codigo']}\") y luego: python -m aprendiz evaluar {final['codigo']}")


def cmd_validar(r, a):
    casos = cargar_banco(r)
    codigos = [a.codigo] if a.codigo else [l["codigo"] for d in (r.activas, r.candidatas)
                                           for l in cargar_lecciones(d)]
    fallo = False
    for cod in codigos:
        p = ruta_leccion(r, cod)
        if not p:
            print(f"{cod}: no existe"); fallo = True; continue
        v = ciclo.validar_motor(r, leer_json(p), casos)
        aviso = ""
        if p.parent == r.activas and "banco" in v and not v["banco"]["rompe"]:
            # Una activa ya probó su valor en la compuerta; aquí solo importa que no rompa nada.
            if not v["banco"]["arregla"]:
                aviso = " · redundante hoy: otra lección activa ya cubre su caso"
            v["ok"] = True
        fallo |= not v["ok"]
        detalle = (f"banco {_cmp(v['banco'])}, arregla {v['banco']['arregla']}, rompe {v['banco']['rompe'] or 'nada'}"
                   if "banco" in v else "; ".join(v.get("esquema") or [v.get("error", "")]))
        print(f"{_marca(v['ok'])} {cod} ({p.parent.name}): {detalle}{aviso}")
    sys.exit(1 if fallo else 0)


def cmd_evaluar(r, a):
    res = ciclo.evaluar(r, a.codigo)
    l = res["leccion"]
    print(f"{OK if res['acepta'] else NO} {a.codigo} {'ACEPTADA' if res['acepta'] else 'RECHAZADA'}")
    print(f"  Banco (intérprete): {_cmp(res['banco'])} · arregla {res['banco']['arregla']} "
          f"· rompe {res['banco']['rompe'] or 'nada'}")
    print(f"  Reservados (no se usaron para aprender): {_cmp(res['reservados'])}")
    if res["acepta"]:
        print(f"  Nueva versión: {res['version']} = activas {leer_json(r.versiones)['historial'][-1]['lecciones']}")
        print(f"\nSiguiente: el intérprete recarga lecciones/activas/ y corre el banco como {res['version']}.")
    else:
        print(f"  Motivo: {l['motivo_rechazo']}")
    for d in res["discrepancias"]:
        print(f"  ⚠ {d['caso']}: intérprete {d['interprete']} vs motor {d['motor']} (creados, abiertos)")


def cmd_rechazar(r, a):
    l = ciclo.rechazar(r, a.codigo, a.motivo, actor="equipo")
    print(f"{a.codigo} rechazada por el equipo: {l['motivo_rechazo']}")


def cmd_ablacion(r, a):
    res = ciclo.ablacion(r, a.codigo)
    print(f"Ablación de {a.codigo}")
    print(f"  Motor: sin {a.codigo} vuelven a fallar {res['motor']['rompe'] or 'ninguno'}")
    if "interprete" in res:
        print(f"  Intérprete ({res['lote_ablacion']}): vuelven a fallar {res['interprete']['rompe'] or 'ninguno'}")
    else:
        print(f"  Falta el lote del intérprete con sin = [\"{a.codigo}\"].")


def cmd_verificar(r, a):
    res = ciclo.verificar(r, a.lote)
    print(f"Lote {res['lote']} · lecciones {res['lecciones'] or '[]'}")
    if res["faltan"]:
        print(f"  ⚠ El lote cita lecciones que no existen aquí: {res['faltan']}")
    if not res["discrepancias"]:
        print(f"  {OK} El intérprete coincide con el motor de referencia en todos los casos.")
    for d in res["discrepancias"]:
        print(f"  {NO} {d['caso']}: intérprete {d['interprete']} vs motor {d['motor']} (creados, abiertos)")
    sys.exit(1 if res["discrepancias"] or res["faltan"] else 0)


def cmd_reporte(r, a):
    rep = ciclo.reporte(r)
    if a.json:
        print(json.dumps(rep, ensure_ascii=False, indent=2)); return
    ids = list(rep["casos"])
    print("lote".ljust(30), "versión", "lecciones".ljust(16), "banco", "reserv.", " ".join(i.ljust(2) for i in ids))
    for f in rep["lotes"]:
        etiqueta = f["lecciones"] + ([f"+{f['candidata']}"] if f["candidata"] else []) + [f"-{x}" for x in f["sin"]]
        print(f["lote"][:30].ljust(30), f["version"].ljust(7), ",".join(etiqueta)[:16].ljust(16),
              f"{f['banco'][0]}/{f['banco'][1]}".ljust(5), f"{f['reservados'][0]}/{f['reservados'][1]}".ljust(7),
              " ".join(_marca(f["casos"][i]).ljust(2) for i in ids))
    print(f"\nGuardado en resultados/reporte.json · versión actual {rep['version_actual']}")


def main(argv=None):
    p = argparse.ArgumentParser(prog="aprendiz", description="Aprendiz de SignalLoop (modo Mixto)")
    p.add_argument("--raiz", help="carpeta del proyecto (por defecto: actual o SIGNALLOOP_RAIZ)")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("estado", help="versión, lecciones y siguiente paso").set_defaults(f=cmd_estado)
    s = sub.add_parser("proponer", help="propone una lección para el primer caso fallido")
    s.add_argument("--caso"); s.add_argument("--llm", action="store_true", help="usa OpenAI y cae a plantilla si falla")
    s.set_defaults(f=cmd_proponer)
    s = sub.add_parser("validar", help="valida lecciones con el motor de referencia")
    s.add_argument("codigo", nargs="?"); s.set_defaults(f=cmd_validar)
    s = sub.add_parser("evaluar", help="compuerta: compara el lote base con el lote de la candidata")
    s.add_argument("codigo"); s.set_defaults(f=cmd_evaluar)
    s = sub.add_parser("rechazar", help="el equipo rechaza o retira una lección")
    s.add_argument("codigo"); s.add_argument("--motivo", required=True); s.set_defaults(f=cmd_rechazar)
    s = sub.add_parser("ablacion", help="qué casos vuelven a fallar sin una lección")
    s.add_argument("codigo"); s.set_defaults(f=cmd_ablacion)
    s = sub.add_parser("verificar", help="compara un lote del intérprete con el motor de referencia")
    s.add_argument("--lote"); s.set_defaults(f=cmd_verificar)
    s = sub.add_parser("reporte", help="tabla de lotes y resultados/reporte.json para el panel")
    s.add_argument("--json", action="store_true"); s.set_defaults(f=cmd_reporte)

    a = p.parse_args(argv)
    r = Raiz(a.raiz)
    r.asegurar()
    try:
        a.f(r, a)
    except ciclo.Detenido as e:
        print(f"Detenido: {e}", file=sys.stderr)
        sys.exit(2)
    except ValueError as e:
        print(f"Error en un archivo del contrato: {e}", file=sys.stderr)
        sys.exit(3)
