# SignalLoop for RSI-Hackathon
Adaptive Device Intelligence

## PostgreSQL

The initial database migration creates the `project` and `simulator` schemas in one PostgreSQL database. See [Database setup](docs/database-setup.md) before running the application or simulator.

## Local simulator

The web simulator generates reproducible synthetic XFS, IoT, and SNMP device events and inserts them into PostgreSQL. See [Simulator setup](simulator/README.md) to run it locally.
