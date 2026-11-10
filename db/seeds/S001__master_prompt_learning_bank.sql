-- Deterministic six-case bank for the master-prompt progression.
-- One train and one validation case per source; expected outcomes are evaluator-only.
BEGIN;

DO $$
DECLARE
    v_scenario_id uuid;
    atm_id uuid;
    iot_id uuid;
    snmp_id uuid;
    ticket_id uuid;
    case_no integer;
    src text;
    err_code text;
    cat text;
    sev text;
    cause text;
    action text;
    split_name text;
    key_name text;
    event_code text;
    event_msg text;
    event_level text;
    event_id text;
BEGIN
    SELECT id INTO v_scenario_id
      FROM simulator.scenarios
     WHERE scenario_key = 'master-prompt-learning-bank' AND version = 1;
    IF v_scenario_id IS NOT NULL THEN
        RAISE NOTICE 'Master prompt learning bank already exists: %', v_scenario_id;
        RETURN;
    END IF;

    INSERT INTO simulator.scenarios
        (scenario_key, version, name, description, domain, simulator_version, seed, status)
    VALUES
        ('master-prompt-learning-bank', 1, 'SignalLoop · banco de aprendizaje (6 casos)',
         'Banco fijo XFS, IoT y SNMP: un caso de entrenamiento y uno de validación por fuente.',
         'device_operations', 'master-prompt-1.0', 1109, 'generated')
    RETURNING id INTO v_scenario_id;

    INSERT INTO simulator.atms
        (scenario_id, atm_key, location_code, vendor, model, software_version, xfs_version)
    VALUES (v_scenario_id, 'ATM-BENCH-XFS', 'CDMX-CENTRO', 'NCR', 'SelfServ 80', '4.8.1', '3.50')
    RETURNING id INTO atm_id;

    INSERT INTO simulator.assets
        (scenario_id, asset_key, asset_type, location_code, vendor, model, metadata)
    VALUES (v_scenario_id, 'ASSET-BENCH-IOT', 'iot_refrigerator', 'CDMX-CENTRO', 'FríoTec', 'FT-IoT 400', '{"synthetic":true,"source":"IOT"}')
    RETURNING id INTO iot_id;

    INSERT INTO simulator.assets
        (scenario_id, asset_key, asset_type, location_code, vendor, model, metadata)
    VALUES (v_scenario_id, 'ASSET-BENCH-SNMP', 'snmp_ups', 'GDL-MINERVA', 'APC', 'Smart-UPS 1500', '{"synthetic":true,"source":"SNMP"}')
    RETURNING id INTO snmp_id;

    INSERT INTO simulator.error_catalog
        (scenario_id, code, subsystem, category, severity, meaning, recommended_check, recommended_action)
    VALUES
        (v_scenario_id, 'SIM_XFS_DUPLICATE_EVENT', 'XFS', 'software', 'P2', 'El mismo evento XFS llegó más de una vez.', 'Comparar IDs y marcas de tiempo de entrega.', 'Deduplicar por ID de evento y conservar un solo incidente.'),
        (v_scenario_id, 'SIM_IOT_REFRIGERATOR_HEARTBEAT_MISSING', 'IOT', 'hardware', 'P1', 'El refrigerador dejó de reportar heartbeat.', 'Revisar gateway, alimentación y última telemetría.', 'Enviar soporte de instalaciones y confirmar que vuelva a reportar.'),
        (v_scenario_id, 'SIM_SNMP_ALARM_INTERMITTENT', 'SNMP', 'network', 'P1', 'La alarma de UPS volvió después de una recuperación breve.', 'Correlacionar alarma, recuperación y acuse por dispositivo e ID.', 'Mantener un solo incidente abierto hasta que el UPS esté estable.')
    ON CONFLICT (scenario_id, code) DO NOTHING;

    FOR case_no IN 1..6 LOOP
        src := CASE WHEN case_no <= 2 THEN 'XFS' WHEN case_no <= 4 THEN 'IOT' ELSE 'SNMP' END;
        err_code := CASE src WHEN 'XFS' THEN 'SIM_XFS_DUPLICATE_EVENT' WHEN 'IOT' THEN 'SIM_IOT_REFRIGERATOR_HEARTBEAT_MISSING' ELSE 'SIM_SNMP_ALARM_INTERMITTENT' END;
        cat := CASE src WHEN 'XFS' THEN 'software' WHEN 'IOT' THEN 'hardware' ELSE 'network' END;
        sev := CASE WHEN src = 'XFS' THEN 'P2' ELSE 'P1' END;
        cause := CASE src WHEN 'XFS' THEN 'El mismo evento XFS fue entregado más de una vez.' WHEN 'IOT' THEN 'El refrigerador dejó de reportar su heartbeat.' ELSE 'La alarma del UPS reapareció tras una recuperación temporal.' END;
        action := CASE src WHEN 'XFS' THEN 'Deduplicar por ID de evento y conservar un solo incidente.' WHEN 'IOT' THEN 'Enviar soporte de instalaciones y confirmar la telemetría.' ELSE 'Mantener un incidente abierto hasta confirmar estabilidad del UPS.' END;
        split_name := CASE WHEN case_no % 2 = 1 THEN 'train' ELSE 'validation' END;
        key_name := 'MASTER-' || src || '-' || CASE WHEN split_name = 'train' THEN 'TRAIN' ELSE 'VALIDATION' END;

        INSERT INTO simulator.tickets
            (scenario_id, ticket_key, atm_id, asset_id, occurred_at, category, severity, title, description, customer_impact, split, metadata)
        VALUES
            (v_scenario_id, key_name,
             CASE WHEN src = 'XFS' THEN atm_id ELSE NULL END,
             CASE WHEN src = 'IOT' THEN iot_id WHEN src = 'SNMP' THEN snmp_id ELSE NULL END,
             timestamptz '2026-01-01 12:00:00+00' + make_interval(mins => case_no),
             cat, sev,
             CASE src WHEN 'XFS' THEN 'Evento XFS duplicado' WHEN 'IOT' THEN 'Heartbeat IoT ausente' ELSE 'Alarma SNMP intermitente' END,
             'Caso sintético fijo del master prompt. Clasifica usando únicamente las señales observadas.',
             'Activo sintético; sin impacto real.', split_name,
             jsonb_build_object('synthetic', true, 'benchmark', 'master-prompt-6', 'source', src))
        RETURNING id INTO ticket_id;

        INSERT INTO simulator.expected_outcomes
            (ticket_id, root_cause, expected_category, expected_severity, expected_next_action, rubric)
            VALUES (ticket_id, cause, cat, sev, action,
                jsonb_build_object('category_weight', 1.0, 'benchmark_only', true));

        IF src = 'XFS' THEN
            event_id := key_name || '-EVT-DUP';
            INSERT INTO simulator.logs (scenario_id, atm_id, ticket_id, occurred_at, level, subsystem, error_code, message, attributes)
            VALUES
                (v_scenario_id, atm_id, ticket_id, timestamptz '2026-01-01 12:00:00+00' + make_interval(mins => case_no * 10 - 3), 'WARN', 'XFS', err_code, 'Se recibió el evento XFS duplicado.', jsonb_build_object('event_id', event_id, 'tipo_evento', 'Operacion', 'synthetic', true)),
                (v_scenario_id, atm_id, ticket_id, timestamptz '2026-01-01 12:00:00+00' + make_interval(mins => case_no * 10 - 2), 'WARN', 'XFS', err_code, 'Se recibió nuevamente el mismo ID de evento XFS.', jsonb_build_object('event_id', event_id, 'tipo_evento', 'Operacion', 'synthetic', true)),
                (v_scenario_id, atm_id, ticket_id, timestamptz '2026-01-01 12:00:00+00' + make_interval(mins => case_no * 10 - 1), 'WARN', 'CORE', 'SIM_INCIDENT_OPENED', 'Se abrió el ticket sintético.', jsonb_build_object('synthetic', true));
        ELSIF src = 'IOT' THEN
            INSERT INTO simulator.logs (scenario_id, asset_id, ticket_id, occurred_at, level, subsystem, error_code, message, attributes)
            VALUES
                (v_scenario_id, iot_id, ticket_id, timestamptz '2026-01-01 12:00:00+00' + make_interval(mins => case_no * 10 - 10), 'INFO', 'IOT', 'SIM_IOT_HEARTBEAT_LAST_SEEN', 'Último heartbeat reportado por el refrigerador.', jsonb_build_object('synthetic', true, 'tipo_evento', 'Heartbeat')),
                (v_scenario_id, iot_id, ticket_id, timestamptz '2026-01-01 12:00:00+00' + make_interval(mins => case_no * 10 - 1), 'ERROR', 'IOT', err_code, 'No se recibió heartbeat IoT dentro de 60 segundos.', jsonb_build_object('synthetic', true, 'tipo_evento', 'Silencio', 'threshold_seconds', 60)),
                (v_scenario_id, iot_id, ticket_id, timestamptz '2026-01-01 12:00:00+00' + make_interval(mins => case_no * 10), 'ERROR', 'CORE', 'SIM_INCIDENT_OPENED', 'Se abrió el ticket sintético.', jsonb_build_object('synthetic', true));
        ELSE
            INSERT INTO simulator.logs (scenario_id, asset_id, ticket_id, occurred_at, level, subsystem, error_code, message, attributes)
            VALUES
                (v_scenario_id, snmp_id, ticket_id, timestamptz '2026-01-01 12:00:00+00' + make_interval(mins => case_no * 10 - 4), 'WARN', 'SNMP', 'SIM_SNMP_ALARM_FIRST', 'Alarma SNMP inicial del UPS.', jsonb_build_object('synthetic', true, 'event_id', key_name || '-ALARM')),
                (v_scenario_id, snmp_id, ticket_id, timestamptz '2026-01-01 12:00:00+00' + make_interval(mins => case_no * 10 - 2), 'INFO', 'SNMP', 'SIM_SNMP_RECOVERY', 'El UPS reportó recuperación temporal.', jsonb_build_object('synthetic', true, 'event_id', key_name || '-RECOVERY')),
                (v_scenario_id, snmp_id, ticket_id, timestamptz '2026-01-01 12:00:00+00' + make_interval(mins => case_no * 10 - 1), 'ERROR', 'SNMP', err_code, 'La alarma del UPS reapareció tras la recuperación.', jsonb_build_object('synthetic', true, 'event_id', key_name || '-ALARM-RECURRED')),
                (v_scenario_id, snmp_id, ticket_id, timestamptz '2026-01-01 12:00:00+00' + make_interval(mins => case_no * 10), 'ERROR', 'CORE', 'SIM_INCIDENT_OPENED', 'Se abrió el ticket sintético.', jsonb_build_object('synthetic', true));
        END IF;
    END LOOP;

    INSERT INTO simulator.dataset_exports (scenario_id, split, export_version, row_count, content_hash, frozen)
    SELECT v_scenario_id, t.split, 1, count(*)::int,
           md5(string_agg(t.ticket_key, ',' ORDER BY t.ticket_key)), t.split = 'test'
      FROM simulator.tickets t WHERE t.scenario_id = v_scenario_id GROUP BY t.split
    ON CONFLICT (scenario_id, split, export_version) DO NOTHING;
END $$;

COMMIT;
