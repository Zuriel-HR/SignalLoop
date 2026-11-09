---
id: DB-OPS-0001
title: Configuración y operación del esquema PostgreSQL de SignalLoop
status: Draft
version: 0.3.1
owner: SignalLoop Backend
created: 2026-11-09
updated: 2026-11-09
reviewers: []
depends_on: [DB-MIGRATION-001, RFC-0001, ARC-0001]
supersedes: []
tags: [database, postgresql, migrations, operations]
---

# Configuración y operación del esquema PostgreSQL de SignalLoop

- `project`: agent systems, immutable generations, datasets, evaluation runs and per-case results, evidence-backed lessons, change decisions, mutation statistics, playbooks, and transfer experiments.
- `simulator`: synthetic scenarios, ATMs and generic device assets, tickets, XFS/IoT/SNMP error catalog, runbooks, logs, ATM transactions, expected outcomes, and frozen dataset exports.

Documentar los requisitos, aplicación, verificación y estructura de la migración inicial `db/migrations/V001__initial_schemas.sql`. El SQL versionado sigue siendo la estructura. La Central lo aplica con Flyway al arrancar, como se resume en la sección 7.10.

## 2. Antecedentes

V001 define dos esquemas PostgreSQL, `project` y `simulator`, con 24 tablas y 12 índices secundarios. El esquema `project` registra proyectos, especificaciones de problema, datasets, generaciones de sistemas, evaluaciones, lecciones, decisiones, playbooks y experimentos de transferencia. El esquema `simulator` almacena escenarios ATM, tickets, catálogo de errores, runbooks, logs, transacciones, resultados esperados y exportaciones de datasets.

La separación permite que los datos generados por simuladores alimenten ejecuciones y evaluaciones sin mezclar las responsabilidades de almacenamiento. Las particiones `train`, `validation` y `test` aparecen tanto en datasets como en tickets y exportaciones; el código consumidor debe respetar la política de aislamiento del conjunto `test`.

## 3. Problema

La migración necesita instrucciones reproducibles para preparar una base local y una descripción de las entidades y restricciones, de modo que los equipos puedan cargar datos y validar evaluaciones sin depender de conocimiento oral.

## 4. Alcance

- Requisitos previos de PostgreSQL y UUID.
- Creación de base de datos, aplicación de V001 y comprobaciones posteriores.
- Resumen de tablas, relaciones funcionales, constraints e índices definidos.
- Configuración local de conexión y manejo de credenciales.
- Límites conocidos del SQL y preguntas pendientes para integración con Quarkus.

## 5. No objetivos

- No cambia V001 ni prescribe un proveedor cloud.
- No duplica el contrato de la secuencia; ese contrato está en `docs/spec/SPEC-0002-signal-sequence.md`.
- No sustituye los archivos SQL versionados ni define datos de producción.

## 6. Definiciones

- **Esquema PostgreSQL:** espacio de nombres que agrupa tablas; V001 crea `project` y `simulator`.
- **Migración:** cambio SQL versionado y ordenado aplicado a una base de datos.
- **Dataset split:** partición declarada como `train`, `validation` o `test`.
- **Generación:** snapshot de configuración de un sistema agente con número, posible generación padre y estado de evaluación.
- **Outcome esperado:** etiqueta de referencia accesible al evaluador; no debe enviarse como entrada al sistema bajo evaluación.
- **V001:** archivo `db/migrations/V001__initial_schemas.sql`; la repetición del DDL de tablas no está diseñada como migración plenamente idempotente.

## 7. Propuesta / Diseño

### 7.1 Requisitos previos

1. PostgreSQL instalado y accesible desde la terminal o pgAdmin.
2. Permiso para crear la base `signalloop` o acceso a una base ya creada.
3. La función `gen_random_uuid()` disponible. V001 la usa como default de claves UUID, pero no crea una extensión ni declara una versión mínima de PostgreSQL. Verificar su disponibilidad antes de ejecutar; si falta, acordar y aplicar una precondición versionada para la instalación elegida.
4. Ejecutar comandos desde la raíz del repositorio para que la ruta relativa de la migración exista.

### 7.2 Crear una base local vacía

Con utilidades de PostgreSQL configuradas en PATH:

```sh
createdb signalloop
psql -d signalloop -f db/migrations/V001__initial_schemas.sql
```

Alternativa en pgAdmin:

1. Crear o seleccionar la base `signalloop`.
2. Abrir Query Tool conectado a esa base.
3. Abrir el archivo `db/migrations/V001__initial_schemas.sql` desde la raíz del repo.
4. Ejecutar todo el script y revisar el resultado antes de cargar datos.

