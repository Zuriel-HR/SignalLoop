# 03 · Plan de 8 horas y roles

## Roles

| Persona | Responsabilidad |
|---|---|
| **1 · Backend** | FastAPI, `POST /ingest`, normalización, incidentes, BD (Postgres / plan B SQLite), endpoints para dashboard |
| **2 · Agentes / aprendizaje** | Investigation + Learning Agent, formato de lección, validación con pytest, construcción de `vN+1`, evals |
| **3 · Simuladores** | Generadores XFS/SNMP/IoT/contexto, escenarios normales y anómalos con `expected.json`, casos held-out |
| **4 · Frontend / integración** | Dashboard, Learning Lab, integración, guion y ensayo de la demo. Si son 3, se reparte con Backend |

## Cronograma

| Hora | Objetivo | Hecho cuando… |
|---|---|---|
| 0:00–0:45 | Contratos (doc 01), repo, escenarios semilla, guion | Esquemas y `expected.json` de XFS acordados |
| 0:45–3:30 | Construcción en paralelo | Cada pieza corre sola con mocks |
| 3:30–4:00 | **Integración 1**: XFS punta a punta con v0 (sin IA) | Evento simulado → incidente en dashboard |
| 4:00–6:00 | Ciclo RSI: señal → lección → regla → pytest → v1 | v1 guardada y mejor que v0 en evals |
| 6:00–7:00 | Held-out (SNMP o IoT) reutilizando regla; v2 si da tiempo; métricas | `evals/report.json` con v0/v1/v2 |
| 7:00–8:00 | Congelar código, ensayar 3 min, grabar video de respaldo | Demo corre 2 veces seguidas sin fallar |

## Reglas de equipo

- Toda decisión manual se anota en `06_bitacora_rsi.md` como `EQUIPO`.
- Cada uso relevante de Codex se anota (sirve para el criterio 2).
- Al inicio, declarar qué se trajo de antes (base previa) y qué se construyó en el hackathon.
