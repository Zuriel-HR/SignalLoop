# Contrato entre el intérprete y el Aprendiz (modo Mixto)

El intérprete puede estar en cualquier lenguaje. El Aprendiz está en Python. No comparten código ni base de datos: solo estos archivos.

| Archivo | Lo escribe | Lo lee | Esquema |
|---|---|---|---|
| `banco/casos/*.json` | Equipo | Intérprete y Aprendiz | `contrato/caso.schema.json` |
| `resultados/corridas.jsonl` | **Intérprete** (solo agrega líneas) | Aprendiz | `contrato/corrida.schema.json` |
| `lecciones/candidatas/<L>.json` | **Aprendiz** | Intérprete, solo en una corrida de prueba | `contrato/leccion.schema.json` |
| `lecciones/activas/<L>.json` | Aprendiz (la compuerta) | Intérprete, en cada corrida | `contrato/leccion.schema.json` |
| `lecciones/rechazadas/<L>.json` | Aprendiz o equipo | Nadie en el ciclo (es evidencia) | `contrato/leccion.schema.json` |
| `lecciones/versiones.json` | Aprendiz | Intérprete (campo `actual`) | — |
| `resultados/bitacora_rsi.jsonl` | Aprendiz (y el intérprete, si quiere) | Panel | — |

Nadie modifica el banco desde el ciclo. Si el Aprendiz pudiera cambiar los casos esperados, podría subir su nota sin mejorar.

## El ciclo, paso a paso

```
Intérprete  ── lote normal (vN) ─────────────► corridas.jsonl
Aprendiz    ◄─ lee corridas ── proponer ─────► lecciones/candidatas/L1.json   (ya validada con el motor)
Intérprete  ── lote con candidata = "L1" ────► corridas.jsonl
Aprendiz    ◄─ evaluar L1 ── compuerta ──────► lecciones/activas/L1.json + versiones.json (vN+1)
Intérprete  ── recarga activas, lote vN+1 ──► corridas.jsonl
```

## Lo que hace el intérprete

**1. Antes de cada lote**, recarga:
- `lecciones/versiones.json` → usa `actual` como `version` (ej. `"v1"`).
- Todos los `lecciones/activas/L*.json`.

**2. Corre todos los casos de `banco/casos/`** (banco y reservados) y agrega **una línea por caso** a `resultados/corridas.jsonl`. Todas las líneas de un lote comparten `lote`, `version`, `lecciones_activas`, `candidata` y `sin`.

Tres modos, según quién lo pida:

| Modo | `lecciones_activas` | `candidata` | `sin` | Lecciones aplicadas |
|---|---|---|---|---|
| Normal | códigos de activas | `null` | `[]` | activas |
| Prueba de candidata | códigos de activas | `"L5"` | `[]` | activas + `lecciones/candidatas/L5.json` |
| Ablación | códigos de activas | `null` | `["L1"]` | activas menos L1 |

En el modo de prueba, `lecciones_activas` **no** incluye la candidata.

**3. Línea mínima** (los campos `tickets` y `senales` son opcionales, pero `tickets` es la evidencia que cita la lección):

```json
{"run_id": "v0-20261109T150846-C1", "lote": "v0-20261109T150846", "version": "v0", "caso": "C1",
 "conjunto": "banco", "lecciones_activas": [], "candidata": "L1", "sin": [],
 "esperado": {"tickets_creados": 1, "tickets_abiertos": 0},
 "obtenido": {"tickets_creados": 1, "tickets_abiertos": 0},
 "pasa": true,
 "tickets": [{"id": "T1", "dispositivo": "ATM001", "sensor": "Dispensador", "motivo": "CashOut",
              "estado": "cerrado", "abierto_por_evento": "XFS-ATM001-C1-0002",
              "cerrado_por_evento": "XFS-ATM001-C1-0003", "cierre_por": "L1"}],
 "ts": "2026-11-09T15:08:46-06:00", "actor": "interprete"}
```

