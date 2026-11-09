"""Acceso a los archivos del contrato. Es el único módulo que sabe dónde vive cada cosa."""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path

from jsonschema import Draft202012Validator

PAQUETE = Path(__file__).resolve().parent.parent


def ahora() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


class Raiz:
    """Rutas del proyecto. Por defecto la carpeta actual o SIGNALLOOP_RAIZ."""

    def __init__(self, raiz: str | Path | None = None):
        self.base = Path(raiz or os.environ.get("SIGNALLOOP_RAIZ") or Path.cwd()).resolve()
        self.banco = self.base / "banco" / "casos"
        self.lecciones = self.base / "lecciones"
        self.activas = self.lecciones / "activas"
        self.candidatas = self.lecciones / "candidatas"
        self.rechazadas = self.lecciones / "rechazadas"
        self.versiones = self.lecciones / "versiones.json"
        self.resultados = self.base / "resultados"
        self.corridas = self.resultados / "corridas.jsonl"
        self.bitacora = self.resultados / "bitacora_rsi.jsonl"

    def asegurar(self) -> None:
        for d in (self.activas, self.candidatas, self.rechazadas, self.resultados):
            d.mkdir(parents=True, exist_ok=True)
        if not self.versiones.exists():
            escribir_json(self.versiones, {"actual": "v0", "historial": [
                {"version": "v0", "lecciones": [], "padre": None, "creado_por": "equipo",
                 "nota": "reglas base del intérprete", "ts": ahora()}]})


# ---------- JSON ----------

def leer_json(ruta: Path):
    return json.loads(Path(ruta).read_text(encoding="utf-8"))


def escribir_json(ruta: Path, datos) -> None:
    ruta = Path(ruta)
    ruta.parent.mkdir(parents=True, exist_ok=True)
    tmp = ruta.with_suffix(ruta.suffix + ".tmp")
    tmp.write_text(json.dumps(datos, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(ruta)  # escritura atómica: el intérprete nunca lee un archivo a medias


def anexar_jsonl(ruta: Path, registro: dict) -> None:
    ruta = Path(ruta)
    ruta.parent.mkdir(parents=True, exist_ok=True)
    with ruta.open("a", encoding="utf-8") as f:
        f.write(json.dumps(registro, ensure_ascii=False) + "\n")


def leer_jsonl(ruta: Path) -> list[dict]:
    ruta = Path(ruta)
    if not ruta.exists():
        return []
    filas = []
    for n, linea in enumerate(ruta.read_text(encoding="utf-8").splitlines(), 1):
        if linea.strip():
            try:
                filas.append(json.loads(linea))
            except json.JSONDecodeError as e:
                raise ValueError(f"{ruta}:{n} no es JSON válido: {e}") from e
    return filas


# ---------- Esquemas ----------

@lru_cache
def _validador(nombre: str) -> Draft202012Validator:
    return Draft202012Validator(leer_json(PAQUETE / "contrato" / f"{nombre}.schema.json"))


def errores_esquema(nombre: str, datos: dict) -> list[str]:
    return [f"{'/'.join(map(str, e.absolute_path)) or '(raíz)'}: {e.message}"
            for e in _validador(nombre).iter_errors(datos)]


# ---------- Banco ----------

def cargar_banco(r: Raiz) -> dict[str, dict]:
    casos = {}
    for p in sorted(r.banco.glob("*.json")):
        c = leer_json(p)
        errs = errores_esquema("caso", c)
        if errs:
            raise ValueError(f"{p.name}: " + "; ".join(errs))
        casos[c["caso"]] = c
    return casos


# ---------- Corridas ----------

def cargar_corridas(r: Raiz, validar: bool = True) -> list[dict]:
    filas = leer_jsonl(r.corridas)
    if validar:
        for i, f in enumerate(filas, 1):
            errs = errores_esquema("corrida", f)
            if errs:
                raise ValueError(f"corridas.jsonl línea {i}: " + "; ".join(errs))
    return filas


def lotes(corridas: list[dict]) -> dict[str, list[dict]]:
    """Agrupa por lote conservando el orden de aparición (el último es el más reciente)."""
    out: dict[str, list[dict]] = {}
    for c in corridas:
        out.setdefault(c["lote"], []).append(c)
    return out


# ---------- Lecciones ----------

def cargar_lecciones(carpeta: Path) -> list[dict]:
    return [leer_json(p) for p in sorted(Path(carpeta).glob("L*.json"),
                                          key=lambda p: int(p.stem[1:]) if p.stem[1:].isdigit() else 0)]


def ruta_leccion(r: Raiz, codigo: str) -> Path | None:
    for d in (r.candidatas, r.activas, r.rechazadas):
        p = d / f"{codigo}.json"
        if p.exists():
            return p
    return None


def siguiente_codigo(r: Raiz) -> str:
    nums = [int(p.stem[1:]) for d in (r.candidatas, r.activas, r.rechazadas)
            for p in d.glob("L*.json") if p.stem[1:].isdigit()]
    return f"L{max(nums, default=0) + 1}"


def versiones(r: Raiz) -> dict:
    r.asegurar()
    return leer_json(r.versiones)
