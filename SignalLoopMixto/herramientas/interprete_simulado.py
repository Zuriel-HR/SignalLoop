"""Intérprete SIMULADO: solo para probar el contrato mientras el intérprete real se conecta.

Usa el motor de referencia y escribe resultados/corridas.jsonl con el mismo formato que debe
escribir el intérprete real. En la demo no lo uses: las corridas deben venir del intérprete.

  python herramientas/interprete_simulado.py                 # lote normal con las activas
  python herramientas/interprete_simulado.py --candidata L1  # activas + candidata
  python herramientas/interprete_simulado.py --sin L1        # ablación
"""
from __future__ import annotations

import argparse
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from aprendiz import motor  # noqa: E402
from aprendiz.archivos import (Raiz, ahora, anexar_jsonl, cargar_banco, cargar_lecciones,  # noqa: E402
                               leer_json, versiones)
from aprendiz.senales import senales  # noqa: E402


def correr(r: Raiz, candidata: str | None = None, sin: list[str] | None = None) -> str:
    r.asegurar()
    sin = sin or []
    activas = cargar_lecciones(r.activas)
    usadas = [l for l in activas if l["codigo"] not in sin]
    if candidata:
        usadas.append(leer_json(r.candidatas / f"{candidata}.json"))
    version = versiones(r)["actual"]
    lote = f"{version}-{datetime.now().strftime('%Y%m%dT%H%M%S%f')}"
    for cid, caso in cargar_banco(r).items():
        res = motor.ejecutar(caso, usadas)
        obtenido = {"tickets_creados": res["tickets_creados"], "tickets_abiertos": res["tickets_abiertos"]}
        fila = {
            "run_id": f"{lote}-{cid}", "lote": lote, "version": version, "caso": cid,
            "conjunto": caso["conjunto"],
            "lecciones_activas": [l["codigo"] for l in activas],
            "candidata": candidata, "sin": sin,
            "esperado": caso["esperado"], "obtenido": obtenido,
            "pasa": motor.pasa(caso, res), "tickets": res["tickets"],
            "ts": ahora(), "actor": "interprete_simulado",
        }
        fila["senales"] = senales(fila)
        anexar_jsonl(r.corridas, fila)
    return lote


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--raiz")
    p.add_argument("--candidata")
    p.add_argument("--sin", nargs="*", default=[])
    a = p.parse_args()
    print(correr(Raiz(a.raiz), a.candidata, a.sin))
