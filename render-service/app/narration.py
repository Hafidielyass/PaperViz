"""Text to speech, and the timing that follows from it.

The order here is the whole point. Beat durations used to be a guess — word
count over 2.5 words per second — and real speech does not obey that. Laying a
voice track over a video timed that way drifts within a single part and is
visibly out of step by the end.

So audio comes first: synthesise every line, measure what actually came back,
and let those measurements set the beat durations before a frame is drawn.
Video and audio are then the same length by construction rather than by luck.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import wave
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger(__name__)

VOICE_DIR = Path(os.getenv("PIPER_VOICE_DIR", "/opt/piper-voices"))
VOICE_NAME = os.getenv("PIPER_VOICE", "en_US-lessac-medium")

# Breathing room after each line so beats do not run into one another.
BEAT_PADDING_SECONDS = float(os.getenv("BEAT_PADDING_SECONDS", "0.6"))

# A beat still needs to be watchable even when its narration is one short word.
MIN_BEAT_SECONDS = 2.5

# Title card: no narration, just a held frame.
TITLE_CARD_SECONDS = 2.2


@dataclass
class Clip:
    """One synthesised line."""

    path: Path
    seconds: float


def voice_available() -> bool:
    return (VOICE_DIR / f"{VOICE_NAME}.onnx").is_file()


def _model_paths() -> tuple[Path, Path]:
    return VOICE_DIR / f"{VOICE_NAME}.onnx", VOICE_DIR / f"{VOICE_NAME}.onnx.json"


def synthesise(text: str, out_path: Path) -> Clip | None:
    """Renders one line to a wav file.

    Returns None rather than raising: a voice failure must degrade the part to
    silence, never lose it.
    """
    text = (text or "").strip()
    if not text:
        return None
    if not voice_available():
        log.warning("piper voice %s not present in %s", VOICE_NAME, VOICE_DIR)
        return None

    model, config = _model_paths()
    try:
        # The CLI is used rather than the Python API because piper's module
        # surface has moved between releases, while `piper -m ... -f ...` has
        # been stable. It also keeps ONNX runtime state out of this process.
        subprocess.run(
            ["piper", "--model", str(model), "--config", str(config),
             "--output_file", str(out_path)],
            input=text,
            text=True,
            capture_output=True,
            timeout=120,
            check=True,
        )
    except FileNotFoundError:
        log.warning("piper binary not on PATH; rendering will be silent")
        return None
    except subprocess.CalledProcessError as e:
        log.warning("piper failed on %r: %s", text[:60], (e.stderr or "")[-300:])
        return None
    except subprocess.TimeoutExpired:
        log.warning("piper timed out on %r", text[:60])
        return None

    seconds = wav_duration(out_path)
    if seconds is None or seconds <= 0:
        return None
    return Clip(path=out_path, seconds=seconds)


def wav_duration(path: Path) -> float | None:
    """Reads duration straight from the wav header; no subprocess needed."""
    try:
        with wave.open(str(path), "rb") as handle:
            frames = handle.getnframes()
            rate = handle.getframerate()
            return frames / float(rate) if rate else None
    except (OSError, wave.Error):
        return None


@dataclass
class NarrationPlan:
    """Beat durations derived from real audio, plus the clips to mux."""

    clips: list[Clip | None]
    durations: list[float]
    total_seconds: float
    spoken_beats: int

    @property
    def any_audio(self) -> bool:
        return any(clip is not None for clip in self.clips)


def build(storyboard: dict, workdir: Path) -> NarrationPlan:
    """Synthesises every beat and returns the timings the video must use."""
    beats = storyboard.get("beats") or []
    audio_dir = workdir / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True)

    clips: list[Clip | None] = []
    durations: list[float] = []

    for index, beat in enumerate(beats):
        clip = synthesise(beat.get("narration") or "", audio_dir / f"beat{index:02d}.wav")
        clips.append(clip)

        if clip is None:
            # No audio: fall back to whatever the storyboard asked for.
            requested = float(beat.get("seconds") or MIN_BEAT_SECONDS)
            durations.append(max(requested, MIN_BEAT_SECONDS))
        else:
            durations.append(max(clip.seconds + BEAT_PADDING_SECONDS, MIN_BEAT_SECONDS))

    spoken = sum(1 for c in clips if c is not None)
    total = sum(durations)
    if (storyboard.get("title") or "").strip():
        total += TITLE_CARD_SECONDS

    log.info("narration: %d/%d beats spoken, %.1fs of video", spoken, len(beats), total)
    return NarrationPlan(clips=clips, durations=durations,
                         total_seconds=total, spoken_beats=spoken)


def retime(storyboard: dict, plan: NarrationPlan) -> dict:
    """Returns a copy of the storyboard with beat durations set from the audio."""
    beats = storyboard.get("beats") or []
    retimed = []
    for index, beat in enumerate(beats):
        copy = dict(beat)
        if index < len(plan.durations):
            copy["seconds"] = round(plan.durations[index], 3)
        retimed.append(copy)

    out = dict(storyboard)
    out["beats"] = retimed
    out["totalSeconds"] = round(plan.total_seconds, 3)
    return out


def build_audio_track(plan: NarrationPlan, storyboard: dict, workdir: Path) -> Path | None:
    """Assembles one wav matching the video timeline exactly.

    Each beat contributes its clip followed by silence out to the beat's full
    duration, so a clip never bleeds into the next beat and the track lands on
    the same total length as the video.
    """
    if not plan.any_audio:
        return None
    if shutil.which("ffmpeg") is None:
        log.warning("ffmpeg missing; cannot assemble narration")
        return None

    pieces: list[Path] = []
    audio_dir = workdir / "audio"

    lead_in = TITLE_CARD_SECONDS if (storyboard.get("title") or "").strip() else 0.0
    if lead_in > 0:
        silence = audio_dir / "lead-in.wav"
        if _silence(silence, lead_in):
            pieces.append(silence)

    for index, clip in enumerate(plan.clips):
        target = plan.durations[index]
        if clip is None:
            gap = audio_dir / f"gap{index:02d}.wav"
            if _silence(gap, target):
                pieces.append(gap)
            continue

        pieces.append(clip.path)
        tail = target - clip.seconds
        if tail > 0.01:
            gap = audio_dir / f"tail{index:02d}.wav"
            if _silence(gap, tail):
                pieces.append(gap)

    if not pieces:
        return None

    # concat demuxer needs a list file; paths are ours, but quote them anyway.
    list_file = audio_dir / "concat.txt"
    list_file.write_text(
        "\n".join(f"file '{p.as_posix()}'" for p in pieces) + "\n", encoding="utf-8")

    track = audio_dir / "narration.wav"
    result = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0",
         "-i", str(list_file), "-ar", "22050", "-ac", "1", str(track)],
        capture_output=True, text=True, timeout=180, check=False,
    )
    if result.returncode != 0 or not track.is_file():
        log.warning("failed to assemble narration: %s", (result.stderr or "")[-300:])
        return None
    return track


def _silence(path: Path, seconds: float) -> bool:
    result = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi",
         "-i", "anullsrc=r=22050:cl=mono", "-t", f"{seconds:.3f}", str(path)],
        capture_output=True, text=True, timeout=60, check=False,
    )
    return result.returncode == 0 and path.is_file()


def mux(video: Path, audio: Path, out_path: Path) -> bool:
    """Lays the narration onto the rendered video.

    The video stream is copied rather than re-encoded — it is already what we
    want, and re-encoding would cost time and quality for nothing.
    """
    result = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-y",
         "-i", str(video), "-i", str(audio),
         "-c:v", "copy", "-c:a", "aac", "-b:a", "128k",
         "-map", "0:v:0", "-map", "1:a:0",
         # End with the video; a trailing sliver of silence is not worth padding.
         "-shortest", str(out_path)],
        capture_output=True, text=True, timeout=300, check=False,
    )
    if result.returncode != 0 or not out_path.is_file():
        log.warning("mux failed: %s", (result.stderr or "")[-300:])
        return False
    return True
