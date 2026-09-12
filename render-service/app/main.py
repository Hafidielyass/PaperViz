"""PaperViz render service.

Deliberately dumb: it renders what it is told to render. Every LLM call lives on
the Spring Boot side. Stage 1 exposes only health + capability reporting; the
/render endpoints arrive in stage 4.
"""

import os
import platform
import shutil
from pathlib import Path

from fastapi import FastAPI
from pydantic import BaseModel

MEDIA_ROOT = Path(os.getenv("MEDIA_ROOT", "/data/media"))

app = FastAPI(
    title="PaperViz Render Service",
    version="0.1.0",
    description="Manim scene rendering and TTS narration for PaperViz.",
)


class Health(BaseModel):
    status: str
    service: str
    version: str
    python: str
    media_root: str
    media_writable: bool
    capabilities: dict[str, bool]


def _media_writable() -> bool:
    try:
        MEDIA_ROOT.mkdir(parents=True, exist_ok=True)
        probe = MEDIA_ROOT / ".write-probe"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink()
        return True
    except OSError:
        return False


def _capabilities() -> dict[str, bool]:
    """What this image can actually do. Flips to True as stages 4 and 5 land."""
    return {
        # stage 4
        "manim": shutil.which("manim") is not None,
        "ffmpeg": shutil.which("ffmpeg") is not None,
        "latex": shutil.which("latex") is not None,
        # stage 5
        "piper": shutil.which("piper") is not None,
    }


@app.get("/health", response_model=Health)
def health() -> Health:
    return Health(
        status="UP",
        service="paperviz-render",
        version=app.version,
        python=platform.python_version(),
        media_root=str(MEDIA_ROOT),
        media_writable=_media_writable(),
        capabilities=_capabilities(),
    )
