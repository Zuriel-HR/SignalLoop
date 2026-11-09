import shutil
import sys
from pathlib import Path

import pytest

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ))
sys.path.insert(0, str(RAIZ / "herramientas"))


@pytest.fixture
def proyecto(tmp_path):
    """Copia limpia del proyecto: banco y contrato, sin lecciones ni corridas."""
    for d in ("banco", "contrato"):
        shutil.copytree(RAIZ / d, tmp_path / d)
    from aprendiz.archivos import Raiz
    r = Raiz(tmp_path)
    r.asegurar()
    return r
