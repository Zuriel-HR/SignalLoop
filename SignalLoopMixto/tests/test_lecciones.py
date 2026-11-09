"""pytest valida cada lección del proyecto real (activas y candidatas) contra el banco.

Es el paso «las pruebas validan» de la diapositiva 3:  pytest tests/test_lecciones.py
"""
import os
from pathlib import Path

import pytest

from aprendiz import motor
from aprendiz.archivos import Raiz, cargar_banco, cargar_lecciones, errores_esquema
from conftest import RAIZ

r = Raiz(os.environ.get("SIGNALLOOP_RAIZ") or RAIZ)
ACTIVAS = cargar_lecciones(r.activas)
CANDIDATAS = cargar_lecciones(r.candidatas)
CASOS = cargar_banco(r)
BANCO = {k: v for k, v in CASOS.items() if v["conjunto"] == "banco"}
TODAS = [("activa", l) for l in ACTIVAS] + [("candidata", l) for l in CANDIDATAS]


def _id(p):
    return f"{p[1]['codigo']}-{p[0]}"


pytestmark = pytest.mark.skipif(not TODAS, reason="todavía no hay lecciones activas ni candidatas")


@pytest.mark.parametrize("par", TODAS, ids=_id)
def test_esquema(par):
    assert errores_esquema("leccion", par[1]) == []


@pytest.mark.parametrize("par", TODAS, ids=_id)
def test_evidencia_apunta_a_casos_del_banco(par):
    casos = par[1]["evidencia"]["casos"]
    assert casos and all(c in BANCO for c in casos), "una lección no puede aprender de casos reservados"


@pytest.mark.parametrize("par", TODAS, ids=_id)
def test_no_rompe_casos_normales_ni_anomalos(par):
    estado, l = par
    base = [x for x in ACTIVAS if x["codigo"] != l["codigo"]]
    cmp = motor.comparar(motor.evaluar(BANCO, base), motor.evaluar(BANCO, base + [l]))
    assert cmp["rompe"] == []
    if estado == "candidata":
        assert set(l["evidencia"]["casos"]) & set(cmp["arregla"]), "la candidata no arregla su caso"
