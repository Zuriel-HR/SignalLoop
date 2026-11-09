"""Deterministic synthetic ATM incidents for the SignalLoop hackathon."""
from __future__ import annotations

import hashlib
import json
import random
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional, Tuple
from uuid import UUID, uuid4

from psycopg import Connection
from psycopg.types.json import Jsonb


FAULTS: List[Dict[str, Any]] = [
    {"code": "SIM_XFS_CDM_DISPENSE_JAM", "subsystem": "XFS", "category": "hardware", "severity": "P1", "cause": "Cash dispenser reports a jammed note path", "check": "Inspect dispenser status and cash-unit counters", "action": "Keep the ATM out of service and dispatch field support", "signal": "XFS CDM dispense request returned a device error"},
    {"code": "SIM_XFS_CDM_CASHUNIT_LOW", "subsystem": "XFS", "category": "hardware", "severity": "P2", "cause": "A cash unit is below its operational threshold", "check": "Read cash-unit inventory and replenishment threshold", "action": "Schedule a cassette replenishment", "signal": "XFS CDM cash-unit level is below threshold"},
    {"code": "SIM_XFS_CIM_RETRACT_BIN_FULL", "subsystem": "XFS", "category": "hardware", "severity": "P1", "cause": "The retract bin is full and deposits cannot complete", "check": "Inspect retract-bin capacity and recent reject events", "action": "Stop deposit service and dispatch a technician", "signal": "XFS CIM reports retract-bin capacity reached"},
    {"code": "SIM_XFS_PINPAD_NOT_READY", "subsystem": "XFS", "category": "hardware", "severity": "P1", "cause": "The PIN pad is unavailable after device initialization", "check": "Review device initialization and secure-channel status", "action": "Disable customer transactions and escalate to device support", "signal": "XFS PIN service remains not-ready after restart"},
    {"code": "SIM_XFS_CARD_READER_CLEANING", "subsystem": "XFS", "category": "hardware", "severity": "P2", "cause": "Card reader sensor detects repeated read failures", "check": "Compare reader retries with the last maintenance record", "action": "Schedule reader cleaning and monitor retry rate", "signal": "XFS IDC reports elevated card-read retries"},
    {"code": "SIM_XFS_DUPLICATE_EVENT", "subsystem": "XFS", "category": "software", "severity": "P2", "cause": "The same XFS event was delivered more than once", "check": "Compare event IDs and delivery timestamps before opening a ticket", "action": "Deduplicate by event ID and retain one incident record", "signal": "The same XFS event ID was received twice"},
    {"code": "SIM_XFS_CASHOUT_PATTERN", "subsystem": "XFS", "category": "fraud", "severity": "P1", "cause": "Withdrawal activity matches a synthetic rapid cash-out pattern", "check": "Review linked dispense attempts and the velocity window", "action": "Preserve the event sequence and route it for fraud review", "signal": "XFS dispense events exceed the configured cash-out velocity threshold"},
    {"code": "SIM_IOT_REFRIGERATOR_TEMP_HIGH", "subsystem": "IOT", "category": "hardware", "severity": "P1", "asset_type": "iot_refrigerator", "cause": "Refrigerator temperature is above the safe range", "check": "Compare temperature trend, door sensor, and compressor state", "action": "Dispatch facilities support and move sensitive stock to a safe unit", "signal": "IoT temperature probe exceeded the configured refrigerator threshold"},
    {"code": "SIM_IOT_REFRIGERATOR_HEARTBEAT_MISSING", "subsystem": "IOT", "category": "hardware", "severity": "P1", "asset_type": "iot_refrigerator", "cause": "Refrigerator stopped reporting its heartbeat", "check": "Check gateway reachability, device power, and the last reported telemetry", "action": "Dispatch facilities support and verify the refrigerator is reporting again", "signal": "No IoT refrigerator heartbeat was received within 60 seconds"},
    {"code": "SIM_IOT_REFRIGERATOR_COMPRESSOR", "subsystem": "IOT", "category": "hardware", "severity": "P2", "asset_type": "iot_refrigerator", "cause": "Refrigerator compressor is cycling abnormally", "check": "Inspect compressor current, duty cycle, and recent temperature changes", "action": "Schedule refrigeration maintenance and monitor temperature", "signal": "IoT compressor telemetry reports repeated short cycles"},
    {"code": "SIM_IOT_BOILER_NO_HEAT", "subsystem": "IOT", "category": "hardware", "severity": "P2", "asset_type": "iot_boiler", "cause": "Boiler is not reaching its target temperature", "check": "Review burner state, water flow, and temperature sensor readings", "action": "Dispatch boiler maintenance and keep the unit under observation", "signal": "IoT boiler outlet temperature remained below target"},
    {"code": "SIM_IOT_BOILER_PRESSURE_HIGH", "subsystem": "IOT", "category": "hardware", "severity": "P1", "asset_type": "iot_boiler", "cause": "Boiler pressure exceeded its operating limit", "check": "Inspect pressure sensor, relief valve, and recent pressure trend", "action": "Place the boiler in a safe state and dispatch facilities support", "signal": "IoT pressure sensor crossed the high-pressure safety threshold"},
    {"code": "SIM_SNMP_ROUTER_LINK_DOWN", "subsystem": "SNMP", "category": "network", "severity": "P1", "asset_type": "snmp_router", "cause": "Branch router interface is down", "check": "Review interface state, error counters, and upstream reachability", "action": "Escalate to network operations and verify branch connectivity", "signal": "SNMP interface status changed to down"},
    {"code": "SIM_SNMP_UPS_BATTERY_LOW", "subsystem": "SNMP", "category": "hardware", "severity": "P1", "asset_type": "snmp_ups", "cause": "UPS battery capacity is below its safe reserve", "check": "Check battery charge, runtime estimate, and recent power events", "action": "Dispatch facilities support and verify backup power availability", "signal": "SNMP UPS battery charge fell below the configured reserve"},
    {"code": "SIM_SNMP_PACKET_LOSS", "subsystem": "SNMP", "category": "network", "severity": "P2", "asset_type": "snmp_network", "cause": "Network packet loss is above the service threshold", "check": "Compare interface discards, errors, and path latency", "action": "Investigate the affected network path and monitor packet loss", "signal": "SNMP interface counters show elevated packet discards"},
    {"code": "SIM_SNMP_ALARM_INTERMITTENT", "subsystem": "SNMP", "category": "network", "severity": "P1", "asset_type": "snmp_ups", "cause": "UPS alarm recurred after a brief recovery", "check": "Correlate alarm, recovery, and acknowledgment events by device and event ID", "action": "Keep one incident open until the UPS is stable and acknowledged", "signal": "SNMP UPS alarm returned shortly after a recovery event"},
    {"code": "SIM_IO_JOURNAL_DISK_FULL", "subsystem": "IO", "category": "software", "severity": "P1", "cause": "The transaction journal volume has no free space", "check": "Check journal filesystem capacity and archive rotation", "action": "Stop new transactions and restore journal capacity", "signal": "I/O writer returned ENOSPC while appending journal record"},
    {"code": "SIM_IO_JOURNAL_WRITE_TIMEOUT", "subsystem": "IO", "category": "software", "severity": "P1", "cause": "Journal writes exceed the transaction timeout", "check": "Inspect storage latency and pending journal writes", "action": "Pause transaction processing and investigate the storage path", "signal": "Journal write exceeded the configured I/O deadline"},
    {"code": "SIM_IO_DEVICE_READ_ERROR", "subsystem": "IO", "category": "hardware", "severity": "P1", "cause": "The storage device returns intermittent read errors", "check": "Review kernel I/O errors and device health counters", "action": "Fail over or replace the affected storage device", "signal": "Kernel reported EIO on the ATM storage device"},
    {"code": "SIM_IO_LOG_ROTATION_STALLED", "subsystem": "IO", "category": "software", "severity": "P2", "cause": "Log rotation is blocked by a stale process", "check": "Check log volume usage and rotation process state", "action": "Clear the stale rotation lock and verify free space", "signal": "Log volume grows while rotation checkpoints remain unchanged"},
    {"code": "SIM_NET_SWITCH_LINK_FLAP", "subsystem": "NETWORK", "category": "network", "severity": "P1", "cause": "The branch switch uplink is repeatedly disconnecting", "check": "Correlate link state changes with switch port events", "action": "Escalate to network operations and maintain safe service state", "signal": "Gateway heartbeat lost during multiple link transitions"},
    {"code": "SIM_NET_AUTH_HOST_TIMEOUT", "subsystem": "NETWORK", "category": "network", "severity": "P1", "cause": "Authorization host responses are timing out", "check": "Compare host latency with the ATM network path", "action": "Escalate to the payments network team and monitor retries", "signal": "Authorization requests time out before host response"},
    {"code": "SIM_NET_DNS_LOOKUP_FAILURE", "subsystem": "NETWORK", "category": "network", "severity": "P2", "cause": "The ATM cannot resolve the configured host name", "check": "Validate DNS resolver reachability and record freshness", "action": "Restore resolver access and retry a controlled health check", "signal": "Host lookup failed for the configured authorization endpoint"},
    {"code": "SIM_NET_TLS_CERT_EXPIRED", "subsystem": "NETWORK", "category": "network", "severity": "P1", "cause": "The secure channel certificate is expired", "check": "Inspect certificate validity and trust-chain status", "action": "Renew the certificate through the approved change process", "signal": "TLS handshake rejected because the peer certificate is expired"},
    {"code": "SIM_CORE_SERVICE_RESTART_LOOP", "subsystem": "CORE", "category": "software", "severity": "P1", "cause": "The transaction service repeatedly exits during startup", "check": "Inspect service restart count and the latest startup trace", "action": "Keep the ATM offline and roll back the last service change", "signal": "Transaction service restarted repeatedly without becoming healthy"},
    {"code": "SIM_CORE_CONFIG_VERSION_MISMATCH", "subsystem": "CORE", "category": "software", "severity": "P2", "cause": "The deployed service and configuration versions do not match", "check": "Compare deployment manifest with active configuration version", "action": "Restore a compatible configuration and verify service health", "signal": "Runtime manifest version differs from loaded configuration"},
    {"code": "SIM_CORE_HEALTHCHECK_DEGRADED", "subsystem": "CORE", "category": "software", "severity": "P2", "cause": "A background transaction dependency is degraded", "check": "Review dependency health checks and recent error rate", "action": "Recover the dependency and re-run the controlled health check", "signal": "Core service is up but dependency health checks are failing"},
    {"code": "SIM_RECON_DISPENSE_JOURNAL_MISMATCH", "subsystem": "RECONCILIATION", "category": "reconciliation", "severity": "P1", "cause": "The dispense journal and host record disagree", "check": "Reconcile transaction ID, dispense result, and EJ entry", "action": "Prevent duplicate customer impact and open a reconciliation case", "signal": "Electronic journal amount differs from host dispense record"},
    {"code": "SIM_RECON_CASH_UNIT_VARIANCE", "subsystem": "RECONCILIATION", "category": "reconciliation", "severity": "P2", "cause": "Physical cash count differs from the recorded cash-unit total", "check": "Compare cassette counters with the last balancing report", "action": "Hold balancing and request an audited cash count", "signal": "Cash-unit counter does not match the reconciliation snapshot"},
    {"code": "SIM_RECON_DUPLICATE_REVERSAL", "subsystem": "RECONCILIATION", "category": "reconciliation", "severity": "P1", "cause": "A reversal was recorded more than once for one transaction", "check": "Trace original and reversal references through the journal", "action": "Stop automated correction and escalate for ledger review", "signal": "Multiple reversal references point to one transaction ID"},
    {"code": "SIM_FRAUD_CASHOUT_PATTERN", "subsystem": "FRAUD", "category": "fraud", "severity": "P1", "cause": "Withdrawal activity matches a synthetic rapid cash-out pattern", "check": "Review linked attempts, card token, and velocity window", "action": "Preserve evidence and route to the fraud response queue", "signal": "Withdrawal velocity exceeds the configured risk threshold"},
    {"code": "SIM_FRAUD_CARD_RETAIN_ANOMALY", "subsystem": "FRAUD", "category": "fraud", "severity": "P1", "cause": "Repeated card-retain events suggest a targeted device pattern", "check": "Compare retain events across cards and neighboring ATMs", "action": "Preserve logs and escalate to fraud operations", "signal": "Unusual card-retain sequence repeats across a short window"},
    {"code": "SIM_FRAUD_TAMPER_SWITCH", "subsystem": "FRAUD", "category": "fraud", "severity": "P1", "cause": "A cabinet tamper input changed while the ATM was active", "check": "Correlate tamper sensor state with service access records", "action": "Secure the device and follow the physical-security escalation", "signal": "Tamper sensor event occurred outside the maintenance window"},
]

