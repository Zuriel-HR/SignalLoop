# SignalLoop · Adaptive Device Intelligence · RSI Hackathon

Sistema de agentes que monitorea eventos de dispositivos (ATM/XFS, redes/SNMP, IoT/MQTT), aprende de sus ejecuciones y usa lo aprendido para construir una **versión sucesora** que resuelve mejor los mismos casos y casos nuevos.

> La IA propone el conocimiento; las pruebas lo validan; el sistema lo guarda, lo versiona y lo reutiliza.

## Documentos

| Archivo | Contenido |
|---|---|
| `01_arquitectura_y_contratos.md` | Generador → Intérprete → Integrador, tipos de mensaje, esquemas JSON, cash out, perfiles de dispositivo |
| `02_ciclo_de_aprendizaje_y_evaluacion.md` | Cómo se detecta una falla, cómo se guarda la lección, cómo se construye el sucesor y cómo se mide |
| `03_plan_8h_y_roles.md` | Reparto del equipo y cronograma |
| `04_prompt_dashboard.md` | Prompt inicial para construir el dashboard (estilo sobrio y minimalista) |
| `05_demo_y_pitch.md` | Guion de 3 minutos, preguntas probables y checklist |
| `signalloop_brand.png` | Branding: logo, paleta (#000000, #374151, #9CA3AF, #F9FAFB), Inter y flujo del producto |
| `06_bitacora_rsi.md` | Plantilla para documentar en vivo: versiones, lecciones, uso de Codex e intervenciones del equipo |

## Mapa contra la rúbrica

| Criterio (25 % c/u) | Cómo lo cubrimos | Evidencia en la demo |
|---|---|---|
| **1. Impacto** | Operación de cajeros y help desk: menos tickets duplicados, cierre automático de cash out, menos tiempo de revisión | Métricas v0 vs v1 vs v2 sobre los mismos casos + casos reservados (held-out) |
| **2. Aplicación de Codex** | Codex construye simuladores, tests y componentes; además el Rule Engineer genera reglas que Codex/pytest validan | Sección "Uso de Codex" en la bitácora con ejemplos concretos |
| **3. Uso creativo de habilidades** | Señal de falla → lección → regla versionada → sucesor | Lección concreta (p. ej. "la recarga cierra el cash out") que cambia el comportamiento |
| **4. Demostración** | Falla → decisión del sistema → resultado de la siguiente versión, separando acciones del sistema vs del equipo | Panel "Learning Lab" + diff de versiones + etiquetas `SISTEMA` / `EQUIPO` |

## Marca

**SignalLoop** · Adaptive Device Intelligence. El bucle del logo representa el ciclo RSI: la señal entra, el sistema aprende y vuelve mejorado.
Flujo del producto: **Datos** (multifuente) → **Transformación** (enriquecimiento y normalización) → **Eventos y Alertas** (detección en tiempo real) → **Inteligencia y Visibilidad** (operación y análisis).
