"""Proponedor con LLM (opcional). Requiere `pip install openai` y OPENAI_API_KEY.

El modelo solo redacta la regla en JSON. El esquema, el motor de referencia y la compuerta
deciden si entra; el modelo nunca ve ni toca el banco de casos esperado más allá del caso
que falló, ni puede escribir en lecciones/activas.
"""
from __future__ import annotations

import json
import os

MODELO = os.environ.get("SIGNALLOOP_MODELO", "gpt-4.1-mini")

INSTRUCCIONES = """Eres el Aprendiz de SignalLoop, un monitor de eventos de cajeros (XFS), IoT y SNMP.
El intérprete falló un caso. Investiga la evidencia y propone UNA regla declarativa que lo corrija
sin romper casos normales. Responde solo con un objeto JSON con estas claves:
  tipo, titulo, hipotesis, aplica_a, regla.

Reglas base del intérprete (v0): cada Alarma abre un ticket (dispositivo, sensor, status.condicion);
un Status con status.status == 0 cierra los tickets del mismo dispositivo y sensor; nada más cierra.

Tipos de regla permitidos (el campo regla depende del tipo):
- deduplicacion: {"clave": ["event_id"], "ventana_s": 600}
- ciclo_de_vida: {"abre": COND, "cierra_con": [COND, ...]}  # cierra tickets abiertos del mismo dispositivo
- flapping: {"abre": COND, "cierra_con": [COND, ...], "reabre_en_s": 300}  # una alarma repetida reabre el mismo ticket
- silencio: {"latido": COND, "max_silencio_s": 90, "motivo": "SinLatido"}
- umbral: {"dispara": COND, "motivo": "..."}
COND se evalúa contra un elemento de evento.grupo y admite solo estas claves:
  tipo_evento, sensor (texto o lista), campo (ruta tipo "status.evento"), igual, en, mayor_que, menor_que, existe.
aplica_a: {"origen": ["XFS"]} o {"origen": ["*"]}. Generaliza solo si la falla no depende del protocolo.
hipotesis: una o dos frases en español que citen eventos concretos de la evidencia."""


def proponer_llm(caso: dict, corrida: dict, senales: list[str], activas: list[dict]) -> dict:
    try:
        from openai import OpenAI
    except ImportError as e:
        raise RuntimeError("Falta el paquete openai: pip install openai") from e
    if not os.environ.get("OPENAI_API_KEY"):
        raise RuntimeError("Falta OPENAI_API_KEY")

    evidencia = {
        "caso": {k: caso[k] for k in ("caso", "nombre", "origen", "descripcion", "fin", "eventos")},
        "esperado": caso["esperado"],
        "obtenido": corrida["obtenido"],
        "senales": senales,
        "tickets_del_interprete": corrida.get("tickets", []),
        "lecciones_activas": [{k: l[k] for k in ("codigo", "tipo", "titulo", "aplica_a", "regla")} for l in activas],
    }
    cliente = OpenAI()
    r = cliente.chat.completions.create(
        model=MODELO,
        response_format={"type": "json_object"},
        messages=[{"role": "system", "content": INSTRUCCIONES},
                  {"role": "user", "content": json.dumps(evidencia, ensure_ascii=False)}],
    )
    propuesta = json.loads(r.choices[0].message.content)
    faltan = {"tipo", "titulo", "aplica_a", "regla"} - set(propuesta)
    if faltan:
        raise ValueError(f"La propuesta del modelo no trae {sorted(faltan)}")
    return {k: propuesta[k] for k in ("tipo", "titulo", "hipotesis", "aplica_a", "regla") if k in propuesta}
