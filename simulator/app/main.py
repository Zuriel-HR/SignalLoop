from __future__ import annotations

import os
from pathlib import Path
from typing import List, Literal
from uuid import UUID

import psycopg
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from psycopg.rows import dict_row

from .generator import create_scenario

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / ".env")

app = FastAPI(title="SignalLoop Simulator", version="0.1.0")
app.mount("/static", StaticFiles(directory=BASE_DIR / "app" / "static"), name="static")


class ScenarioRequest(BaseModel):
    name: str = Field(min_length=3, max_length=80)
    ticket_count: Literal[30, 60, 90, 120, 240] = 60
    seed: int = Field(ge=0, le=2_147_483_647)
    categories: List[Literal["hardware", "network", "software", "reconciliation", "fraud"]]
    subsystems: List[Literal["XFS", "IO", "NETWORK", "CORE", "FRAUD", "RECONCILIATION", "IOT", "SNMP"]]


def database_url() -> str:
    value = os.getenv("DATABASE_URL", "").strip()
    if not value:
        raise HTTPException(status_code=503, detail="DATABASE_URL is not configured. Copy simulator/.env.example to simulator/.env and set your local connection.")
    return value


def connection():
    try:
        return psycopg.connect(database_url(), row_factory=dict_row)
    except HTTPException:
        raise
    except psycopg.Error as exc:
        raise HTTPException(status_code=503, detail="PostgreSQL is unavailable. Start Postgres.app and verify DATABASE_URL.") from exc


@app.get("/")
def index():
    return FileResponse(BASE_DIR / "app" / "static" / "index.html")


@app.get("/api/health")
def health():
    try:
        with connection() as conn:
            row = conn.execute("SELECT current_database() AS database_name, current_setting('server_version') AS postgres_version").fetchone()
            return {"status": "online", "database": row["database_name"], "postgres_version": row["postgres_version"]}
    except HTTPException as exc:
        return {"status": "offline", "message": exc.detail}


@app.get("/api/scenarios")
def scenarios():
    with connection() as conn:
        rows = conn.execute(
            """SELECT s.id::text AS id, s.scenario_key, s.name, s.seed, s.status, s.simulator_version, s.created_at,
                      COUNT(t.id)::int AS ticket_count,
                      COUNT(t.id) FILTER (WHERE t.split='train')::int AS train_count,
                      COUNT(t.id) FILTER (WHERE t.split='validation')::int AS validation_count,
                      COUNT(t.id) FILTER (WHERE t.split='test')::int AS test_count
               FROM simulator.scenarios s LEFT JOIN simulator.tickets t ON t.scenario_id=s.id
               GROUP BY s.id ORDER BY s.created_at DESC LIMIT 30"""
        ).fetchall()
        return rows


@app.post("/api/scenarios")
def generate_scenario(request: ScenarioRequest):
    name = request.name.strip()
    if len(name) < 3:
        raise HTTPException(status_code=422, detail="El nombre debe tener al menos tres caracteres.")
    if not request.categories:
        raise HTTPException(status_code=422, detail="Selecciona al menos una categoría de incidente.")
    if not request.subsystems:
        raise HTTPException(status_code=422, detail="Selecciona al menos una fuente de señales.")
    try:
        with connection() as conn:
            return create_scenario(conn, name, request.ticket_count, request.seed, request.categories, request.subsystems)
    except HTTPException:
        raise
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except psycopg.Error as exc:
        raise HTTPException(status_code=500, detail="Falló la generación; PostgreSQL revirtió la transacción completa.") from exc


@app.get("/api/scenarios/{scenario_id}/tickets")
def scenario_tickets(scenario_id: UUID, split: Literal["all", "train", "validation", "test"] = "all"):
    with connection() as conn:
        exists = conn.execute("SELECT 1 FROM simulator.scenarios WHERE id=%s", (scenario_id,)).fetchone()
        if not exists:
            raise HTTPException(status_code=404, detail="No se encontró el escenario.")
        rows = conn.execute(
            """SELECT t.id::text AS id, t.ticket_key, t.title, t.category, t.severity, t.split,
                      t.customer_impact, COALESCE(a.atm_key, g.asset_key) AS atm_key,
                      COALESCE(a.location_code, g.location_code) AS location_code, e.root_cause
               FROM simulator.tickets t
               LEFT JOIN simulator.atms a ON a.id=t.atm_id
               LEFT JOIN simulator.assets g ON g.id=t.asset_id
               LEFT JOIN simulator.expected_outcomes e ON e.ticket_id=t.id
               WHERE t.scenario_id=%s AND (%s='all' OR t.split=%s)
               ORDER BY t.occurred_at DESC LIMIT 500""",
            (scenario_id, split, split),
        ).fetchall()
        return rows


@app.get("/api/scenarios/{scenario_id}/agent-cases")
def agent_cases(scenario_id: UUID, split: Literal["train", "validation", "test"] = "train"):
    """Return model-facing inputs only; categories, severity, and gold outcomes are omitted."""
    with connection() as conn:
        exists = conn.execute("SELECT 1 FROM simulator.scenarios WHERE id=%s", (scenario_id,)).fetchone()
        if not exists:
            raise HTTPException(status_code=404, detail="No se encontró el escenario.")
        rows = conn.execute(
            """SELECT t.id::text AS case_id, t.ticket_key, t.title, t.description, t.customer_impact, t.occurred_at,
                      COALESCE(a.atm_key, g.asset_key) AS asset_key,
                      COALESCE(a.location_code, g.location_code) AS location_code,
                      COALESCE(a.vendor, g.vendor) AS vendor, COALESCE(a.model, g.model) AS model,
                      a.software_version, a.xfs_version, g.asset_type,
                      COALESCE((SELECT jsonb_agg(jsonb_build_object(
                          'occurred_at', l.occurred_at, 'level', l.level, 'subsystem', l.subsystem,
                          'error_code', l.error_code, 'message', l.message, 'attributes', l.attributes
                      ) ORDER BY l.occurred_at) FROM simulator.logs l WHERE l.ticket_id=t.id), '[]'::jsonb) AS logs
               FROM simulator.tickets t LEFT JOIN simulator.atms a ON a.id=t.atm_id
               LEFT JOIN simulator.assets g ON g.id=t.asset_id
               WHERE t.scenario_id=%s AND t.split=%s ORDER BY t.ticket_key""",
            (scenario_id, split),
        ).fetchall()
        return rows