FAULT_TEXT_ES = {
    "SIM_XFS_CDM_DISPENSE_JAM": ("Atasco en la ruta de billetes del dispensador", "Revisa el estado del dispensador y los contadores de efectivo", "Mantén el cajero fuera de servicio y envía soporte de campo", "La orden de dispensación CDM de XFS devolvió un error de dispositivo"),
    "SIM_XFS_CDM_CASHUNIT_LOW": ("Una unidad de efectivo está por debajo del nivel operativo", "Consulta el inventario de casetes y el umbral de reposición", "Programa la reposición del casete", "XFS CDM reporta un nivel bajo en la unidad de efectivo"),
    "SIM_XFS_CIM_RETRACT_BIN_FULL": ("El contenedor de rechazos está lleno y no permite completar depósitos", "Inspecciona su capacidad y los rechazos recientes", "Suspende depósitos y envía un técnico", "XFS CIM reporta que el contenedor de rechazos alcanzó su capacidad"),
    "SIM_XFS_PINPAD_NOT_READY": ("El teclado PIN no está disponible después de inicializarse", "Revisa la inicialización del dispositivo y el canal seguro", "Deshabilita transacciones y escala a soporte de dispositivos", "El servicio PIN de XFS sigue sin estar listo después del reinicio"),
    "SIM_XFS_CARD_READER_CLEANING": ("El sensor del lector detecta fallas de lectura repetidas", "Compara los reintentos con el último mantenimiento", "Programa la limpieza del lector y vigila los reintentos", "IDC de XFS reporta un aumento de reintentos de lectura"),
    "SIM_XFS_DUPLICATE_EVENT": ("El mismo evento XFS se recibió más de una vez", "Compara los ID de evento y las marcas de tiempo antes de abrir un ticket", "Deduplica por ID de evento y conserva un solo registro del incidente", "Se recibió dos veces el mismo ID de evento XFS"),
    "SIM_XFS_CASHOUT_PATTERN": ("Los retiros coinciden con un patrón sintético de extracción acelerada", "Revisa intentos de dispensación relacionados y su ventana de velocidad", "Conserva la secuencia de eventos y envíala a revisión de fraude", "Los eventos de dispensación XFS superan el umbral de velocidad de CashOut"),
    "SIM_IOT_REFRIGERATOR_TEMP_HIGH": ("La temperatura del refrigerador superó el rango seguro", "Compara la tendencia de temperatura, el sensor de puerta y el compresor", "Envía soporte de instalaciones y mueve los productos sensibles a una unidad segura", "La sonda IoT superó el umbral configurado del refrigerador"),
    "SIM_IOT_REFRIGERATOR_HEARTBEAT_MISSING": ("El refrigerador dejó de reportar su heartbeat", "Revisa el acceso al gateway, la energía del dispositivo y la última telemetría", "Envía soporte de instalaciones y verifica que el refrigerador vuelva a reportar", "No se recibió heartbeat del refrigerador IoT en 60 segundos"),
    "SIM_IOT_REFRIGERATOR_COMPRESSOR": ("El compresor del refrigerador presenta ciclos anormales", "Revisa corriente, ciclo de trabajo y cambios recientes de temperatura", "Programa mantenimiento de refrigeración y vigila la temperatura", "La telemetría IoT detectó ciclos cortos repetidos del compresor"),
    "SIM_IOT_BOILER_NO_HEAT": ("La caldera no alcanza la temperatura objetivo", "Revisa el quemador, el flujo de agua y las lecturas del sensor", "Envía mantenimiento de caldera y mantén la unidad bajo observación", "La temperatura de salida de la caldera permaneció debajo del objetivo"),
    "SIM_IOT_BOILER_PRESSURE_HIGH": ("La presión de la caldera superó el límite operativo", "Inspecciona el sensor de presión, la válvula de alivio y la tendencia reciente", "Coloca la caldera en estado seguro y envía soporte de instalaciones", "El sensor IoT cruzó el umbral de seguridad por presión alta"),
    "SIM_SNMP_ROUTER_LINK_DOWN": ("La interfaz del router de la sucursal está caída", "Revisa el estado de la interfaz, contadores de error y acceso aguas arriba", "Escala a operaciones de red y verifica la conectividad de la sucursal", "SNMP reportó que el estado de la interfaz cambió a inactivo"),
    "SIM_SNMP_UPS_BATTERY_LOW": ("La batería del UPS está por debajo de la reserva segura", "Revisa carga, autonomía estimada y eventos eléctricos recientes", "Envía soporte de instalaciones y verifica la disponibilidad de energía de respaldo", "SNMP reportó que la carga de batería cayó debajo de la reserva configurada"),
    "SIM_SNMP_PACKET_LOSS": ("La pérdida de paquetes supera el umbral del servicio", "Compara descartes de interfaz, errores y latencia de ruta", "Investiga la ruta de red afectada y vigila la pérdida de paquetes", "Los contadores SNMP muestran descartes elevados en la interfaz"),
    "SIM_SNMP_ALARM_INTERMITTENT": ("La alarma del UPS reapareció después de una recuperación breve", "Correlaciona alarmas, recuperaciones y reconocimientos por dispositivo e ID de evento", "Mantén un solo incidente abierto hasta que el UPS esté estable y reconocido", "La alarma SNMP del UPS reapareció poco después de recuperarse"),
    "SIM_IO_JOURNAL_DISK_FULL": ("El volumen del journal de transacciones se quedó sin espacio", "Revisa el espacio del sistema de archivos y la rotación", "Detén transacciones nuevas y recupera espacio para el journal", "El escritor de I/O devolvió ENOSPC al guardar una entrada del journal"),
    "SIM_IO_JOURNAL_WRITE_TIMEOUT": ("Las escrituras del journal exceden el tiempo límite", "Inspecciona la latencia de almacenamiento y las escrituras pendientes", "Pausa transacciones e investiga la ruta de almacenamiento", "La escritura del journal superó el plazo de I/O configurado"),
    "SIM_IO_DEVICE_READ_ERROR": ("El almacenamiento devuelve errores de lectura intermitentes", "Revisa errores de I/O del kernel y contadores de salud", "Cambia al dispositivo de respaldo o reemplaza el almacenamiento", "El kernel reportó EIO en el almacenamiento del cajero"),
    "SIM_IO_LOG_ROTATION_STALLED": ("La rotación de logs está bloqueada por un proceso obsoleto", "Comprueba el uso del volumen y el estado de la rotación", "Libera el bloqueo obsoleto y verifica el espacio disponible", "El volumen de logs crece mientras la rotación no avanza"),
    "SIM_NET_SWITCH_LINK_FLAP": ("El enlace del switch de la sucursal se desconecta repetidamente", "Correlaciona cambios de enlace con eventos del puerto", "Escala a operaciones de red y conserva el servicio en estado seguro", "Se perdió el heartbeat de gateway durante varios cambios de enlace"),
    "SIM_NET_AUTH_HOST_TIMEOUT": ("Las respuestas del host de autorización están expirando", "Compara la latencia del host con la ruta de red del cajero", "Escala al equipo de pagos y vigila los reintentos", "Las solicitudes de autorización expiran antes de recibir respuesta"),
    "SIM_NET_DNS_LOOKUP_FAILURE": ("El cajero no puede resolver el nombre configurado del host", "Valida el acceso al resolver DNS y la vigencia del registro", "Restablece el acceso DNS y ejecuta una verificación controlada", "Falló la búsqueda DNS del endpoint de autorización"),
    "SIM_NET_TLS_CERT_EXPIRED": ("El certificado del canal seguro está vencido", "Inspecciona la vigencia del certificado y la cadena de confianza", "Renueva el certificado mediante el proceso aprobado", "El handshake TLS fue rechazado porque el certificado venció"),
    "SIM_CORE_SERVICE_RESTART_LOOP": ("El servicio de transacciones termina repetidamente al iniciar", "Revisa el contador de reinicios y la última traza de inicio", "Mantén el cajero fuera de línea y revierte el último cambio", "El servicio se reinició varias veces sin quedar saludable"),
    "SIM_CORE_CONFIG_VERSION_MISMATCH": ("La versión desplegada no coincide con su configuración", "Compara el manifiesto con la versión activa de configuración", "Restaura una configuración compatible y verifica el servicio", "La versión del manifiesto difiere de la configuración cargada"),
    "SIM_CORE_HEALTHCHECK_DEGRADED": ("Una dependencia del servicio de transacciones está degradada", "Revisa sus health checks y los errores recientes", "Recupera la dependencia y repite la verificación", "El servicio central responde, pero falla una comprobación de dependencia"),
    "SIM_RECON_DISPENSE_JOURNAL_MISMATCH": ("El journal de dispensación no coincide con el registro del host", "Concilia el ID de transacción, la dispensación y la entrada EJ", "Evita impactos duplicados al cliente y abre un caso de conciliación", "El importe del journal electrónico difiere del registro del host"),
    "SIM_RECON_CASH_UNIT_VARIANCE": ("El conteo físico difiere del total registrado en las unidades de efectivo", "Compara los contadores de casete con el último reporte de balance", "Pausa el balance y solicita un conteo auditado", "El contador no coincide con la captura de conciliación"),
    "SIM_RECON_DUPLICATE_REVERSAL": ("Se registró más de una reversa para una transacción", "Traza las referencias de la transacción original y sus reversas", "Detén la corrección automática y escala para revisar el libro", "Varias referencias de reversa apuntan al mismo ID de transacción"),
    "SIM_FRAUD_CASHOUT_PATTERN": ("Los retiros coinciden con un patrón sintético de extracción acelerada", "Revisa intentos relacionados, token de tarjeta y ventana de velocidad", "Conserva la evidencia y enruta el caso a respuesta de fraude", "La velocidad de retiros supera el umbral de riesgo configurado"),
    "SIM_FRAUD_CARD_RETAIN_ANOMALY": ("Hay retenciones de tarjeta repetidas con un patrón inusual", "Compara eventos entre tarjetas y cajeros cercanos", "Conserva los logs y escala a operaciones de fraude", "Una secuencia inusual de retenciones se repite en una ventana corta"),
    "SIM_FRAUD_TAMPER_SWITCH": ("El sensor del gabinete cambió mientras el cajero estaba activo", "Correlaciona el sensor con los registros de acceso de servicio", "Asegura el dispositivo y sigue la escalación de seguridad física", "El sensor registró un evento fuera de la ventana de mantenimiento"),
}
for fault in FAULTS:
    fault["cause"], fault["check"], fault["action"], fault["signal"] = FAULT_TEXT_ES[fault["code"]]

