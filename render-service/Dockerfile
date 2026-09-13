# syntax=docker/dockerfile:1
# Manim rendering service.
#
# Built on the official Manim image rather than installing TeX ourselves. That
# is not a style preference: on this machine the Debian mirror runs at roughly
# 190 KB/s, which makes a 1.5 GB TeX Live install a two-hour build, while Docker
# Hub saturates the connection. The upstream image already carries Manim,
# ffmpeg, Cairo, Pango and a working LaTeX toolchain, all pinned together and
# tested by the Manim project.
#
# The pinned tag matters: this image is the render contract, and an unpinned
# :latest would change Manim's API under us.
FROM manimcommunity/manim:v0.18.1

USER root
WORKDIR /srv

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    MPLBACKEND=Agg

# Only the web layer; manim and its dependencies come from the base image.
COPY requirements-service.txt .
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install -r requirements-service.txt

# Bake the voice into the image so a render never needs the network.
# lessac-medium is the best quality/size trade at ~63 MB; the .json beside it
# carries the phoneme config and is not optional.
ARG PIPER_VOICE=en_US-lessac-medium
ARG PIPER_VOICE_PATH=en/en_US/lessac/medium
ENV PIPER_VOICE_DIR=/opt/piper-voices
# Downloaded with Python rather than curl: the base image ships neither curl nor
# wget, and Python is the one thing it is guaranteed to have.
RUN mkdir -p "$PIPER_VOICE_DIR" && python - <<'PYEOF'
import os, urllib.request
voice = os.environ["PIPER_VOICE"]
path = os.environ["PIPER_VOICE_PATH"]
out = os.environ["PIPER_VOICE_DIR"]
base = f"https://huggingface.co/rhasspy/piper-voices/resolve/main/{path}/{voice}"
for suffix in (".onnx", ".onnx.json"):
    target = os.path.join(out, voice + suffix)
    print("fetching", base + suffix, flush=True)
    urllib.request.urlretrieve(base + suffix, target)
    print("  ->", target, os.path.getsize(target), "bytes", flush=True)
PYEOF
ENV PIPER_VOICE=$PIPER_VOICE

COPY app ./app

# The base image ships a "manimuser"; reuse it but put it in the shared media
# group so backend and render service can both write the volume.
# See backend/Dockerfile for why the GID is pinned.
RUN groupadd --gid 10100 pvmedia \
 && usermod -aG pvmedia manimuser \
 && mkdir -p /data/media \
 && chown -R manimuser:pvmedia /data/media /srv \
 && chmod 2775 /data/media

USER manimuser
ENV HOME=/home/manimuser

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
