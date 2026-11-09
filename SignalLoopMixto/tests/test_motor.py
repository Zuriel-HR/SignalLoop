"""Semántica del DSL. Si el intérprete real cambia de comportamiento, estas pruebas dicen cuál es la referencia."""
import pytest

from aprendiz import motor
from aprendiz.archivos import Raiz, cargar_banco
from conftest import RAIZ

CASOS = cargar_banco(Raiz(RAIZ))
BANCO = {k: v for k, v in CASOS.items() if v["conjunto"] == "banco"}


def L(codigo, tipo, regla, origen=("*",)):
    return {"codigo": codigo, "tipo": tipo, "aplica_a": {"origen": list(origen)}, "regla": regla}


DEDUP = L("L1", "deduplicacion", {"clave": ["event_id"], "ventana_s": 300})
CASHOUT = L("L2", "ciclo_de_vida", {
    "abre": {"tipo_evento": "Alarma", "sensor": "Dispensador", "campo": "status.condicion", "igual": "CashOut"},
    "cierra_con": [{"tipo_evento": "Operacion", "sensor": "Dispensador", "campo": "status.evento", "igual": "REPLENISH"}]},
    origen=("XFS",))
SILENCIO = L("L3", "silencio", {"latido": {"tipo_evento": "Heartbeat"}, "max_silencio_s": 90, "motivo": "SinLatido"})
FLAP = L("L4", "flapping", {"abre": {"tipo_evento": "Alarma"}, "cierra_con": [{"tipo_evento": "Recuperacion"}],
                            "reabre_en_s": 300})


def test_v0_resuelve_2_de_6():
    assert sum(motor.evaluar(BANCO, []).values()) == 2
    assert motor.evaluar(BANCO, []) == {"C1": False, "C2": False, "C3": True, "C4": False, "C5": False, "C6": True}


@pytest.mark.parametrize("leccion,caso", [(DEDUP, "C2"), (CASHOUT, "C1"), (SILENCIO, "C4"), (FLAP, "C5")])
def test_cada_leccion_arregla_su_caso_sin_romper_los_normales(leccion, caso):
    cmp = motor.comparar(motor.evaluar(BANCO, []), motor.evaluar(BANCO, [leccion]))
    assert caso in cmp["arregla"]
    assert cmp["rompe"] == []


def test_todas_juntas_resuelven_el_banco():
    assert all(motor.evaluar(BANCO, [DEDUP, CASHOUT, SILENCIO, FLAP]).values())


def test_cashout_registra_quien_cerro():
    r = motor.ejecutar(CASOS["C1"], [CASHOUT])
    assert r["tickets"][0]["cierre_por"] == "L2"
    assert r["tickets"][0]["cerrado_por_evento"].startswith("XFS-ATM001")


def test_cashout_aprendido_en_xfs_no_aplica_a_otro_origen():
    assert not motor.aplica(CASHOUT, {"origen": "SNMP"})


def test_flapping_reabre_el_mismo_ticket():
    r = motor.ejecutar(CASOS["C5"], [FLAP])
    assert r["tickets_creados"] == 1 and r["tickets"][0]["reaperturas"] == 2


def test_silencio_se_cierra_si_vuelve_el_latido():
    caso = {**CASOS["C4"], "eventos": CASOS["C4"]["eventos"] + [
        {**CASOS["C4"]["eventos"][0], "event_id": "tarde", "ts": "2026-11-09T14:09:30-06:00"}]}
    r = motor.ejecutar(caso, [SILENCIO])
    assert r["tickets_creados"] == 1 and r["tickets_abiertos"] == 0


def test_umbral():
    u = L("L9", "umbral", {"dispara": {"tipo_evento": "Status", "campo": "status.temperatura", "mayor_que": 3},
                           "motivo": "TemperaturaAlta"})
    assert motor.ejecutar(CASOS["C4"], [u])["tickets_creados"] == 1


@pytest.mark.parametrize("cond,ok", [
    ({"tipo_evento": "Operacion"}, True),
    ({"tipo_evento": ["Alarma", "Operacion"]}, True),
    ({"campo": "status.evento", "igual": "REPLENISH"}, True),
    ({"campo": "status.evento", "en": ["X", "REPLENISH"]}, True),
    ({"campo": "status.evento", "existe": False}, False),
    ({"campo": "status.no_hay", "existe": False}, True),
    ({"campo": "status.no_hay"}, False),
    ({"sensor": "Impresora"}, False),
])
def test_condiciones(cond, ok):
    item = {"tipo_evento": "Operacion", "sensor": "Dispensador", "status": {"evento": "REPLENISH"}}
    assert motor.cumple(cond, item) is ok
