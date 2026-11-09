-- SignalLoop initial PostgreSQL schema. Run inside database `signalloop`.
CREATE SCHEMA IF NOT EXISTS project;
CREATE SCHEMA IF NOT EXISTS simulator;

CREATE TABLE project.projects (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug text NOT NULL UNIQUE,
    name text NOT NULL,
    description text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE project.problem_specs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project.projects(id),
    key text NOT NULL,
    title text NOT NULL,
    domain text NOT NULL,
    description text NOT NULL,
    evaluator_version text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, key)
);

CREATE TABLE project.datasets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    problem_spec_id uuid NOT NULL REFERENCES project.problem_specs(id),
    source_scenario_id uuid,
    name text NOT NULL,
    split text NOT NULL CHECK (split IN ('train','validation','test')),
    source text NOT NULL DEFAULT 'synthetic',
    simulator_version text,
    seed integer,
    item_count integer NOT NULL DEFAULT 0 CHECK (item_count >= 0),
    content_hash text,
    immutable boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (problem_spec_id, name)
);

CREATE TABLE project.agent_systems (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project.projects(id),
    problem_spec_id uuid NOT NULL REFERENCES project.problem_specs(id),
    name text NOT NULL,
    role text NOT NULL CHECK (role IN ('solver','builder')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE project.generations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    system_id uuid NOT NULL REFERENCES project.agent_systems(id),
    generation_no integer NOT NULL CHECK (generation_no >= 0),
    parent_generation_id uuid REFERENCES project.generations(id),
    status text NOT NULL DEFAULT 'proposed'
        CHECK (status IN ('proposed','evaluating','accepted','rejected','failed','baseline')),
    config jsonb NOT NULL,
    config_hash text NOT NULL,
    change_type text CHECK (change_type IN ('prompt','tool','strategy','lessons','builder','other')),
    change_summary text,
    rationale text,
    created_by text NOT NULL DEFAULT 'system' CHECK (created_by IN ('system','human')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (system_id, generation_no),
    UNIQUE (system_id, config_hash)
);

CREATE TABLE project.evaluation_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    system_id uuid NOT NULL REFERENCES project.agent_systems(id),
    generation_id uuid NOT NULL REFERENCES project.generations(id),
    dataset_id uuid NOT NULL REFERENCES project.datasets(id),
    run_group uuid NOT NULL DEFAULT gen_random_uuid(),
    seed integer,
    status text NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued','running','completed','failed','cancelled')),
    evaluator_version text NOT NULL,
    started_at timestamptz,
    finished_at timestamptz,
    total_score numeric(8,5),
    total_cost_usd numeric(12,6) NOT NULL DEFAULT 0,
    total_input_tokens integer NOT NULL DEFAULT 0,
    total_output_tokens integer NOT NULL DEFAULT 0,
    error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at)
);

CREATE TABLE project.case_results (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_run_id uuid NOT NULL REFERENCES project.evaluation_runs(id) ON DELETE CASCADE,
    case_key text NOT NULL,
    simulator_ticket_id uuid,
    trace_id text,
    input_snapshot jsonb NOT NULL,
    expected jsonb NOT NULL,
    output jsonb,
    score numeric(8,5) CHECK (score BETWEEN 0 AND 1),
    tools_used jsonb NOT NULL DEFAULT '[]'::jsonb,
    input_tokens integer NOT NULL DEFAULT 0,
    output_tokens integer NOT NULL DEFAULT 0,
    cost_usd numeric(12,6) NOT NULL DEFAULT 0,
    latency_ms integer,
    error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (evaluation_run_id, case_key)
);

CREATE TABLE project.lessons (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project.projects(id),
    problem_spec_id uuid REFERENCES project.problem_specs(id),
    lesson_key text NOT NULL,
    condition text NOT NULL,
    action text NOT NULL,
    scope text NOT NULL DEFAULT 'domain'
        CHECK (scope IN ('case','domain','cross_domain','process')),
    confidence numeric(5,4) CHECK (confidence BETWEEN 0 AND 1),
    status text NOT NULL DEFAULT 'proposed'
        CHECK (status IN ('proposed','accepted','rejected','superseded')),
    source text NOT NULL DEFAULT 'reflector'
        CHECK (source IN ('reflector','human','distillation')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, lesson_key)
);

CREATE TABLE project.lesson_evidence (
    lesson_id uuid NOT NULL REFERENCES project.lessons(id) ON DELETE CASCADE,
    case_result_id uuid NOT NULL REFERENCES project.case_results(id) ON DELETE CASCADE,
    evidence_role text NOT NULL DEFAULT 'support'
        CHECK (evidence_role IN ('support','counterexample')),
    PRIMARY KEY (lesson_id, case_result_id)
);