V001 contiene `CREATE SCHEMA IF NOT EXISTS`; esto no hace que todas sus sentencias sean seguras para repetición. Las tablas se crean sin `IF NOT EXISTS`. No volver a ejecutar V001 sobre una base parcialmente aplicada o ya poblada. Para una base desechable local, eliminarla y recrearla solo si se confirma que no contiene datos que deban conservarse; en otros casos, inspeccionar el estado y preparar una nueva migración correctiva.

### 7.3 Verificar la instalación

```sh
psql -d signalloop -c '\dn'
psql -d signalloop -c '\dt project.*'
psql -d signalloop -c '\dt simulator.*'
```

Migration `V001` creates 15 project tables and 9 simulator tables. `V002` extends simulator logs and assets for IoT and SNMP sources. Apply new migrations in order. Do not commit PostgreSQL dumps, `.env` files, passwords, or real customer data.

```sql
SELECT table_schema, count(*) AS table_count
FROM information_schema.tables
WHERE table_type = 'BASE TABLE'
  AND table_schema IN ('project', 'simulator')
GROUP BY table_schema
ORDER BY table_schema;
```

Para confirmar la disponibilidad de UUID:

```sql
SELECT gen_random_uuid();
```

### 7.4 Mapa del esquema `project`

| Tabla | Responsabilidad observada en V001 | Relación principal |
|---|---|---|
| `project.projects` | Identidad del proyecto (`slug`, nombre y descripción) | Raíz del proyecto |
| `project.problem_specs` | Definición/versionado del problema y evaluador | Pertenece a un proyecto; `key` es única dentro del proyecto |
| `project.datasets` | Dataset, split, procedencia, semilla, cantidad y hash | Pertenece a una especificación; `(problem_spec_id, name)` único |
| `project.agent_systems` | Sistema agente y rol `solver`/`builder` | Pertenece a proyecto y especificación |
| `project.generations` | Configuración/hash de generación, ascendencia y cambio | Pertenece a sistema; número y hash únicos por sistema |
| `project.evaluation_runs` | Ejecución de evaluación por generación y dataset | Referencia sistema, generación, dataset y grupo de corrida |
| `project.case_results` | Entrada, expected/output, score, herramientas, costo y latencia por caso | Pertenece a corrida; cascada al borrar la corrida; clave de caso única por corrida |
| `project.lessons` | Lecciones propuestas/aceptadas/rechazadas/superseded | Pertenece a proyecto; clave de lección única por proyecto |
| `project.lesson_evidence` | Evidencia de apoyo o contraejemplo para una lección | Unión de lección y resultado de caso; ambos borrados en cascada |
| `project.generation_lessons` | Lecciones asociadas a una generación | Unión generación/lección; borrar generación borra la asociación |
| `project.change_decisions` | Decisión y estadísticas de comparación de candidata vs padre | Cada candidata tiene como máximo una decisión (`candidate_generation_id` unique) |
| `project.mutation_stats` | Intentos, aceptaciones, ganancia y costo por tipo de cambio | Clave compuesta `(system_id, change_type)` |
| `project.playbooks` | Snapshot JSON versionado por proyecto | `(project_id, version)` único; fuente de sistema opcional |
| `project.playbook_lessons` | Lecciones incluidas en un playbook | Tabla de unión; borrar playbook elimina asociaciones |
| `project.transfer_experiments` | Experimentos de transferencia con/sin playbook | Pertenece a proyecto y problema destino; playbook opcional |

### 7.5 Mapa del esquema `simulator`

| Tabla | Responsabilidad observada en V001 | Relación principal |
|---|---|---|
| `simulator.scenarios` | Escenario versionado, dominio, versión de simulador, semilla y estado | Raíz de los datos simulados; `(scenario_key, version)` único |
| `simulator.atms` | Dispositivos ATM y versiones de software/XFS | Pertenece a escenario; `atm_key` único por escenario |
| `simulator.tickets` | Tickets con categoría, severidad, split, metadata e impacto | Pertenece a escenario; ATM opcional; ticket key único por escenario |
| `simulator.error_catalog` | Código, subsistema, significado y acción recomendada | Pertenece a escenario; código único por escenario |
| `simulator.runbooks` | Síntoma y pasos diagnósticos/resolutivos | Pertenece a escenario; runbook key único por escenario |
| `simulator.logs` | Logs temporales vinculados a ATM y ticket opcional | ATM se borra en cascada; borrar ticket deja `ticket_id` nulo |
| `simulator.transactions` | Transacciones ATM con monto, moneda y estado | ATM se borra en cascada; ticket opcional se vuelve nulo al borrar ticket |
| `simulator.expected_outcomes` | Verdad de referencia por ticket para evaluación | Un resultado esperado por ticket; borrar ticket lo elimina |
| `simulator.dataset_exports` | Exportación por split/version con cantidad y hash | Pertenece a escenario; combinación escenario/split/versión única |