ATMS: List[Dict[str, str]] = [
    {"location": "CDMX-CENTRO", "city": "Ciudad de México", "vendor": "NCR", "model": "SelfServ 80"},
    {"location": "GDL-MINERVA", "city": "Guadalajara", "vendor": "Diebold Nixdorf", "model": "DN Series 200"},
    {"location": "MTY-OBISPADO", "city": "Monterrey", "vendor": "Hyosung", "model": "MX5300"},
    {"location": "PUE-ANGELÓPOLIS", "city": "Puebla", "vendor": "NCR", "model": "SelfServ 84"},
    {"location": "QRO-JURIQUILLA", "city": "Querétaro", "vendor": "Diebold Nixdorf", "model": "DN Series 100"},
    {"location": "TOL-METEPEC", "city": "Toluca", "vendor": "Hyosung", "model": "MX5300"},
]

ASSETS: List[Dict[str, str]] = [
    {"asset_type": "iot_refrigerator", "location": "CDMX-CENTRO", "vendor": "FríoTec", "model": "FT-IoT 400"},
    {"asset_type": "iot_boiler", "location": "CDMX-CENTRO", "vendor": "TermoMex", "model": "TM-B IoT 80"},
    {"asset_type": "snmp_router", "location": "GDL-MINERVA", "vendor": "Cisco", "model": "ISR 1100"},
    {"asset_type": "snmp_ups", "location": "GDL-MINERVA", "vendor": "APC", "model": "Smart-UPS 1500"},
    {"asset_type": "snmp_network", "location": "MTY-OBISPADO", "vendor": "Cisco", "model": "Catalyst 9200"},
]

