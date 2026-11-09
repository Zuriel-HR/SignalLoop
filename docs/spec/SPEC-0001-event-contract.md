---
id: SPEC-0001
title: Contrato de eventos de la Central
status: Draft
version: 0.1.1
owner: SignalLoop API
created: 2026-11-09
updated: 2026-11-09
reviewers: []
depends_on: [RFC-0001, ARC-0001, CTX-01]
supersedes: []
tags: [specification, api, events, json]
---

# SPEC-0001: Contrato de eventos de la Central

## 1. Propósito

Proponer un contrato de intercambio para eventos normalizados y definir las decisiones que bloquean su aceptación. Este documento todavía no es contrato aprobado.

## 2. Antecedentes

Los formatos de origen incluyen eventos XFS, telemetría IoT, métricas tipadas, datos de vehículo y comandos. `context/01_arquitectura_y_contratos.md` ya propone un evento común con `event_id`, `device_id`, `ts`, `source`, `protocol`, `type`, `code`, `severity` y `attributes`.

## 3. Problema

Los mensajes de origen tienen estructuras heterogéneas. La Central necesita metadatos comunes para filtrar y correlacionar, sin perder ni deformar el payload propio de cada protocolo.

## 4. Alcance

- Define el sobre lógico de un evento recibido por la API.
- Define separación entre metadatos normalizados y contenido de fuente.
- Identifica reglas de validación temporal, procesamiento e idempotencia aún por decidir.

## 5. No objetivos

- No define tablas de base de datos ni reglas de detección.
- No determina autenticación, autorización o cuotas HTTP.
- No exige que fuentes externas publiquen directamente el formato normalizado.

## 6. Definiciones

- **Evento de origen:** mensaje original recibido de un dispositivo/simulador.
- **Evento normalizado:** representación interna con campos comunes y `payload` específico.
- **Tipo de evento:** clase semántica, por ejemplo status, operation, alert, telemetry, diagnostic o command.
- **Estado de procesamiento:** estado interno del trabajo, no estado físico reportado por el dispositivo.

## 7. Propuesta / Diseño

### Sobre normalizado propuesto

```json
{
  "event_id": "uuid",
  "occurred_at": "2026-11-09T12:39:00Z",
  "received_at": "2026-11-09T12:39:01Z",
  "device_id": "ATM001",
  "source": "atm",
  "protocol": "xfs",
  "type": "status",
  "code": "CASH_OUT",
  "severity": "info",
  "group": {
    "type": "Status",
    "sensor": "Dispensador",
    "status": "CashOut"
  },
  "payload": {},
  "source_event_id": null,
  "correlation_id": null,
  "schema_version": "1.0"
}
```

### Reglas propuestas

- `event_id` identifica de forma única el evento en SignalLoop; si no viene del emisor, lo asigna la Central.
- `occurred_at` representa tiempo declarado por la fuente en RFC 3339; `received_at` lo asigna la Central. Conversión de epoch requiere configuración explícita de unidad/zona o adaptador específico.
- `source` identifica clase/origen del dispositivo; `protocol` identifica transporte/protocolo. Catálogos canónicos están pendientes.
- `type` describe la semántica; el estado de procesamiento debe guardarse en el registro interno o respuesta, no sobrescribir `group.status`.
- `payload` contiene propiedades específicas sin pérdida; los campos de métricas tipadas pueden normalizarse en una representación derivada sin borrar el original.
- Comandos salientes deben distinguirse de eventos observados y llevar dirección/resultado propios; pendiente de SPEC específica.
- La normalización no puede asumir que nombres como `DM` o `Bal` signifiquen denominación y saldo sin confirmación del productor.
- Eventos inválidos deben recibir error estructurado y no ejecutar reglas de negocio.

### Campos XFS de Marcos

`Origen` se mapea tentativamente a `protocol` si describe XFS; `Dispositivo` a `device_id`; `Grupo.Tipo Evento` a `group.type` o `type` según definición; `Grupo.Sensor` a `group.sensor`; `Grupo.Status` a `group.status`; `StatusProcesamiento` a estado interno de procesamiento; `IDProceso` a `source_event_id` solo si identifica el mensaje origen. Estas equivalencias requieren validación del equipo.

### Ejemplo IoT

```json
{
  "device_id": "sensor_temp_04",
  "source": "iot",
  "protocol": "mqtt",
  "type": "telemetry",
  "occurred_at": "2026-11-09T12:39:00Z",
  "payload": {
    "variables": {"temperatura": 23.5, "humedad": 42.1, "presion_hpa": 1013.2},
    "bateria_porcentaje": 88.5,
    "estado": "OK"
  },
  "schema_version": "1.0"
}
```

### API inicial a especificar

- `POST /api/v1/events`: recibe uno o varios eventos; tamaño de lote y respuesta parcial/atómica pendientes.
- `GET /health/live` y `GET /health/ready`: estado de proceso y dependencias esenciales; contratos de respuesta pendientes.
- Consulta de eventos/incidentes se define en otra especificación para no mezclar escritura y lectura en este documento.

### Estado frente a V001

`db/migrations/V001__initial_schemas.sql` no tiene tabla de eventos normalizados. La primera secuencia persistida está definida en `docs/spec/SPEC-0002-signal-sequence.md` sobre `simulator.logs`. Este sobre sigue en borrador y no es el contrato de esos endpoints.

## 8. Alternativas consideradas

- Un único JSON arbitrario: flexible, pero dificulta validación, filtros y evolución.
- Esquema rígido universal sin payload extensible: fácil de consultar pero acopla a todos los productores y pierde semántica específica.
- Sobre común + payload preservado: propuesto para equilibrar consultas comunes y heterogeneidad.

## 9. Riesgos

- El mismo término (`Origen`, `Tipo Evento`, `Status`) puede tener significados distintos entre productores.
- Fechas numéricas sin unidad explícita pueden interpretarse erróneamente.
- JSON flexible puede dificultar índices y validación si no se define catálogo de campos comunes.
- La idempotencia requiere definir qué clave suministra cada fuente y por cuánto tiempo se retiene.

## 10. Preguntas abiertas

1. ¿Nombres wire en inglés o español? ¿La API traduce sobre JSON de origen?
2. ¿`Origen` del ejemplo XFS significa protocolo, sistema productor o ambos?
3. ¿`IDProceso` identifica un proceso, evento, lote u operación?
4. ¿Qué significan `DM` y `Bal` en inventario?
5. ¿`StatusProcesamiento` lo asigna el emisor o es exclusivamente estado interno?
6. ¿Los mensajes pueden incluir varios grupos/tipos de evento? ¿Se separan al ingresar?
7. ¿Qué formatos de epoch y zona horaria deben aceptarse?
8. ¿Cuáles son los límites de lote, tamaño, retención, respuesta e idempotencia?
9. ¿El esquema conserva mensaje crudo para auditoría y replay?

## 11. Documentos relacionados

- `docs/rfc/RFC-0001-central-quarkus.md`
- `docs/architecture/ARC-0001-central-modular.md`
- `context/01_arquitectura_y_contratos.md`
- `context/02_ciclo_de_aprendizaje_y_evaluacion.md`

## 12. Changelog

| Versión | Fecha | Cambio |
|---|---|---|
| 0.1.1 | 2026-11-09 | Aclara que V001 no implementa este sobre; la secuencia persistida es SPEC-0002. |
| 0.1.0 | 2026-11-09 | Primer borrador del sobre común y preguntas de contrato. |
