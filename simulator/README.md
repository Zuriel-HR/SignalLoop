# SignalLoop Simulator

Local web simulator for generating synthetic device incidents and inserting them into the PostgreSQL `simulator` schema. The supported sources are ATM/XFS, IoT (refrigerator and boiler), and SNMP (router, UPS, and network). Each scenario is reproducible by seed and includes tickets, device assets, source logs, runbooks, expected outcomes, and fixed train/validation/test exports. ATM cases also include transaction samples.

## Requirements

- Python 3.9+
- PostgreSQL with migrations `V001` and `V002` applied

Start Postgres.app before launching the web app. This simulator does not start, migrate, or drop databases automatically.

## Run locally

From the repository root:

```sh
cd simulator
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
cp .env.example .env
```

Edit `simulator/.env` locally with the connection string for your `signalloop` database. Keep the actual credentials out of Git. Then start the app:

```sh
uvicorn app.main:app --reload --host 127.0.0.1 --port 8090
```

Open http://127.0.0.1:8090. Check the PostgreSQL indicator before generating a scenario. The generator inserts each scenario in one transaction; a failed insert rolls back the entire scenario.

If the database already has the initial schema, apply the additive IoT/SNMP migration once from the repository root before restarting the app:

```sh
cd ..
/Applications/Postgres.app/Contents/Versions/18/bin/psql -h localhost -U postgres -d signalloop -f db/migrations/V002__iot_snmp_assets.sql
```

## Dataset rules

- The random seed controls device assignment, incident templates, event times, and split assignment.
- Split proportions are 50% train, 25% validation, and the remainder test. For 60 cases this is 30/15/15.
- Test exports are marked frozen in `simulator.dataset_exports`.
- `GET /api/scenarios/{id}/agent-cases?split=train` returns model-facing ticket text, device context, and logs without category, severity, root cause, or expected action.
- `GET /api/scenarios/{id}/tickets` is the simulator review view and includes labels. Do not use that response as agent input.
- All generated content is synthetic. Never add customer or production data to this simulator.

## API

- `GET /api/health` — local PostgreSQL status
- `GET /api/scenarios` — recent scenarios and split counts
- `POST /api/scenarios` — generate and insert a scenario
- `GET /api/scenarios/{id}/tickets?split=all|train|validation|test` — review cases and labels
- `GET /api/scenarios/{id}/agent-cases?split=train|validation|test` — safe agent input without labels
