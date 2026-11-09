# PostgreSQL setup

SignalLoop uses one PostgreSQL database named `signalloop` with two schemas:

- `project`: agent systems, immutable generations, datasets, evaluation runs and per-case results, evidence-backed lessons, change decisions, mutation statistics, playbooks, and transfer experiments.
- `simulator`: synthetic scenarios, ATMs and generic device assets, tickets, XFS/IoT/SNMP error catalog, runbooks, logs, ATM transactions, expected outcomes, and frozen dataset exports.

The simulator writes scenario data into `simulator`. The runner writes execution and evaluation records into `project`. Keep expected outcomes out of the model-facing input; use them only in the evaluator. Keep the `test` split frozen and out of the mutation loop.

## Create the database

Create a local database using your PostgreSQL installation, then apply the initial migration once:

```sh
createdb signalloop
psql -d signalloop -f db/migrations/V001__initial_schemas.sql
```

For a database created with pgAdmin, skip `createdb` and run the `psql` migration command from the repository root. Alternatively, open the migration in pgAdmin's query tool while connected to `signalloop` and execute it.

Verify the result:

```sh
psql -d signalloop -c '\dn'
psql -d signalloop -c '\dt project.*'
psql -d signalloop -c '\dt simulator.*'
```

Migration `V001` creates 15 project tables and 9 simulator tables. `V002` extends simulator logs and assets for IoT and SNMP sources. Apply new migrations in order. Do not commit PostgreSQL dumps, `.env` files, passwords, or real customer data.

## Connection configuration

Use an environment variable for the local connection string, for example:

```env
DATABASE_URL=postgresql://USER:PASSWORD@localhost:5432/signalloop
```

Keep the actual value in a local `.env` file ignored by Git or in the deployment secret manager. Each teammate configures their own local database and credentials.