### 7.6 Restricciones semánticas principales

- Los splits aceptados en dataset, ticket y exportación son `train`, `validation` y `test`.
- Generaciones empiezan con número no negativo; `baseline` es uno de sus estados posibles.
- Corridas de evaluación permiten estados `queued`, `running`, `completed`, `failed` y `cancelled`; la fecha final no puede preceder a la inicial cuando ambas existen.
- `case_results.score` está entre 0 y 1; tokens y costos comienzan en cero.
- Lecciones limitan `scope`, `status` y `source` a los catálogos escritos en sus `CHECK` constraints; confianza, cuando existe, está entre 0 y 1.
- Decisiones de cambio admiten `accepted`, `rejected`, `inconclusive` o `failed` y guardan razón obligatoria.
- Escenarios pueden estar `draft`, `generated` o `frozen`; `frozen` es un estado de datos, no un mecanismo SQL que impida por sí solo actualizaciones.
- Ticks/outcomes y catálogos limitan categoría/severidad/subsistema/estado con `CHECK`; consultar V001 para los valores exactos antes de ampliar catálogos.
- Los índices secundarios cubren consultas de generaciones recientes, evaluaciones/casos, lecciones, experimentos, tickets por escenario/split/severidad y logs/transacciones por ATM-tiempo o ticket.

### 7.7 Política de datos de evaluación

La guía existente de simulación indica que `expected_outcomes` debe usarse solo por el evaluador, no como input del modelo que se puntúa. El split `test` debe permanecer fuera del ciclo que propone mutaciones/lecciones y reservarse para evaluación held-out. La base no impone esa separación entre componentes: debe aplicarse en el runner, el repositorio de datos y permisos/configuración de aplicación. Registrar en cada corrida generación, dataset, versión del evaluador, semilla (si aplica), estado y artefactos de resultados para que el reporte sea trazable.

### 7.8 Configurar conexión local

Usar una variable local; no escribir credenciales reales en Markdown versionado:

```env
DATABASE_URL=postgresql://USER:PASSWORD@localhost:5432/signalloop
```

El `.gitignore` excluye `.env` y `.env.*`, salvo `.env.example`. Cada colaborador debe configurar su propia base y credenciales. En despliegues, inyectar el valor mediante el gestor de secretos del entorno. No pasar credenciales en argumentos que queden en historial compartido.

Esta variable documenta una convención para la aplicación futura. V001 por sí sola no consume `DATABASE_URL`; el formato concreto aceptado por Quarkus queda pendiente de su especificación de configuración.

### 7.9 Orden recomendado de aplicación de datos

1. Crear proyecto y especificación del problema.
2. Registrar sistema agente y generación baseline.
3. Registrar escenarios y sus datos de entrenamiento/validación/test con versión y semilla.
4. Registrar datasets y exportaciones con conteos y hashes verificables.
5. Ejecutar evaluaciones y guardar resultados por caso.
6. Asociar evidencia antes de proponer lecciones y decisiones de cambio.
7. Mantener `test` reservado según la política del equipo.

El orden es guía conceptual derivada de las relaciones/propósito de las tablas; V001 no incluye datos semilla ni automatiza este flujo.

### 7.10 Central Quarkus

La aplicación `central/` aplica el archivo canónico `db/migrations/V001__initial_schemas.sql` con Flyway al arrancar. Maven lo copia al classpath `db/migration`; no hay una segunda copia versionada. La historia de Flyway queda en `public.flyway_schema_history`.

Si V001 ya se restauró a mano, la base `signalloop` queda con 15 tablas en `project` y 9 en `simulator`. Flyway vigila los esquemas `public`, `project` y `simulator`: al verlos ocupados marca la versión 1 como línea base y no vuelve a ejecutar el script. Repetir el restore sobre esa base falla: los `CREATE TABLE` no son idempotentes. Una base vacía, como `signalloop_test`, sí recibe V001 por Flyway.