CREATE TABLE project.generation_lessons (
    generation_id uuid NOT NULL REFERENCES project.generations(id) ON DELETE CASCADE,
    lesson_id uuid NOT NULL REFERENCES project.lessons(id),
    PRIMARY KEY (generation_id, lesson_id)
);

CREATE TABLE project.change_decisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    system_id uuid NOT NULL REFERENCES project.agent_systems(id),
    parent_generation_id uuid NOT NULL REFERENCES project.generations(id),
    candidate_generation_id uuid NOT NULL UNIQUE REFERENCES project.generations(id),
    validation_run_group uuid NOT NULL,
    baseline_mean numeric(8,5),
    candidate_mean numeric(8,5),
    mean_delta numeric(8,5),
    lower_bound_90 numeric(8,5),
    margin numeric(8,5) NOT NULL DEFAULT 0,
    decision text NOT NULL CHECK (decision IN ('accepted','rejected','inconclusive','failed')),
    reason text NOT NULL,
    decided_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE project.mutation_stats (
    system_id uuid NOT NULL REFERENCES project.agent_systems(id),
    change_type text NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    accepted integer NOT NULL DEFAULT 0,
    mean_gain numeric(8,5) NOT NULL DEFAULT 0,
    mean_cost_usd numeric(12,6) NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (system_id, change_type),
    CHECK (accepted <= attempts)
);

CREATE TABLE project.playbooks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project.projects(id),
    version integer NOT NULL CHECK (version > 0),
    name text NOT NULL,
    content jsonb NOT NULL,
    source_system_id uuid REFERENCES project.agent_systems(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, version)
);

CREATE TABLE project.playbook_lessons (
    playbook_id uuid NOT NULL REFERENCES project.playbooks(id) ON DELETE CASCADE,
    lesson_id uuid NOT NULL REFERENCES project.lessons(id),
    PRIMARY KEY (playbook_id, lesson_id)
);

CREATE TABLE project.transfer_experiments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project.projects(id),
    target_problem_spec_id uuid NOT NULL REFERENCES project.problem_specs(id),
    playbook_id uuid REFERENCES project.playbooks(id),
    condition text NOT NULL CHECK (condition IN ('with_playbook','without_playbook')),
    seed integer NOT NULL,
    budget_usd numeric(12,6),
    generation_limit integer,
    initial_score numeric(8,5),
    final_score numeric(8,5),
    generations_used integer,
    cost_to_target_usd numeric(12,6),
    target_score numeric(8,5),
    status text NOT NULL DEFAULT 'planned'
        CHECK (status IN ('planned','running','completed','failed')),
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

CREATE TABLE simulator.scenarios (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_key text NOT NULL,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    name text NOT NULL,
    description text,
    domain text NOT NULL DEFAULT 'atm_support',
    simulator_version text NOT NULL DEFAULT '0.1.0',
    seed integer NOT NULL,
    status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','generated','frozen')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (scenario_key, version)
);

CREATE TABLE simulator.atms (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id uuid NOT NULL REFERENCES simulator.scenarios(id) ON DELETE CASCADE,
    atm_key text NOT NULL,
    location_code text NOT NULL,
    vendor text NOT NULL,
    model text NOT NULL,
    software_version text NOT NULL,
    xfs_version text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (scenario_id, atm_key)
);

CREATE TABLE simulator.tickets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id uuid NOT NULL REFERENCES simulator.scenarios(id) ON DELETE CASCADE,
    ticket_key text NOT NULL,
    atm_id uuid REFERENCES simulator.atms(id),
    occurred_at timestamptz NOT NULL,
    category text NOT NULL CHECK (category IN ('hardware','network','software','reconciliation','fraud')),
    severity text NOT NULL CHECK (severity IN ('P1','P2','P3')),
    title text NOT NULL,
    description text NOT NULL,
    customer_impact text,
    split text NOT NULL CHECK (split IN ('train','validation','test')),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (scenario_id, ticket_key)
);

CREATE TABLE simulator.error_catalog (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id uuid NOT NULL REFERENCES simulator.scenarios(id) ON DELETE CASCADE,
    code text NOT NULL,
    subsystem text NOT NULL CHECK (subsystem IN ('XFS','IO','NETWORK','CORE','FRAUD','RECONCILIATION')),
    category text NOT NULL,
    severity text NOT NULL CHECK (severity IN ('P1','P2','P3')),
    meaning text NOT NULL,
    recommended_check text NOT NULL,
    recommended_action text NOT NULL,
    UNIQUE (scenario_id, code)
);

