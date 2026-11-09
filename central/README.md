# SignalLoop Central

Servicio Quarkus de la Central. PostgreSQL 18 y el esquema V001. Si la base ya fue restaurada, Flyway la toma como versión 1 y no vuelve a crear las tablas. Una base vacía sí recibe el script al arrancar.

La secuencia que se puede guardar y leer hoy está definida en `docs/spec/SPEC-0002-signal-sequence.md`: logs de un ATM ordenados por tiempo. El outcome esperado se guarda en otro endpoint y no sale en esa lectura.

## Arranque

Crea la base `signalloop` y define las variables. El archivo local es `central/.env`.

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

## Pruebas

Las pruebas usan `signalloop_test`, no `signalloop`, y la recrean al arrancar.

```powershell
$env:JAVA_HOME="C:\Users\zurie\.jdks\temurin-21.0.12.1"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd test
```
