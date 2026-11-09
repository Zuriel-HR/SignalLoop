---
id: SPEC-0002
title: Secuencia de señal XFS sobre el esquema V001
status: Draft
version: 0.1.0
owner: SignalLoop API
created: 2026-11-09
updated: 2026-11-09
reviewers: []
depends_on: [DB-MIGRATION-001, DB-OPS-0001, CTX-02]
supersedes: []
tags: [specification, sequence, xfs, postgresql]
---

# SPEC-0002: Secuencia de señal XFS sobre el esquema V001

## 1. Propósito

Definir la primera secuencia que la Central persiste y lee, usando solo tablas de `db/migrations/V001__initial_schemas.sql`. Esta secuencia es la forma almacenada de la lección XFS descrita en `context/02_ciclo_de_aprendizaje_y_evaluacion.md`.

## 2. Antecedentes

V001 no tiene una tabla de eventos normalizados. La señal observable de un cajero está en `simulator.logs`, ordenable por `occurred_at`. El ticket describe la falla. `simulator.expected_outcomes` es la etiqueta del evaluador y no forma parte de la entrada del sistema que se puntúa.

`SPEC-0001` sigue en borrador y propone otro sobre. No es el contrato de estos endpoints.

## 3. Problema

Sin un orden canónico de lectura, tres logs de dispensador no constituyen una secuencia: el sistema no puede decir qué advertencia ocurrió antes de la falla, ni separar esa observación de la respuesta esperada.

## 4. Alcance

- Definir la secuencia de señal como lectura ordenada de logs de un ATM.
- Fijar la primera secuencia XFS de demostración: dos advertencias y una falla.
- Decir qué endpoints de la Central escriben y leen esa secuencia hoy.
- Dejar el resto del ciclo de `context/02` mapeado a tablas, sin implementarlo todavía.

## 5. No objetivos

- No declara códigos `DISP_*` como catálogo SQL. `logs.error_code` es texto libre; `error_catalog` no tiene clave foránea desde `logs`.
- No ejecuta reglas, no abre incidentes y no crea generaciones ni evaluaciones.
- No aísla por permisos el split `test` ni `expected_outcomes`. La API simplemente no los mezcla en la lectura de logs.
- No cambia V001.

## 6. Definiciones

- **Secuencia de señal:** filas de `simulator.logs` de un `atm_id`, ordenadas por `occurred_at` ascendente y, en empate, por `id` ascendente.
- **Paso:** una fila de esa secuencia.
- **Falla asociada:** `simulator.tickets` opcional referenciado por `logs.ticket_id`.
- **Etiqueta de evaluación:** la fila única de `simulator.expected_outcomes` del ticket. No es un paso.

## 7. Propuesta / Diseño

### 7.1 Secuencia XFS inicial

Escenario `scenario_key=xfs-dispenser-prefail`, `domain=atm_support`, `seed=7`. ATM `ATM-001`. Ticket `T-JAM-001`, `category=hardware`, `severity=P2`, `split=train`.

| Orden | `occurred_at` | `level` | `subsystem` | `error_code` | `ticket_id` |
|---|---|---|---|---|---|
| 1 | 2026-11-09T18:00:00Z | `WARN` | `XFS` | `DISP_WARN_LOW` | nulo |
| 2 | 2026-11-09T18:01:00Z | `WARN` | `XFS` | `DISP_JAM_WARN` | nulo |
| 3 | 2026-11-09T18:02:00Z | `ERROR` | `XFS` | `DISP_JAM` | `T-JAM-001` |

La etiqueta, guardada aparte, dice `root_cause=dispenser jam`, `expected_category=hardware`, `expected_severity=P2`, `expected_next_action=service the dispenser`.

El orden de inserción no importa. La lectura manda.

### 7.2 Endpoints que sostienen esta secuencia

| Método y ruta | Tabla | Resultado |
|---|---|---|
| `POST /api/v1/projects` | `project.projects` | 201, o 409 `SLUG_CONFLICT` |
| `GET /api/v1/projects/{id}` | `project.projects` | 200 o 404 |
| `POST /api/v1/projects/{id}/problem-specs` | `project.problem_specs` | 201, o 409 `PROBLEM_KEY_CONFLICT` |
| `POST /api/v1/scenarios` | `simulator.scenarios` | 201. `version` ausente vale 1, `domain` ausente vale `atm_support`, `status` ausente vale `draft` |
| `GET /api/v1/scenarios/{id}` | `simulator.scenarios` | 200 o 404 |
| `POST /api/v1/scenarios/{id}/atms` | `simulator.atms` | 201, o 409 `ATM_KEY_CONFLICT` |
| `POST /api/v1/scenarios/{id}/tickets` | `simulator.tickets` | 201. El ATM, si viene, tiene que ser del mismo escenario |
| `POST /api/v1/scenarios/{id}/logs` | `simulator.logs` | 201. Agrega un paso. ATM obligatorio y del mismo escenario |
| `GET /api/v1/atms/{atmId}/logs` | `simulator.logs` | La secuencia. No incluye `expected_outcomes` |
| `POST /api/v1/tickets/{ticketId}/expected-outcome` | `simulator.expected_outcomes` | 201 para el evaluador, o 409 `OUTCOME_CONFLICT` |

Un valor fuera de un `CHECK` de V001 responde 400 `INVALID_REQUEST` y no se inserta. `GET /health/ready` falla si PostgreSQL no responde.

### 7.3 Ciclo que esta secuencia todavía no cierra

El orden de `docs/database-setup.md` sección 7.9 sigue vigente. Después de la secuencia de señal faltan, en este orden: sistema agente y generación `baseline`, dataset y exportación, `evaluation_runs` y `case_results`, evidencia y lección, decisión de cambio y generación sucesora. Esas tablas no tienen endpoint en este corte.

## 8. Alternativas consideradas

- Seguir con `POST /api/v1/events` en memoria: no cabe en V001.
- Exigir los códigos `DISP_*` en un `CHECK`: cambiaría la migración para una sola demo.
- Devolver el outcome junto con los logs: filtra la etiqueta al mismo canal que la señal.

## 9. Riesgos

- Cualquier cliente puede escribir `expected_outcomes` y leerlos por su propio endpoint. V001 no separa roles.
- `frozen` no bloquea escrituras.
- Los códigos de la tabla 7.1 pueden repetirse con otro significado porque no hay clave foránea hacia `error_catalog`.

## 10. Preguntas abiertas

1. ¿La generación baseline y la primera evaluación entran en el siguiente corte, o primero se congela el escenario?
2. ¿Quién queda autorizado a llamar `expected-outcome` cuando exista autenticación?
3. ¿Los códigos `DISP_*` deben registrarse también en `simulator.error_catalog` antes de la demo?

## 11. Documentos relacionados

- `db/migrations/V001__initial_schemas.sql`
- `docs/database-setup.md`
- `docs/database-migration-v001.md`
- `docs/spec/SPEC-0001-event-contract.md`
- `context/02_ciclo_de_aprendizaje_y_evaluacion.md`

## 12. Changelog

| Versión | Fecha | Cambio |
|---|---|---|
| 0.1.0 | 2026-11-09 | Primera definición de la secuencia XFS y de los endpoints que la persisten. |
