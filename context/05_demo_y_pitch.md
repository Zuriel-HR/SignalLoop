# 05 · Demo (3 min) y preguntas (2 min)

## Guion

| Tiempo | Qué se dice / muestra |
|---|---|
| 0:00–0:25 | **SignalLoop** — "cada señal cierra un ciclo de aprendizaje". **Impacto**: operación de cajeros y help desk recibe alertas rígidas, duplicadas y tickets que no cierran. Beneficiarios: centro de monitoreo y técnicos de campo. |
| 0:25–1:10 | **Falla en v0**: corre escenario XFS + cash out. v0 no anticipa la falla y deja el ticket abierto tras la recarga. Señal marcada en rojo. |
| 1:10–2:00 | **Decisión del sistema** (Learning Lab): evidencias → lección → regla JSON → pytest en verde → nace v1. Etiquetas SISTEMA vs EQUIPO visibles. |
| 2:00–2:40 | **Antes y después**: mismos casos v0 vs v1, y caso held-out (SNMP flapping o IoT tendencia) donde se reutiliza lo aprendido. Si hay v2, mostrar qué se acumuló. |
| 2:40–3:00 | **Qué sigue fallando** + arquitectura Generador → Intérprete → Integrador como siguiente paso. Cómo usamos Codex en una frase. |

## Preguntas probables

- **¿Cómo evitan que la IA aprenda reglas malas?** La regla es JSON declarativo y solo se aprueba si pasa pytest en escenarios normales y anómalos.
- **¿Qué pasa con casos no vistos?** Mostramos held-out y lo que todavía falla.
- **¿Qué trajeron de antes?** _(completar: p. ej. PostgreSQL existente)_.
- **¿Funciona con datos reales?** El contrato de `POST /ingest` es el mismo; hoy usamos simuladores para que sea reproducible.
- **¿Cómo usaron Codex?** _(ver bitácora)_.

## Checklist antes de presentar

- [ ] Demo corre dos veces seguidas con la misma semilla
- [ ] `evals/report.json` generado por pruebas reales
- [ ] Video de respaldo grabado
- [ ] Base previa declarada
- [ ] Bitácora con lecciones y uso de Codex completa
