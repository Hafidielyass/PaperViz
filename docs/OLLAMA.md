# Ollama setup

You do **not** need Ollama installed on the host. Compose runs the official
`ollama/ollama` image and a one-shot `ollama-init` service pulls the models into a
named volume the first time you bring the stack up.

## What happens automatically

```bash
docker compose up
```

1. `ollama` starts and serves the HTTP API on `11434`.
2. `ollama-init` waits for it, then runs `ollama pull llama3.1:8b` and
   `ollama pull nomic-embed-text`, and exits.
3. Models live in the `ollama` volume, so later `up` runs skip the download.

Expect ~5 GB of download on the first run.

## Verifying by hand

```bash
docker compose exec ollama ollama list
```

```bash
curl http://localhost:11434/api/tags
```

Ask it something, end to end:

```bash
curl http://localhost:11434/api/generate -d '{"model":"llama3.1:8b","prompt":"Say OK","stream":false}'
```

## Model choice

| Model | Size (Q4) | Fits 8 GB VRAM | Notes |
|---|---|---|---|
| `llama3.1:8b` | ~4.7 GB | yes, fully | **Default.** Good instruction-following, reliable JSON. |
| `qwen2.5:14b` | ~9 GB | no, spills to CPU | Better at structured output and code, but roughly 3–5× slower on this machine. |
| `qwen2.5:7b` | ~4.7 GB | yes | Strong alternative if Llama's JSON drifts. |
| `nomic-embed-text` | ~0.3 GB | yes | Embeddings for the pgvector store (768-dim). |

Swapping models is **config only** — no code changes:

```bash
# .env
OLLAMA_CHAT_MODEL=qwen2.5:14b
```

Then `docker compose up -d ollama-init backend` to pull the new model and restart the API.

If you change the *embedding* model, also update
`spring.ai.vectorstore.pgvector.dimensions` in `backend/src/main/resources/application.yml`
to match, and drop the `vector_store` table so it is recreated at the new width.

## GPU acceleration (RTX 4070, 8 GB)

The default compose file runs Ollama on CPU so the stack starts on any machine.
To hand it the GPU:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up
```

Requires Docker Desktop with the WSL2 backend and a current NVIDIA driver
(the NVIDIA Container Toolkit ships inside Docker Desktop's WSL distro). Confirm with:

```bash
docker compose exec ollama nvidia-smi
```

With `llama3.1:8b` fully resident in VRAM, expect roughly 40–60 tokens/s versus
5–10 tokens/s on CPU — which matters a lot, because the pipeline makes one LLM
call per section for concept extraction and several more per animated concept.

## Spring AI wiring

`backend/src/main/resources/application.yml` holds the config; compose overrides the
URL and model names via environment variables:

```yaml
spring:
  ai:
    ollama:
      base-url: ${SPRING_AI_OLLAMA_BASE_URL:http://localhost:11434}
      init:
        pull-model-strategy: never   # ollama-init owns pulling
      chat:
        options:
          model: ${SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL:llama3.1:8b}
          temperature: 0.2
      embedding:
        options:
          model: ${SPRING_AI_OLLAMA_EMBEDDING_OPTIONS_MODEL:nomic-embed-text}
```

`pull-model-strategy: never` matters: without it Spring AI may try to pull the model
itself on startup and the API container will appear to hang while it downloads.
