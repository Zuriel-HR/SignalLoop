---
id: AGENTS-README
title: Catálogo de agentes documentales de SignalLoop
status: Draft
version: 0.1.0
owner: SignalLoop Architecture
created: 2026-11-09
updated: 2026-11-09
reviewers: []
depends_on: [RFC-0001, AGENT-TEMPLATE-ADI]
supersedes: []
tags: [agents, governance, documentation]
---

# Catálogo de agentes documentales de SignalLoop

## 1. Propósito

Indexar instrucciones `.md` para trabajadores humanos o de IA que contribuyan a la Central. Cada fichero es un contrato de trabajo, no implica que exista un proceso autónomo en runtime.

## 2. Antecedentes

Estas fichas son contratos documentales para quienes diseñan, implementan y revisan SignalLoop. No son agentes runtime ni configuran proveedores/modelos. El repositorio no contiene actualmente `.augura/AGENT_TEMPLATE.md`, `.augura/AKB-0001-Knowledge-Standard.md` ni `.augura/ROOT_MEMORY.md`; los perfiles deben usar los RFC, ARC, SPEC y documentos de contexto versionados que sí están presentes.

## 3. Problema

Agentes sin límites explícitos pueden duplicar decisiones, aprobar sus propias propuestas o implementar antes de aceptar especificaciones.

## 4. Alcance

Define índice, orden recomendado de colaboración y estado actual de las fichas.

## 5. No objetivos

- No configura runtime multiagente ni proveedores LLM.
- No concede a los agentes autoridad para cambiar documentos fundacionales.

## 6. Definiciones

- **Agente documental:** instrucciones persistentes para acotar la tarea de un trabajador.
- **Agente runtime:** componente de software que ejecuta inferencia u otras acciones durante operación. Requiere una especificación independiente.

## 7. Propuesta / Diseño

### Agentes

| ID | Fichero | Misión primaria | No es autoridad de |
|---|---|---|---|
| AG-00 | `AG-00-central-orchestrator.md` | Coordinar flujo, contratos y trazabilidad | Aprobar arquitectura/activación de reglas |
| AG-01 | `AG-01-event-contract-steward.md` | Revisar ingesta y normalización contra SPEC de eventos | Cambiar unilateralmente contrato aceptado |
| AG-02 | `AG-02-incident-investigator.md` | Construir hipótesis sustentadas por evidencia | Activar reglas o inventar hechos faltantes |
| AG-03 | `AG-03-rule-engineer.md` | Proponer reglas declarativas y casos de validación | Aprobar su propia regla o ejecutar código generado |
| AG-04 | `AG-04-validation-auditor.md` | Verificar pruebas, evidencia, versionado y seguridad de propuestas | Convertir propuesta fallida en regla activa |

### Relación con los componentes runtime

Los perfiles de arriba ayudan al equipo a construir y revisar el sistema. El diagrama de arquitectura mínima describe componentes runtime distintos:

| Componente runtime | Responsabilidad | Perfil documental que puede apoyar | Límite de autoridad |
|---|---|---|---|
| Simulador / contrato de eventos | Produce XFS, IoT y SNMP; la ingesta valida y persiste el evento | AG-01 | El steward propone mappings; no cambia por sí mismo el contrato aceptado |
| Intérprete versionado | Clasifica eventos y abre/cierra tickets según el snapshot activo | AG-01 para el contrato; AG-02 para analizar fallas | El perfil documental no ejecuta ni activa reglas |
| Evaluación / banco | Compara la salida con outcomes y conserva resultados por caso | AG-04 para auditoría | El evaluador runtime debe permanecer separado de la clasificación |
| Aprendiz | Usa fallas y retroalimentación del Help Desk para proponer una lección con evidencia | AG-02 aporta análisis; AG-03 redacta la candidata | Ninguno de los dos activa la candidata |
| Compuerta | Acepta una candidata solo si mejora el banco sin regresiones | AG-04 revisa la evidencia | La decisión runtime es determinista; AG-04 no sustituye la compuerta |
| Constructor | Crea el snapshot sucesor, hereda lecciones aceptadas y adjunta pruebas | No hay perfil documental dedicado | No puede editar el banco ni la compuerta |

La progresión de demo del master prompt es V0/XFS → V1/IoT → V2/SNMP, con lecciones de duplicación, silencio de heartbeat e intermitencia. La arquitectura y los datos runtime se describen en `docs/spec/SPEC-0003-versioned-interpreter.md`; las fichas AG no representan ese ciclo como cinco agentes autónomos.

### Secuencia recomendada para trabajo documental

`AG-00` recibe tarea → `AG-01` inspecciona contrato si hay eventos → procesamiento determinista del dominio → `AG-02` analiza señal si aplica → `AG-03` propone lección/regla/pruebas → `AG-04` revisa evidencia y criterios → responsable humano revisa decisiones estratégicas y cualquier aprobación operativa requerida. Esta secuencia organiza el trabajo del equipo; no es el pipeline runtime del diagrama.

### Cómo aplicar plantilla

Cada ficha usa la plantilla versionada `AGENT-TEMPLATE-ADI.md`: identidad, misión, autoridad, contexto, principios, salida, escalamiento, riesgos, preguntas, referencias y changelog. Si una futura ficha requiere conocimiento que no está en el repositorio, debe señalarlo como faltante en vez de citar una ruta inexistente.

## 8. Alternativas consideradas

- Un único prompt maestro: menor número de documentos, pero mezcla responsabilidades y permisos.
- Agentes por proveedor/modelo: acopla misión a tecnología y replica reglas.
- Fichas pequeñas por capacidad con un coordinador: propuesta por límites revisables y reemplazables.

## 9. Riesgos

- Si los documentos no se cargan en el entorno de trabajo, el contrato no se aplica automáticamente.
- La palabra “agente” puede confundirse con un agente runtime; debe conservarse la definición.
- Los borradores de RFC/SPEC limitan el alcance de estas fichas; no deben tomarse como contrato aceptado.

## 10. Preguntas abiertas

- ¿Qué persona revisará/aceptará las fichas documentales?
- ¿Qué contratos de entrada/salida tendrá el Constructor runtime al crear un snapshot sucesor?
- ¿El flujo runtime será determinista en esta demo o incluirá un proveedor LLM como fallback para señales desconocidas?

## 11. Documentos relacionados

- `docs/agents/AGENT-TEMPLATE-ADI.md`
- `docs/spec/SPEC-0003-versioned-interpreter.md`
- `docs/rfc/RFC-0001-central-quarkus.md`
- `docs/architecture/ARC-0001-central-modular.md`

## 12. Changelog

| Versión | Fecha | Cambio |
|---|---|---|
| 0.1.0 | 2026-11-09 | Catálogo inicial de fichas documentales. |
