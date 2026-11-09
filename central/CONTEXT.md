# SignalLoop Central

Quarkus 3.38.3, Java 21, artefacto `io.signalloop:central`.

Flyway aplica `db/migrations/V001__initial_schemas.sql` desde el classpath. No hay copia paralela del SQL. Hibernate no crea tablas.

Endpoints de este corte: proyecto, problem spec, escenario, ATM, ticket, log y expected outcome. `GET /api/v1/atms/{id}/logs` es la secuencia de señal, ordenada por `occurred_at`, `id`. No devuelve `expected_outcomes`.

Aún no hay generaciones, evaluaciones, lecciones ni reglas. El contrato de esa secuencia es `docs/spec/SPEC-0002-signal-sequence.md`, en borrador.

El siguiente diseño propuesto para interpretar señales y mantener versiones V0/V1/V2 dentro de este codebase está en `docs/spec/SPEC-0003-versioned-interpreter.md` (Draft). `SignalLoopMixto/` es referencia funcional y oráculo temporal para pruebas de paridad; no es runtime ni destino de integración. Central aún no ejecuta clasificación.
