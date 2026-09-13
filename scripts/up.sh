#!/usr/bin/env bash
# Brings up PaperViz, using the GPU overlay when an NVIDIA GPU is actually usable.
#
# Why this exists: on a machine where Docker's VM is small, running the model on
# CPU does not merely run slowly — llama-server gets OOM-killed mid-load and the
# API returns "signal: killed". Ollama's own health check still passes, because
# reachability and capability are different things. Picking the overlay
# automatically stops that from being a thing anyone has to remember.
#
#   ./scripts/up.sh              # auto-detect
#   ./scripts/up.sh --cpu        # force CPU
#   ./scripts/up.sh --gpu        # force GPU, fail loudly if unavailable
set -euo pipefail

cd "$(dirname "$0")/.."

MODE="auto"
case "${1:-}" in
  --cpu) MODE="cpu"; shift ;;
  --gpu) MODE="gpu"; shift ;;
  "")    ;;
  *)     echo "usage: $0 [--cpu|--gpu] [extra docker compose args]" >&2; exit 2 ;;
esac

gpu_available() {
  # Ask Docker to actually run something against the GPU. Checking for
  # nvidia-smi on the host proves nothing about the container runtime.
  docker run --rm --gpus all ubuntu:24.04 nvidia-smi -L >/dev/null 2>&1
}

FILES=(-f docker-compose.yml)

case "$MODE" in
  gpu)
    if ! gpu_available; then
      echo "ERROR: --gpu requested but the container runtime cannot reach an NVIDIA GPU." >&2
      echo "       Check Docker Desktop is on the WSL2 backend with a current NVIDIA driver." >&2
      exit 1
    fi
    FILES+=(-f docker-compose.gpu.yml)
    echo "==> GPU mode (forced)"
    ;;
  cpu)
    echo "==> CPU mode (forced)"
    echo "    Note: the 8B model needs ~5.5 GiB. If Docker's VM is smaller than that"
    echo "    once GROBID is loaded, inference will be OOM-killed."
    ;;
  auto)
    if gpu_available; then
      FILES+=(-f docker-compose.gpu.yml)
      echo "==> NVIDIA GPU detected, using the GPU overlay"
    else
      echo "==> No usable GPU, falling back to CPU"
      echo "    Inference may be OOM-killed if Docker's VM is under ~7 GiB."
    fi
    ;;
esac

set -x
docker compose "${FILES[@]}" up -d "$@"
