# Stage 1 image: FastAPI only, so the stack comes up fast.
# Stage 4 adds manim + ffmpeg + a LaTeX subset; stage 5 adds Piper TTS.
FROM python:3.12-slim
WORKDIR /srv

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# The media volume is shared with the backend. Both images pin the same GID and
# the directory is group-writable + setgid, so whichever container initialises
# the named volume, the other can still write into it.
RUN groupadd --gid 10100 pvmedia \
 && useradd --system --create-home --uid 10002 --gid 10100 render \
 && mkdir -p /data/media \
 && chown -R render:pvmedia /data/media /srv \
 && chmod 2775 /data/media
USER render

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
