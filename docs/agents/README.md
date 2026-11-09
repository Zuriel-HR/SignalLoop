---
id: AGENTS-README
title: Catálogo de agentes documentales de SignalLoop
status: Draft
version: 0.1.0
owner: SignalLoop Architecture
created: 2026-11-09
updated: 2026-11-09
reviewers: []
depends_on: [AKB-0001, RFC-0001, AGENT-TEMPLATE-ADI]
supersedes: []
tags: [agents, governance, documentation]
---

# Catálogo de agentes documentales de SignalLoop

## 1. Propósito

Indexar instrucciones `.md` para trabajadores humanos o de IA que contribuyan a la Central. Cada fichero es un contrato de trabajo, no implica que exista un proceso autónomo en runtime.

## 2. Antecedentes

La plantilla de origen es `.augura/AGENT_TEMPLATE.md`; el AKB requiere front matter común y documentos de una sola responsabilidad. Los documentos de agente de este directorio conservan identidad, misión, autoridad, contexto obligatorio, principios, contrato de salida y escalamiento de esa plantilla.

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

### Secuencia recomendada

`AG-00` recibe tarea → `AG-01` inspecciona contrato si hay eventos → procesamiento determinista del dominio → `AG-02` analiza señal si aplica → `AG-03` propone lección/regla/pruebas → `AG-04` valida evidencia y criterios → responsable humano revisa decisiones estratégicas y cualquier aprobación operativa requerida. La implementación solo comienza con especificación aceptada.

### Cómo aplicar plantilla

Cada ficha debe cumplir las secciones numeradas 1–7 de `.augura/AGENT_TEMPLATE.md` y los requisitos de front matter/secciones del AKB-0001. La sección 6 contractualmente solicita objetivo, conocimiento, acciones, archivos, pruebas, preguntas, riesgos y próximo paso. Las secciones 8–12 añaden riesgos, preguntas, referencias y changelog. Todas las fichas presentes usan esa forma.

## 8. Alternativas consideradas

- Un único prompt maestro: menor número de documentos, pero mezcla responsabilidades y permisos.
- Agentes por proveedor/modelo: acopla misión a tecnología y replica reglas.
- Fichas pequeñas por capacidad con un coordinador: propuesta por límites revisables y reemplazables.

## 9. Riesgos

- Si los documentos no se cargan en el orden indicado por el entorno de trabajo, el contrato no se aplica automáticamente.
- La palabra “agente” puede confundirse con un agente runtime; debe conservarse la definición.
- Los borradores de RFC/SPEC limitan el alcance de estas fichas; no deben tomarse como contrato aceptado.

## 10. Preguntas abiertas

- ¿Qué ejecutor leerá estas fichas: Codex, Cursor, otro entorno o agentes del backend en runtime?
- ¿Se requiere plantilla en cada prompt runtime o una política compartida inyectada por el orquestador?
- ¿Qué persona revisará/aceptará cada ficha?

## 11. Documentos relacionados

- `.augura/AGENT_TEMPLATE.md`
- `.augura/AKB-0001-Knowledge-Standard.md`
- `docs/rfc/RFC-0001-central-quarkus.md`
- `docs/architecture/ARC-0001-central-modular.md`

## 12. Changelog

| Versión | Fecha | Cambio |
|---|---|---|
| 0.1.0 | 2026-11-09 | Catálogo inicial de fichas documentales. |