def split_counts(count: int) -> Dict[str, int]:
    train = int(count * 0.5)
    validation = int(count * 0.25)
    return {"train": train, "validation": validation, "test": count - train - validation}


def _insert_case(conn: Connection, scenario_id: UUID, atm_id: Optional[UUID], asset_id: Optional[UUID], ticket: Dict[str, Any], rng: random.Random) -> Tuple[UUID, Dict[str, Any]]:
    ticket_id = conn.execute(
        """INSERT INTO simulator.tickets
           (scenario_id, ticket_key, atm_id, asset_id, occurred_at, category, severity, title, description, customer_impact, split, metadata)
           VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING id""",
        (scenario_id, ticket["ticket_key"], atm_id, asset_id, ticket["occurred_at"], ticket["fault"]["category"], ticket["severity"], ticket["title"], ticket["description"], ticket["impact"], ticket["split"], Jsonb(ticket["metadata"])),
    ).fetchone()["id"]
    fault = ticket["fault"]
    conn.execute(
        """INSERT INTO simulator.expected_outcomes
           (ticket_id, root_cause, expected_category, expected_severity, expected_next_action, rubric)
           VALUES (%s,%s,%s,%s,%s,%s)""",
        (ticket_id, fault["cause"], fault["category"], ticket["severity"], fault["action"], Jsonb({"category_weight": 0.5, "severity_weight": 0.5, "p1_miss_score": 0})),
    )

    occurred = ticket["occurred_at"]
    log_rows = []
    for event_number, (offset, level, message, detail) in enumerate(ticket["log_events"], start=1):
        attributes = {
            **detail,
            "event_id": detail.get("event_id", ticket["ticket_key"] + "-EVT-" + str(event_number).zfill(2)),
            "origen": detail["subsystem"],
            "dispositivo": ticket["entity"]["key"],
            "tipo_evento": detail.get("tipo_evento", "Alarma"),
        }
        log_rows.append((scenario_id, atm_id, asset_id, ticket_id, occurred + timedelta(minutes=offset), level, detail["subsystem"], detail["code"], message, Jsonb(attributes)))
    with conn.cursor() as cursor:
        cursor.executemany(
            """INSERT INTO simulator.logs
               (scenario_id, atm_id, asset_id, ticket_id, occurred_at, level, subsystem, error_code, message, attributes)
               VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
            log_rows,
        )

    tx_rows = []
    tx_types = ["withdrawal", "deposit", "balance_inquiry", "reversal"]
    if atm_id is not None:
        for j in range(rng.randint(1, 3)):
            tx_rows.append((scenario_id, atm_id, ticket_id, "TX-" + ticket["ticket_key"] + "-" + str(j + 1), occurred + timedelta(minutes=rng.randint(-30, 0)), rng.choice(tx_types), round(rng.uniform(200, 5000), 2), "MXN", "declined" if ticket["severity"] == "P1" else rng.choice(["approved", "declined"]), rng.choice(["00", "05", "91", "96"])))
        with conn.cursor() as cursor:
            cursor.executemany(
                """INSERT INTO simulator.transactions
                   (scenario_id, atm_id, ticket_id, transaction_key, occurred_at, transaction_type, amount, currency, status, response_code)
                   VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                tx_rows,
            )
    return ticket_id, {"id": str(ticket_id), "ticket_key": ticket["ticket_key"], "split": ticket["split"]}


