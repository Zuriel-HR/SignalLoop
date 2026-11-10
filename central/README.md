# SignalLoop Central

Servicio Quarkus de la Central. PostgreSQL 18 y el esquema V001. Si la base ya fue restaurada, Flyway la toma como versión 1 y no vuelve a crear las tablas. Una base vacía sí recibe el script al arrancar.

La secuencia que se puede guardar y leer hoy está definida en `docs/spec/SPEC-0002-signal-sequence.md`: logs de un ATM ordenados por tiempo. El outcome esperado se guarda en otro endpoint y no sale en esa lectura.

También está disponible el ciclo runtime del SPEC-0003: Intérprete, Evaluador, Aprendiz, Compuerta y Constructor. El modo determinista permanece como opción predeterminada. El modo IA usa OpenAI Responses API para Intérprete y Aprendiz; la Evaluación, Compuerta y Constructor siguen siendo deterministas. El intérprete recibe señales y snapshot; outcomes esperados solo se usan para entrenar/evaluar fuera de la clasificación. La Compuerta de esta demo acepta si mejora al menos un caso de validation y no produce regresiones; es un criterio inicial, no una prueba estadística.

## Arranque

Crea la base `signalloop` y define las variables de PostgreSQL en el entorno del proceso.

```env
SIGNALLOOP_DB_JDBC_URL=jdbc:postgresql://localhost:5432/signalloop
SIGNALLOOP_DB_USERNAME=postgres
SIGNALLOOP_DB_PASSWORD=
```

```powershell
$env:JAVA_HOME="C:\Users\zurie\.jdks\temurin-21.0.12.1"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd quarkus:dev
```

`GET /health/ready` comprueba PostgreSQL. La API escucha en http://localhost:8080.

## Modo IA local

Central lee `OPENAI_API_KEY` del entorno; no lo guardes en el repositorio ni lo imprimas. En macOS, abre una terminal que cargue tu `~/.zshrc` y verifica solo su presencia:

```bash
source ~/.zshrc
test -n "$OPENAI_API_KEY" && echo 'OPENAI_API_KEY configurada'
export SIGNALLOOP_AI_ENABLED=true
export OPENAI_MODEL_ID=gpt-6-luna
export OPENAI_INTERPRETER_REASONING=low
export OPENAI_LEARNER_REASONING=medium
```

Desde `central/`, arranca con `./mvnw quarkus:dev`. La ruta `GET /api/v1/ai/status` muestra si IA está habilitada y si la clave está configurada, sin devolver la clave. Para volver al pipeline determinista, usa `SIGNALLOOP_AI_ENABLED=false`.

El modo IA crea una pista de evaluación separada (`six-case-multisource-learning-ai` y `SignalLoop Interpreter AI`) en las tablas existentes. Registra modelo, ID de respuesta, tokens y latencia en metadatos de las salidas y propuestas. Al evaluar una candidata, conserva la salida baseline en casos que la regla nueva no modifica para que la variación del modelo no se cuente como efecto de aprendizaje. La clasificación puede proponer nuevas categorías y campos; no altera automáticamente el catálogo ni el esquema SQL. La Compuerta sigue siendo determinista y solo las lecciones que mejoran validation sin regresiones se heredan.

## Experimento local: fuente desconocida

La prueba opt-in añade dos agentes experimentales: detección de fuente y perfilado de incidentes/campos. No modifica el esquema, catálogos ni datos de PostgreSQL. Enciéndela solo en desarrollo:

```bash
export SIGNALLOOP_AI_SOURCE_DISCOVERY_EXPERIMENT=true
```

Con Central iniciado, envía eventos sintéticos de un protocolo nuevo (ejemplo Modbus RTU):

```bash
curl -X POST 'http://localhost:8080/api/v1/ai/source-discovery' \
  -H 'Content-Type: application/json' \
  -d '{"scenario_key":"synthetic-modbus-hvac-v1","events":[
    {"event_id":"MODBUS-001","protocol":"MODBUS_RTU","device_id":"AHU-17","message":"Slave 17 register 40112 reports supply air temperature 68.5 C; configured high threshold is 65 C.","attributes":{"slave_id":17,"register_address":40112,"register_value":68.5,"unit":"C","alarm_code":"HVAC_TEMP_HIGH","function_code":3}},
    {"event_id":"MODBUS-002","protocol":"MODBUS_RTU","device_id":"AHU-17","message":"Read coil 00008 returned OFF while supply fan command remains ON.","attributes":{"slave_id":17,"coil_address":8,"coil_state":"OFF","alarm_code":"HVAC_FAN_FEEDBACK_MISSING","function_code":1}}
  ]}'
```

La respuesta contiene `source_detection` y, si es desconocida, `incident_profile`: fuente candidata, categoría existente sugerida, severity, mappings hacia campos canónicos, campos nuevos y evidencia. `result_persisted=false`, `schema_modified=false` y `registration_status=proposal_only`: es un experimento, no una incorporación al sistema.

## Banco del master prompt

Carga una sola vez el banco sintético fijo de seis casos (dos por cada fuente, un caso de train y uno de validation):

```bash
/Applications/Postgres.app/Contents/Versions/18/bin/psql -h localhost -U postgres -d signalloop -v ON_ERROR_STOP=1 -f ../db/seeds/S001__master_prompt_learning_bank.sql
```

Obtén el ID del escenario:

```bash
/Applications/Postgres.app/Contents/Versions/18/bin/psql -h localhost -U postgres -d signalloop -Atc "SELECT id FROM simulator.scenarios WHERE scenario_key='master-prompt-learning-bank' AND version=1;"
```

Con Central corriendo, ejecuta los tres ciclos en orden (reemplaza `<SCENARIO_ID>` por el ID anterior):

```bash
curl -X POST 'http://localhost:8080/api/v1/scenarios/<SCENARIO_ID>/learning-cycle?source=XFS'
curl -X POST 'http://localhost:8080/api/v1/scenarios/<SCENARIO_ID>/learning-cycle?source=IOT'
curl -X POST 'http://localhost:8080/api/v1/scenarios/<SCENARIO_ID>/learning-cycle?source=SNMP'
```

Cada ciclo guarda las corridas, salidas por caso, propuesta/evidencia, decisión de la Compuerta y generación. La llamada devuelve el cambio en accuracy de validation; no uses el conjunto train para reportar la compuerta.

## Pruebas

Las pruebas Java usan `signalloop_test`, no `signalloop`, y la recrean al arrancar. Las pruebas de `SignalLoopMixto` son independientes de OpenAI y de PostgreSQL.

```powershell
$env:JAVA_HOME="C:\Users\zurie\.jdks\temurin-21.0.12.1"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd test
```