CREATE TABLE simulator.runbooks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id uuid NOT NULL REFERENCES simulator.scenarios(id) ON DELETE CASCADE,
    runbook_key text NOT NULL,
    title text NOT NULL,
    category text NOT NULL,
    severity text CHECK (severity IN ('P1','P2','P3')),
    symptom text NOT NULL,
    diagnostic_steps jsonb NOT NULL,
    resolution_steps jsonb NOT NULL,
    UNIQUE (scenario_id, runbook_key)
);

CREATE TABLE simulator.logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id uuid NOT NULL REFERENCES simulator.scenarios(id) ON DELETE CASCADE,
    atm_id uuid NOT NULL REFERENCES simulator.atms(id) ON DELETE CASCADE,
    ticket_id uuid REFERENCES simulator.tickets(id) ON DELETE SET NULL,
    occurred_at timestamptz NOT NULL,
    level text NOT NULL CHECK (level IN ('DEBUG','INFO','WARN','ERROR','FATAL')),
    subsystem text NOT NULL CHECK (subsystem IN ('XFS','IO','NETWORK','CORE','FRAUD','RECONCILIATION')),
    error_code text,
    message text NOT NULL,
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE simulator.transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id uuid NOT NULL REFERENCES simulator.scenarios(id) ON DELETE CASCADE,
    atm_id uuid NOT NULL REFERENCES simulator.atms(id) ON DELETE CASCADE,
    ticket_id uuid REFERENCES simulator.tickets(id) ON DELETE SET NULL,
    transaction_key text NOT NULL,
    occurred_at timestamptz NOT NULL,
    transaction_type text NOT NULL
        CHECK (transaction_type IN ('withdrawal','deposit','balance_inquiry','reversal')),
    amount numeric(14,2) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL DEFAULT 'MXN',
    status text NOT NULL CHECK (status IN ('approved','declined','reversed','pending')),
    response_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (scenario_id, transaction_key)
);

CREATE TABLE simulator.expected_outcomes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id uuid NOT NULL UNIQUE REFERENCES simulator.tickets(id) ON DELETE CASCADE,
    root_cause text NOT NULL,
    expected_category text NOT NULL
        CHECK (expected_category IN ('hardware','network','software','reconciliation','fraud')),
    expected_severity text NOT NULL CHECK (expected_severity IN ('P1','P2','P3')),
    expected_next_action text NOT NULL,
    rubric jsonb NOT NULL DEFAULT '{}'::jsonb,
    authored_by text NOT NULL DEFAULT 'simulator' CHECK (authored_by IN ('simulator','human'))
);

CREATE TABLE simulator.dataset_exports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id uuid NOT NULL REFERENCES simulator.scenarios(id) ON DELETE CASCADE,
    split text NOT NULL CHECK (split IN ('train','validation','test')),
    export_version integer NOT NULL DEFAULT 1,
    row_count integer NOT NULL DEFAULT 0 CHECK (row_count >= 0),
    content_hash text NOT NULL,
    exported_at timestamptz NOT NULL DEFAULT now(),
    frozen boolean NOT NULL DEFAULT false,
    UNIQUE (scenario_id, split, export_version)
);

CREATE INDEX idx_generations_system_created ON project.generations(system_id, created_at DESC);
CREATE INDEX idx_evaluation_generation_dataset ON project.evaluation_runs(generation_id, dataset_id);
CREATE INDEX idx_case_results_run ON project.case_results(evaluation_run_id);
CREATE INDEX idx_lessons_project_status ON project.lessons(project_id, status);
CREATE INDEX idx_lesson_evidence_result ON project.lesson_evidence(case_result_id);
CREATE INDEX idx_transfer_target ON project.transfer_experiments(target_problem_spec_id, condition);
CREATE INDEX idx_sim_tickets_scenario_split ON simulator.tickets(scenario_id, split);
CREATE INDEX idx_sim_tickets_severity ON simulator.tickets(scenario_id, severity);
CREATE INDEX idx_sim_logs_atm_time ON simulator.logs(atm_id, occurred_at);
CREATE INDEX idx_sim_logs_ticket ON simulator.logs(ticket_id);
CREATE INDEX idx_sim_txn_atm_time ON simulator.transactions(atm_id, occurred_at);
CREATE INDEX idx_sim_txn_ticket ON simulator.transactions(ticket_id);

