# 04 · Prompt inicial del dashboard

```text
Construye el frontend de "SignalLoop · Adaptive Device Intelligence", un dashboard de monitoreo de incidentes de dispositivos (ATM/XFS, redes/SNMP, IoT/MQTT) que además muestra cómo el sistema aprende y crea versiones sucesoras. Stack: React + Vite + TypeScript, Tailwind CSS, Leaflet con OpenStreetMap, Recharts solo si hace falta. Debe correr en macOS y Windows con `npm install && npm run dev`.

ESTILO (marca SignalLoop, ver signalloop_brand.png)
- Sobrio y minimalista, monocromático. Paleta: #000000 principal (marca, texto, botón primario), #374151 secundario (contenido), #9CA3AF soporte (bordes, iconos, texto tenue), #F9FAFB fondo (superficies). Tarjetas blancas con borde 1px #E5E7EB, radios grandes (12–16px), sin sombras fuertes ni degradados.
- Tipografía Inter: títulos en peso 700 y tracking ajustado, cuerpo 400/500. Jerarquía con tamaño y peso, no con color.
- Header: logo SignalLoop (símbolo de bucle con barras de señal) + separador vertical + "Adaptive Device Intelligence" en #374151. Usa /public/signalloop-logo.svg (placeholder si no existe).
- El único color fuera de la escala de grises es el de estado, en tonos apagados: verde #16A34A (normal), ámbar #D97706 (advertencia), rojo #DC2626 (crítico), azul #2563EB (resuelto por regla aprendida). Úsalo en puntos, chips y bordes, no en fondos grandes.
- Motivo visual opcional: líneas onduladas finas en gris claro (#E5E7EB) de fondo en el header, como en el branding.
- Flujo del producto como hilo conductor de la navegación: Datos → Transformación → Eventos y Alertas → Inteligencia y Visibilidad.
- Etiquetas pequeñas "SISTEMA" (negro, texto blanco) y "EQUIPO" (borde gris, texto gris) para distinguir quién hizo cada acción.
- Iconos de línea (lucide-react), animaciones mínimas. Modo oscuro: fondo #000000, superficies #111827, texto #F9FAFB.

LAYOUT (una pantalla, desktop primero)
1. Barra superior: KPIs (incidentes abiertos, cerrados hoy, reglas aprendidas, reglas reutilizadas), selector de versión del sistema (v0 / v1 / v2) y una línea de resumen generada por el agente.
2. Centro: mapa con dispositivos coloreados por estado; incidentes regionales como círculos sombreados; capa activable de eventos de contexto (sismo, lluvia, calor, asalto).
3. Panel derecho "En vivo": feed de eventos e incidentes, filtros por source y severidad. Tarjeta "Fuentes no registradas" con botones Agregar / Ignorar.
4. Abajo: línea de tiempo de incidentes con apertura, cierre y causa de cierre (closed_by).
5. Panel lateral "Learning Lab" al seleccionar un incidente: señal de falla, evidencias, lección, regla propuesta (JSON), resultado de pruebas, versión creada.
6. Vista "Evolución": tabla y gráfica de métricas v0 vs v1 vs v2 sobre los mismos casos y sobre casos held-out, más lista de "qué sigue fallando".

DATOS (mocks en /src/mocks; capa /src/api que luego apunta a FastAPI vía VITE_API_URL)
- Event: { event_id, device_id, ts, source, protocol, type: "status"|"operation"|"alert"|"context", code, severity, attributes }
- Device: { device_id, source, region, site_type, placement, geo: {lat, lon}, status }
- Incident: { id, device_ids, opened_at, closed_at, closed_by, severity, summary, rule_id?, rule_version?, decided_by: "system"|"team" }
- Rule: { id, version, kind: "sequence"|"threshold"|"flapping"|"trend"|"lifecycle", params, status, parent_version, lesson_id, tests: {passed, failed} }
- Lesson: { lesson_id, round, signal, evidence[], hypothesis, proposed_rule, validation, created_by }
- EvalReport: { versions: [{ version, cases, metrics: {detected_before_failure, false_positives, duplicate_tickets, avg_minutes_open_after_replenish, heldout_solved_pct} }], still_failing: [] }
El feed en vivo simula eventos cada 2–3 s, preparado para WebSocket.

ENTREGA
Componentes pequeños y tipados, sin librerías de UI pesadas. Prioridad: Learning Lab y vista Evolución, luego feed en vivo, mapa, resumen y línea de tiempo. README corto con cómo correrlo.
```
