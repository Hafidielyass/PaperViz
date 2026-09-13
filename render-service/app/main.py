"""PaperViz render service.

Deliberately dumb: it renders what it is told to render. Every LLM call lives on
the Spring Boot side, and every decision about *what* a beat shows was made
before the storyboard arrived here.
"""

import logging
import os
import platform
import shutil
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from app import renderer

logging.basicConfig(level=os.getenv("LOG_LEVEL", "info").upper())
log = logging.getLogger(__name__)

MEDIA_ROOT = Path(os.getenv("MEDIA_ROOT", "/data/media"))

app = FastAPI(
    title="PaperViz Render Service",
    version="0.2.0",
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


class RenderRequest(BaseModel):
    """One storyboard to render.

    The storyboard is passed through as a plain dict rather than a typed model:
    the backend already validated it against the scene spec, and duplicating
    that schema here would mean two definitions to keep in step.
    """

    render_id: str = Field(..., min_length=1, max_length=100)
    storyboard: dict[str, Any]
    quality: str = Field(default="medium", pattern="^(low|medium|high)$")


class RenderResponse(BaseModel):
    ok: bool
    render_id: str
    video_path: str | None = None
    duration_seconds: float | None = None
    expected_seconds: float | None = None
    elapsed_seconds: float
    error: str | None = None
    log_tail: str | None = None


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
    """What this image can actually do, checked rather than assumed."""
    return {
        "manim": shutil.which("manim") is not None,
        "ffmpeg": shutil.which("ffmpeg") is not None,
        "latex": shutil.which("latex") is not None,
        "dvisvgm": shutil.which("dvisvgm") is not None,
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


@app.post("/render", response_model=RenderResponse)
def render(request: RenderRequest) -> RenderResponse:
    """Renders a storyboard to MP4. Synchronous — the backend queues these."""
    beats = request.storyboard.get("beats") or []
    if not beats:
        raise HTTPException(status_code=422, detail="Storyboard has no beats to render.")

    # Keep the id filesystem-safe; it becomes a filename.
    if not all(c.isalnum() or c in "-_" for c in request.render_id):
        raise HTTPException(status_code=422, detail="render_id must be alphanumeric, - or _.")

    result = renderer.render(request.storyboard, request.render_id, request.quality)

    return RenderResponse(
        ok=result.ok,
        render_id=request.render_id,
        video_path=result.video_path,
        duration_seconds=result.duration_seconds,
        expected_seconds=renderer.expected_duration(request.storyboard),
        elapsed_seconds=round(result.elapsed_seconds, 2),
        error=result.error,
        log_tail=result.log_tail[-1500:] if result.log_tail else None,
    )
