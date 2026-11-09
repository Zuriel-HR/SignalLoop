# SignalLoop · Aprendiz (modo Mixto)

Este prototipo Python documenta el comportamiento del ciclo de aprendizaje y del motor de reglas. Se conserva como **referencia funcional y oráculo de paridad** mientras el producto se construye en Java dentro de `central/`; no es un servicio que se vaya a integrar o desplegar con Central.

- El intérprete escribe `resultados/corridas.jsonl`: una línea por caso del banco en cada lote.
- El Aprendiz escribe el JSON de la lección en `lecciones/candidatas/` y, si la compuerta la acepta, en `lecciones/activas/`.

El contrato completo, con la semántica de cada regla, está en **[CONTRATO.md](CONTRATO.md)**. Dáselo a quien implemente el intérprete.

## Uso como referencia funcional

Este proyecto es referencia del comportamiento y oráculo temporal de pruebas para el intérprete Java de [`../central/`](../central/). No es el destino de nuevas funcionalidades ni un componente runtime que se vaya a desplegar con Central. La propuesta de snapshots V0/V1/V2 dentro del codebase destino está en [`../docs/spec/SPEC-0003-versioned-interpreter.md`](../docs/spec/SPEC-0003-versioned-interpreter.md).

Hoy el estado local de este proyecto se registra en `lecciones/versiones.json` y sus reglas activas viven en `lecciones/activas/`. Esa estructura describe el prototipo de referencia, no la estructura del producto Central.

## Instalar

```bash
pip install -e ".[dev]"          # jsonschema + pytest
pip install -e ".[llm]"          # opcional: proponer con OpenAI
```

## Ciclo

```bash
# 1. Intérprete: lote normal v0  →  resultados/corridas.jsonl
python -m aprendiz estado        # dice cuál es el siguiente paso
python -m aprendiz proponer      # L1 en lecciones/candidatas/, ya validada con el motor
# 2. Intérprete: lote con candidata = "L1"
python -m aprendiz evaluar L1    # compuerta: activa → v1, o rechazada con motivo
# 3. Intérprete: recarga lecciones/activas/ y corre v1 … y se repite
python -m aprendiz reporte       # tabla v0…vN + resultados/reporte.json para el panel
python -m aprendiz ablacion L1   # pide al intérprete un lote con sin = ["L1"]
python -m aprendiz verificar     # ¿el intérprete aplica las reglas igual que el motor?
pytest tests/test_lecciones.py   # «las pruebas validan»: cada lección contra el banco
```

`proponer --llm` le pide la regla a un modelo de OpenAI (`OPENAI_API_KEY`, modelo en `SIGNALLOOP_MODELO`, por defecto `gpt-4.1-mini`). Si el modelo falla o su regla no pasa el motor, queda en `rechazadas/` y el Aprendiz usa la plantilla determinista. Las dos quedan en la bitácora.

`rechazar L3 --motivo "..."` es la intervención del equipo. Queda con `actor: "equipo"`; si la lección estaba activa, crea una versión nueva sin ella.

## Probar sin el intérprete real

`herramientas/interprete_simulado.py` usa el motor de referencia y escribe corridas con el formato del contrato. Sirve para que el equipo del intérprete compare su salida y para ensayar. **En la demo, las corridas deben venir del intérprete real**, o la rúbrica lo leerá como el sistema evaluándose a sí mismo.

```bash
python herramientas/interprete_simulado.py                 # lote normal
python herramientas/interprete_simulado.py --candidata L1  # prueba de candidata
python herramientas/interprete_simulado.py --sin L1        # ablación
```

Con el simulado, el ciclo completo da esto (es la misma corrida que hace `tests/test_flujo.py`):

```
versión lecciones        banco reserv. C1 C2 C3 C4 C5 C6 R1 R2
v0                       2/6   0/2     ✘  ✘  ✔  ✘  ✘  ✔  ✘  ✘
v1      L1               3/6   0/2     ✔  ✘  ✔  ✘  ✘  ✔  ✘  ✘
v2      L1,L2            4/6   0/2     ✔  ✔  ✔  ✘  ✘  ✔  ✘  ✘
v3      L1,L2,L3         5/6   0/2     ✔  ✔  ✔  ✔  ✘  ✔  ✘  ✘
v4      L1,L2,L3,L4      6/6   1/2     ✔  ✔  ✔  ✔  ✔  ✔  ✘  ✔
```

| Lección | Tipo | Aprendida de | Qué hace |
|---|---|---|---|
| L1 | `ciclo_de_vida` (XFS) | C1 | El CashOut se cierra con `Operacion` `REPLENISH` |
| L2 | `deduplicacion` | C2 | Un mismo `event_id` abre un solo ticket |
| L3 | `silencio` | C4 | 90 s sin latido abre un ticket |
| L4 | `flapping` | C5 (SNMP) | La recuperación cierra; la alarma repetida reabre el mismo ticket |

Tres cosas que conviene decir en la demo porque son ciertas:

- **Transferencia:** L4 se aprendió en un router SNMP y resuelve R2, una caldera IoT que el sistema nunca vio.
- **Lo que sigue fallando:** R1 (cash out que se resuelve con un dispensado exitoso, sin `REPLENISH`). L1 solo aprendió lo que vio en C1. Es la pregunta natural del jurado y un buen caso para `--llm`.
- **Redundancia:** con L4 activa, `validar` marca L2 como redundante: la regla de flapping también absorbe la alarma duplicada. La ablación de L2 lo confirma (nada vuelve a fallar).

## Estructura

```
contrato/        esquemas JSON de corrida, lección y caso
banco/casos/     C1–C6 (banco) y R1–R2 (reservados, no se usan para aprender)
lecciones/       activas/ candidatas/ rechazadas/ versiones.json
resultados/      corridas.jsonl (intérprete) · bitacora_rsi.jsonl · reporte.json
aprendiz/        motor.py (semántica de referencia) · senales.py · proponer.py · llm.py · ciclo.py · cli.py
herramientas/    interprete_simulado.py
tests/           motor, lecciones del proyecto y flujo completo
```