def create_scenario(conn: Connection, name: str, count: int, seed: int, categories: List[str], subsystems: List[str]) -> Dict[str, Any]:
    rng = random.Random(seed)
    # Content timestamps are fixed from the seed so repeated runs reproduce the same cases.
    base_time = datetime(2026, 1, 1, 12, 0, tzinfo=timezone.utc) + timedelta(days=seed % 365)
    scenario_key = "atm-" + uuid4().hex[:10]
    scenario_id = conn.execute(
        """INSERT INTO simulator.scenarios
           (scenario_key, version, name, description, domain, simulator_version, seed, status)
           VALUES (%s,1,%s,%s,'device_operations','0.1.0',%s,'draft') RETURNING id""",
        (scenario_key, name, "Conjunto sintético de eventos de cajeros, IoT y SNMP generado por SignalLoop Simulator.", seed),
    ).fetchone()["id"]

    atm_count = min(12, max(4, count // 5))
    atms: List[Dict[str, Any]] = []
    for i in range(atm_count):
        model = ATMS[i % len(ATMS)]
        atm_id = conn.execute(
            """INSERT INTO simulator.atms
               (scenario_id, atm_key, location_code, vendor, model, software_version, xfs_version)
               VALUES (%s,%s,%s,%s,%s,%s,%s) RETURNING id""",
            (scenario_id, "ATM-MX-" + str(i + 1).zfill(3), model["location"], model["vendor"], model["model"], "4." + str(rng.randint(2, 9)) + "." + str(rng.randint(0, 5)), rng.choice(["3.30", "3.40", "3.50"])),
        ).fetchone()["id"]
        atms.append({"id": atm_id, "key": "ATM-MX-" + str(i + 1).zfill(3), "location": model["location"], "city": model["city"], "vendor": model["vendor"], "model": model["model"]})

    assets_by_type: Dict[str, List[Dict[str, Any]]] = {}
    requested_asset_sources = set(subsystems) & {"IOT", "SNMP"}
    asset_number = 0
    for model in ASSETS:
        asset_source = "IOT" if model["asset_type"].startswith("iot_") else "SNMP"
        if asset_source not in requested_asset_sources:
            continue
        asset_number += 1
        asset_key = "ASSET-MX-" + str(asset_number).zfill(3)
        asset_id = conn.execute(
            """INSERT INTO simulator.assets
               (scenario_id, asset_key, asset_type, location_code, vendor, model, metadata)
               VALUES (%s,%s,%s,%s,%s,%s,%s) RETURNING id""",
            (scenario_id, asset_key, model["asset_type"], model["location"], model["vendor"], model["model"], Jsonb({"synthetic": True, "source": asset_source})),
        ).fetchone()["id"]
        assets_by_type.setdefault(model["asset_type"], []).append({
            "id": asset_id,
            "key": asset_key,
            "location": model["location"],
            "city": model["location"].split("-")[0],
            "vendor": model["vendor"],
            "model": model["model"],
            "asset_type": model["asset_type"],
        })

    candidates = [f for f in FAULTS if f["category"] in categories and f["subsystem"] in subsystems]
    if not candidates:
        raise ValueError("Esa combinación de categorías y fuentes no tiene fallas compatibles. Selecciona otra fuente de señales.")
    split_list = [split for split, number in split_counts(count).items() for _ in range(number)]
    split_rng = random.Random(seed ^ 0x51A6)
    split_rng.shuffle(split_list)

    all_cases: List[Dict[str, Any]] = []
    used_codes: Dict[str, Dict[str, Any]] = {}
    for i in range(count):
        fault = rng.choice(candidates)
        used_codes[fault["code"]] = fault
        if fault.get("asset_type"):
            entity = rng.choice(assets_by_type[fault["asset_type"]])
            atm_id, asset_id = None, entity["id"]
        else:
            entity = rng.choice(atms)
            atm_id, asset_id = entity["id"], None
        event_time = base_time - timedelta(minutes=rng.randint(1, 60 * 24 * 30))
        severity = fault["severity"] if fault["severity"] == "P1" else rng.choices([fault["severity"], "P3"], weights=[70, 30])[0]
        time_label = event_time.strftime("%Y-%m-%d %H:%M UTC")
        ticket_key = "SL-INC-" + str(seed).zfill(4) + "-" + str(i + 1).zfill(4)
        description = (
            "Incidente sintético en " + entity["key"] + " (" + entity["location"] + ").\n"
            + "Señal observada: " + fault["signal"] + ".\n"
            + "Ventana reciente: " + str(rng.randint(2, 9)) + " eventos relacionados en " + str(rng.choice([5, 10, 15, 30])) + " minutos.\n"
            + "Primera detección: " + time_label + ".\n"
            + "Clasifica el incidente, determina su urgencia y recomienda la siguiente verificación operativa."
        )
        title_templates = {
            "hardware": ["Servicio de dispositivo degradado", "Alerta de equipo", "Activo fuera de rango"],
            "network": ["Ruta de red degradada", "Incidente de conectividad", "Comunicación demorada"],
            "software": ["Alerta del servicio transaccional", "Falla de journal o ejecución", "Salud del servicio central degradada"],
            "reconciliation": ["Diferencia en journal de efectivo", "Alerta de conciliación transaccional", "Descuadre de balance ATM"],
            "fraud": ["Señal de riesgo requiere revisión", "Patrón de seguridad ATM detectado", "Actividad transaccional inusual"],
        }
        title = entity["key"] + " · " + rng.choice(title_templates[fault["category"]])
        detail = {"subsystem": fault["subsystem"], "code": fault["code"], "attempt": rng.randint(1, 8), "synthetic": True}
        opened_event = (0, "ERROR" if severity == "P1" else "WARN", "Incidente abierto para " + entity["key"], {"subsystem": "CORE", "code": "SIM_INCIDENT_OPENED", "synthetic": True, "tipo_evento": "Alarma"})
        if fault["code"] == "SIM_XFS_DUPLICATE_EVENT":
            duplicate_event = {**detail, "event_id": ticket_key + "-DUP-01", "tipo_evento": "Operacion"}
            events = [
                (-2, "WARN", fault["signal"], duplicate_event),
                (-1, "WARN", fault["signal"], duplicate_event),
                opened_event,
            ]
        elif fault["code"] == "SIM_IOT_REFRIGERATOR_HEARTBEAT_MISSING":
            events = [
                (-2, "INFO", "Último heartbeat recibido del refrigerador.", {"subsystem": "IOT", "code": "SIM_IOT_HEARTBEAT_LAST_SEEN", "synthetic": True, "tipo_evento": "Heartbeat"}),
                (0, "ERROR", fault["signal"], {**detail, "tipo_evento": "Silencio"}),
                opened_event,
            ]
        elif fault["code"] == "SIM_SNMP_ALARM_INTERMITTENT":
            events = [
                (-4, "WARN", "Alarma SNMP recibida del UPS.", {**detail, "code": "SIM_SNMP_ALARM_FIRST", "tipo_evento": "Alarma"}),
                (-2, "INFO", "El UPS reportó recuperación temporal.", {"subsystem": "SNMP", "code": "SIM_SNMP_RECOVERY", "synthetic": True, "tipo_evento": "Recuperacion"}),
                (0, "ERROR", fault["signal"], {**detail, "code": "SIM_SNMP_ALARM_RECURRED", "tipo_evento": "Alarma"}),
                opened_event,
            ]
        else:
            events = [
                (-4, "WARN", "La señal superó el umbral del servicio " + fault["subsystem"] + ".", {"subsystem": fault["subsystem"], "code": "SIM_WARN_THRESHOLD", "synthetic": True, "tipo_evento": "Status"}),
                (-2, "ERROR", fault["signal"], {**detail, "tipo_evento": "Alarma"}),
                opened_event,
            ]
        impact = rng.choice(["Activo único afectado", "Servicio de la sucursal degradado", "Se requiere atención de mantenimiento", "Servicio degradado; sin impacto a clientes confirmado"])
        all_cases.append({"ticket_key": ticket_key, "fault": fault, "severity": severity, "occurred_at": event_time, "title": title, "description": description, "impact": impact, "split": split_list[i], "metadata": {"seed": seed, "synthetic": True, "city": entity["city"], "asset_type": entity.get("asset_type", "atm")}, "log_events": events, "atm_id": atm_id, "asset_id": asset_id, "entity": entity})

    for fault in used_codes.values():
        conn.execute(
            """INSERT INTO simulator.error_catalog
               (scenario_id, code, subsystem, category, severity, meaning, recommended_check, recommended_action)
               VALUES (%s,%s,%s,%s,%s,%s,%s,%s)""",
            (scenario_id, fault["code"], fault["subsystem"], fault["category"], fault["severity"], fault["cause"], fault["check"], fault["action"]),
        )

    ticket_refs: List[Dict[str, Any]] = []
    for ticket in all_cases:
        _, ref = _insert_case(conn, scenario_id, ticket["atm_id"], ticket["asset_id"], ticket, rng)
        ticket_refs.append(ref)

    used_categories = sorted({fault["category"] for fault in used_codes.values()})
    for category in used_categories:
        fault = next(f for f in used_codes.values() if f["category"] == category)
        conn.execute(
            """INSERT INTO simulator.runbooks
               (scenario_id, runbook_key, title, category, severity, symptom, diagnostic_steps, resolution_steps)
               VALUES (%s,%s,%s,%s,%s,%s,%s,%s)""",
            (scenario_id, "RB-" + category.upper(), "Triage inicial: " + {"hardware": "hardware", "network": "red", "software": "software", "reconciliation": "conciliación", "fraud": "fraude"}[category], category, fault["severity"], fault["signal"], Jsonb(["Confirma el dispositivo afectado y la ventana de tiempo", fault["check"], "Compara la señal con la de un cajero en buen estado"]), Jsonb([fault["action"], "Registra la evidencia y verifica la recuperación con una comprobación controlada"])),
        )

    counts = split_counts(count)
    for split_name, amount in counts.items():
        keys = [case["ticket_key"] for case in all_cases if case["split"] == split_name]
        digest = hashlib.sha256(json.dumps(keys, separators=(",", ":")).encode("utf-8")).hexdigest()
        conn.execute(
            """INSERT INTO simulator.dataset_exports
               (scenario_id, split, export_version, row_count, content_hash, frozen)
               VALUES (%s,%s,1,%s,%s,%s)""",
            (scenario_id, split_name, amount, digest, split_name == "test"),
        )
    conn.execute("UPDATE simulator.scenarios SET status='generated' WHERE id=%s", (scenario_id,))

    return {"id": str(scenario_id), "scenario_key": scenario_key, "name": name, "ticket_count": count, "atm_count": atm_count, "category_count": len(used_categories), "split_counts": counts}