- `pasa` = `obtenido.tickets_creados == esperado.tickets_creados` **y** `obtenido.tickets_abiertos == esperado.tickets_abiertos`.
- `esperado` se copia tal cual del caso.
- `run_id` debe ser único; `lote` + `caso` funciona.
- Escribe cada línea completa con un salto de línea al final. Si el lote se corta a medias, la compuerta lo rechaza porque le faltan casos.

## Reglas base (v0)

1. Cada elemento del grupo con `tipo_evento = "Alarma"` abre un ticket nuevo con `dispositivo`, `sensor` y `motivo = status.condicion`.
2. Un elemento `Status` con `status.status == 0` cierra los tickets abiertos del mismo dispositivo y sensor (`cierre_por = "status=0"`).
3. Nada más abre ni cierra tickets.

Con estas reglas, v0 resuelve 2 de 6 casos del banco (C3 y C6).

## Cómo aplicar cada lección

La definición ejecutable está en `aprendiz/motor.py`. Si el intérprete y el motor no coinciden, corre `python -m aprendiz verificar`: dice en qué caso y con qué conteos.

**Condición** (`COND`): se evalúa contra **un elemento de `evento.grupo`**. Deben cumplirse todas las claves presentes:
`tipo_evento` y `sensor` (texto, lista o `"*"`), `campo` (ruta con puntos, ej. `status.evento`) con `igual`, `en`, `mayor_que`, `menor_que` o `existe`. Un `campo` sin comparador significa «existe».

**`aplica_a.origen`**: la lección solo se aplica a eventos de esos orígenes. `["*"]` = todos.

| `tipo` | `regla` | Qué hace |
|---|---|---|
| `deduplicacion` | `clave: ["event_id"]`, `ventana_s` | Antes de todo: si la clave del evento ya se vio dentro de la ventana, se descarta el evento completo. |
| `ciclo_de_vida` | `abre: COND`, `cierra_con: [COND]` | Un elemento que cumple alguna `cierra_con` cierra los tickets abiertos **del mismo dispositivo** cuya alarma de apertura cumple `abre`. `cierre_por` = código de la lección. |
| `flapping` | `abre: COND`, `cierra_con: [COND]`, `reabre_en_s` | Igual que `ciclo_de_vida`, pero solo mismo dispositivo **y sensor**. Además, una alarma que cumple `abre`: si ya hay ticket abierto con el mismo (dispositivo, sensor, motivo), no abre otro; si hubo uno cerrado hace ≤ `reabre_en_s`, lo reabre (`reaperturas + 1`). |
| `silencio` | `latido: COND`, `max_silencio_s`, `motivo` | Cada elemento que cumple `latido` registra la hora del dispositivo. Si pasa más de `max_silencio_s` sin latido (medido en cada evento y en `caso.fin`), abre un ticket con ese `motivo`. Un latido posterior lo cierra. |
| `umbral` | `dispara: COND`, `motivo` | Un elemento que cumple `dispara` abre un ticket con ese `motivo` si no hay uno abierto igual. |

Orden dentro de cada evento: revisar silencios → deduplicar → por cada elemento del grupo: latidos, apertura por alarma (con flapping), umbral, cierre base `status=0`, cierres aprendidos.

## Lo que hace el Aprendiz

| Comando | Lee | Escribe |
|---|---|---|
| `proponer [--caso C1] [--llm]` | último lote normal con las activas actuales + banco | `lecciones/candidatas/` o `lecciones/rechazadas/` |
| `evaluar L1` | lote normal y lote con `candidata = "L1"` | `lecciones/activas/`, `versiones.json` o `lecciones/rechazadas/` |
| `ablacion L1` | lote normal y lote con `sin = ["L1"]` | bitácora |
| `verificar [--lote X]` | un lote + lecciones citadas | nada |
| `reporte` | todo | `resultados/reporte.json` (para el panel) |

**Compuerta.** Acepta solo si, en el intérprete, la candidata arregla al menos un caso del banco, no rompe ninguno, el lote trae todos los casos y la lección ya pasó el motor de referencia. Los reservados (R1, R2) se reportan, pero nunca deciden ni se usan para aprender.