La conexión usa `quarkus.datasource.*`, no `DATABASE_URL`:

```env
SIGNALLOOP_DB_JDBC_URL=jdbc:postgresql://localhost:5432/signalloop
SIGNALLOOP_DB_USERNAME=postgres
SIGNALLOOP_DB_PASSWORD=
```

Las pruebas usan la base `signalloop_test` y la limpian al arrancar. No apuntan a `signalloop`. PostgreSQL es el motor de este corte; el plan B de SQLite de `context/01` no aplica a V001. Los endpoints de la secuencia escriben `project.projects`, `project.problem_specs` y las tablas de escenario, ATM, ticket, log y outcome. El resto de `project` todavía no tiene escritor.

## 8. Alternativas consideradas

- **Un schema único:** simplificaría nombres, pero mezcla datos del simulador con estado del proyecto/evaluación.
- **SQL generado por ORM en arranque:** no es lo que se observa; la fuente versionada actual es una migración SQL explícita.
- **Reejecutar V001 para sincronizar una instalación:** no seguro por `CREATE TABLE` sin `IF NOT EXISTS`; futuras modificaciones deben ser nuevas migraciones ordenadas.

## 9. Riesgos

- `gen_random_uuid()` depende de disponibilidad de la función; la migración no documenta extensión/versión requerida.
- La guía no identifica todavía un migrador que registre versión aplicada. Aplicación manual puede perder trazabilidad y repetir accidentalmente SQL.
- Las FK usan acciones por defecto salvo los `ON DELETE` indicados; no se debe asumir cascada para las otras relaciones.
- `immutable`, `frozen`, hashes y estados describen intención/datos, pero V001 no impone inmutabilidad general mediante triggers o permisos.
- `expected_outcomes` y los datos `test` no están aislados por permisos SQL en esta migración.
- La tabla `project.projects` tiene columna `slug` única y obligatoria; poblar otras tablas requiere crear referencias en orden válido.
- La migración define tablas/índices pero no roles, grants, backups, retención, pooling, TLS ni política de recuperación.

## 10. Preguntas abiertas

1. ¿Qué versión mínima de PostgreSQL se admite y cómo se provee `gen_random_uuid()` en los entornos soportados?
2. Flyway quedó elegido para la Central. Sigue abierta la operación manual cuando la base no la arranca la aplicación.
3. La Central lee `quarkus.datasource.*` con las variables `SIGNALLOOP_DB_*`. `DATABASE_URL` no es entrada de la aplicación.
4. PostgreSQL es el motor de este corte. V001 no tiene equivalente SQLite.
5. ¿Quién puede leer `expected_outcomes` y el split `test` en ejecución?
6. ¿Qué restricciones de actualización/borrado se requieren para generaciones, reglas y evidencia aceptadas?
7. ¿Cuál es la estrategia de backup, restauración, retención y migración hacia atrás?
8. En este corte la Central escribe los dos esquemas para la secuencia de `SPEC-0002`. Falta decidir el escritor del resto de `project` y si el simulador escribirá `simulator` por su cuenta.

## 11. Documentos relacionados

- `db/migrations/V001__initial_schemas.sql` — fuente SQL de verdad de este documento.
- `docs/spec/SPEC-0002-signal-sequence.md` — secuencia XFS persistida y endpoints de este corte.
- `docs/rfc/RFC-0001-central-quarkus.md` — propuesta de despliegue Quarkus, todavía Draft.
- `docs/architecture/ARC-0001-central-modular.md` — propuesta de límites modulares, todavía Draft.
- `context/01_arquitectura_y_contratos.md` — propuesta de eventos e incidentes del hackathon.
- `context/02_ciclo_de_aprendizaje_y_evaluacion.md` — versiones, datos de evaluación y lecciones.
- `context/06_bitacora_rsi.md` — registro de resultados de la demo.
- `.gitignore` — exclusiones para archivos de entorno locales.

## 12. Changelog

| Versión | Fecha | Cambio |
|---|---|---|
| 0.3.1 | 2026-11-09 | Si V001 ya está restaurado, Flyway marca la versión 1 y no reejecuta el script. |
| 0.3.0 | 2026-11-09 | Flyway en la Central, variables `SIGNALLOOP_DB_*` y enlace a la secuencia de SPEC-0002. |
| 0.2.0 | 2026-11-09 | Sustituye guía breve por referencia de instalación, verificación, mapa de entidades y límites observados en V001. |
| 0.1.0 | 2026-11-09 | Guía inicial de PostgreSQL. |
