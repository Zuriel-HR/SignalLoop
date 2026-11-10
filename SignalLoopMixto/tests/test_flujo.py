"""Ciclo completo con el intérprete simulado: el contrato de archivos funciona de punta a punta."""
import json

import pytest

from aprendiz import ciclo
from aprendiz.archivos import escribir_json, leer_json
from interprete_simulado import correr


def _una_ronda(r):
    propuestas = ciclo.proponer(r)
    if not propuestas:
        return None
    l = propuestas[-1]
    assert l["estado"] == "candidata"
    correr(r, candidata=l["codigo"])
    res = ciclo.evaluar(r, l["codigo"])
    correr(r)
    return res


def test_de_v0_a_banco_completo(proyecto):
    r = proyecto
    with pytest.raises(ciclo.Detenido):
        ciclo.proponer(r)  # sin corridas no hay nada que aprender
    correr(r)
    aceptadas = []
    while (res := _una_ronda(r)) is not None:
        assert res["acepta"], res["motivos"]
        assert res["discrepancias"] == []
        aceptadas.append(res["leccion"]["codigo"])
    assert aceptadas == ["L1", "L2", "L3", "L4"]
    v = leer_json(r.versiones)
    assert v["actual"] == "v4"
    assert [h["version"] for h in v["historial"]] == ["v0", "v1", "v2", "v3", "v4"]
    rep = ciclo.reporte(r)
    assert rep["lotes"][0]["banco"] == [2, 6] and rep["lotes"][-1]["banco"] == [6, 6]
    # La lección de flapping aprendida en SNMP se transfiere a la caldera IoT reservada.
    assert rep["lotes"][-1]["casos"]["R2"] is True
    # Cada acción del sistema quedó en la bitácora.
    acciones = [json.loads(x)["accion"] for x in r.bitacora.read_text().splitlines()]
    assert acciones.count("aceptar") == 4


def test_ablacion_hace_fallar_el_caso(proyecto):
    r = proyecto
    correr(r)
    _una_ronda(r)  # L1 cash out
    correr(r, sin=["L1"])
    res = ciclo.ablacion(r, "L1")
    assert res["interprete"]["rompe"] == ["C1"] == res["motor"]["rompe"]


def test_compuerta_rechaza_si_el_interprete_no_mejora(proyecto):
    r = proyecto
    correr(r)
    l = ciclo.proponer(r)[-1]
    # El intérprete "no aplica" la candidata: el lote de prueba sale igual que el base.
    lote_base = [json.loads(x) for x in r.corridas.read_text().splitlines()]
    with r.corridas.open("a") as f:
        for fila in lote_base:
            fila.update(lote="falso", candidata=l["codigo"], run_id=fila["run_id"] + "-f")
            f.write(json.dumps(fila) + "\n")
    res = ciclo.evaluar(r, l["codigo"])
    assert not res["acepta"] and "no arregla" in res["motivos"][0]
    assert (r.rechazadas / f"{l['codigo']}.json").exists()
    assert res["discrepancias"], "debe avisar que el intérprete no coincide con el motor"


def test_propuesta_que_rompe_se_rechaza_sin_llegar_al_interprete(proyecto, monkeypatch):
    r = proyecto
    correr(r)
    mala = {"tipo": "umbral", "titulo": "todo latido es alarma", "aplica_a": {"origen": ["*"]},
            "regla": {"dispara": {"tipo_evento": "Heartbeat"}, "motivo": "Ruido"}}
    monkeypatch.setattr(ciclo, "proponer_plantilla", lambda *a: mala)
    l = ciclo.proponer(r, caso="C4")[-1]
    assert l["estado"] == "rechazada" and "rompe" in l["motivo_rechazo"]
    assert not list(r.candidatas.glob("*.json"))


def test_no_aprende_de_reservados(proyecto):
    correr(proyecto)
    with pytest.raises(ciclo.Detenido, match="reservado"):
        ciclo.proponer(proyecto, caso="R1")


def test_corrida_mal_formada_se_reporta_con_linea(proyecto):
    proyecto.corridas.write_text('{"run_id": "x"}\n')
    with pytest.raises(ValueError, match="línea 1"):
        ciclo.estado(proyecto)


def test_equipo_retira_una_leccion_y_crea_version(proyecto):
    r = proyecto
    correr(r)
    _una_ronda(r)
    ciclo.rechazar(r, "L1", "prueba manual", actor="equipo")
    v = leer_json(r.versiones)
    assert v["actual"] == "v2" and v["historial"][-1]["creado_por"] == "equipo"
