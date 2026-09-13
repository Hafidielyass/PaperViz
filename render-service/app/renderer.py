"""Runs Manim and returns a video file.

Manim is invoked as a subprocess rather than in-process, for three reasons that
all matter here: a segfault in Cairo or a TeX crash cannot take the API down
with it, each job gets a clean global state (Manim keeps a lot in module-level
config), and the same path will later run LLM-generated code, where isolation
stops being a nicety.
"""

from __future__ import annotations

import json
import logging
import os
import shutil
import subprocess
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger(__name__)

MEDIA_ROOT = Path(os.getenv("MEDIA_ROOT", "/data/media"))
RENDER_DIR = MEDIA_ROOT / "renders"

# Wall-clock ceiling for one render. Manim can spin for a long time on a scene
# that will never finish; failing at ten minutes beats blocking the queue.
RENDER_TIMEOUT_SECONDS = int(os.getenv("RENDER_TIMEOUT_SECONDS", "600"))

QUALITY_FLAGS = {
    "low": "-ql",       # 854x480 @ 15fps  — fast, for iteration
    "medium": "-qm",    # 1280x720 @ 30fps — the default for delivery
    "high": "-qh",      # 1920x1080 @ 60fps
}

# The scene module reads its storyboard from a JSON file beside it, so nothing
# has to be escaped into generated source.
ENTRYPOINT = '''
import json, os, sys
sys.path.insert(0, "/srv")
from app.scenes import StoryboardScene

with open(os.environ["STORYBOARD_PATH"], encoding="utf-8") as handle:
    _STORYBOARD = json.load(handle)


class PaperVizScene(StoryboardScene):
    storyboard = _STORYBOARD
'''


@dataclass
class RenderResult:
    ok: bool
    video_path: str | None
    duration_seconds: float | None
    elapsed_seconds: float
    log_tail: str
    error: str | None = None


def expected_duration(storyboard: dict) -> float:
    """Runtime the storyboard asks for, including the title card."""
    beats = storyboard.get("beats") or []
    total = sum(float(b.get("seconds") or 0) for b in beats)
    if (storyboard.get("title") or "").strip():
        total += 2.2
    return total


def render(storyboard: dict, render_id: str, quality: str = "medium") -> RenderResult:
    """Renders one storyboard to MP4 under the shared media volume."""
    started = time.monotonic()
    RENDER_DIR.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="pvrender-") as workdir:
        work = Path(workdir)
        storyboard_path = work / "storyboard.json"
        storyboard_path.write_text(json.dumps(storyboard), encoding="utf-8")

        scene_file = work / "pv_scene.py"
        scene_file.write_text(ENTRYPOINT, encoding="utf-8")

        flag = QUALITY_FLAGS.get(quality, QUALITY_FLAGS["medium"])
        command = [
            "manim", "render", flag,
            "--media_dir", str(work / "media"),
            "--output_file", "scene",
            "--disable_caching",
            str(scene_file), "PaperVizScene",
        ]

        env = {
            **os.environ,
            "STORYBOARD_PATH": str(storyboard_path),
            # Keep Manim's caches inside the throwaway workdir.
            "XDG_CACHE_HOME": str(work / "cache"),
        }

        log.info("rendering %s at %s quality", render_id, quality)
        try:
            completed = subprocess.run(
                command,
                cwd=work,
                env=env,
                capture_output=True,
                text=True,
                timeout=RENDER_TIMEOUT_SECONDS,
                check=False,
            )
        except subprocess.TimeoutExpired:
            elapsed = time.monotonic() - started
            return RenderResult(
                ok=False, video_path=None, duration_seconds=None,
                elapsed_seconds=elapsed, log_tail="",
                error=f"Render exceeded {RENDER_TIMEOUT_SECONDS}s and was killed.",
            )

        output = (completed.stdout or "") + (completed.stderr or "")
        tail = output[-4000:]

        if completed.returncode != 0:
            elapsed = time.monotonic() - started
            log.error("manim failed for %s: %s", render_id, tail[-800:])
            return RenderResult(
                ok=False, video_path=None, duration_seconds=None,
                elapsed_seconds=elapsed, log_tail=tail,
                error=_first_error_line(output) or "Manim exited non-zero.",
            )

        produced = _find_video(work / "media")
        if produced is None:
            elapsed = time.monotonic() - started
            return RenderResult(
                ok=False, video_path=None, duration_seconds=None,
                elapsed_seconds=elapsed, log_tail=tail,
                error="Manim reported success but produced no video file.",
            )

        target = RENDER_DIR / f"{render_id}.mp4"
        shutil.move(str(produced), target)

        elapsed = time.monotonic() - started
        log.info("rendered %s in %.1fs -> %s", render_id, elapsed, target)
        return RenderResult(
            ok=True,
            video_path=str(target.relative_to(MEDIA_ROOT)).replace("\\", "/"),
            duration_seconds=_probe_duration(target),
            elapsed_seconds=elapsed,
            log_tail=tail,
        )


def _find_video(media_dir: Path) -> Path | None:
    """Manim nests output under media/videos/<file>/<quality>/."""
    candidates = sorted(media_dir.rglob("*.mp4"), key=lambda p: p.stat().st_mtime, reverse=True)
    return candidates[0] if candidates else None


def _probe_duration(video: Path) -> float | None:
    """Actual duration from ffprobe. The storyboard's figure is an intention."""
    try:
        result = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(video)],
            capture_output=True, text=True, timeout=30, check=False,
        )
        return round(float(result.stdout.strip()), 2)
    except (ValueError, subprocess.SubprocessError):
        return None


def _first_error_line(output: str) -> str | None:
    """Pulls something human out of a Manim traceback for the UI."""
    interesting = ("Error", "error:", "Exception", "LaTeX", "!  ")
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    for line in reversed(lines):
        if any(token in line for token in interesting):
            return line[:300]
    return lines[-1][:300] if lines else None
