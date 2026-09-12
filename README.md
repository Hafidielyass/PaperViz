# PaperViz

Turns a research paper into a scroll-driven visual explainer: the paper's text on
one side, an AI-generated Manim animation with narration playing beside each
section as you scroll into it.

Everything runs in Docker. `docker compose up` is the only command you need — no
host installs beyond Docker itself, Ollama included.

---

## Two ways in, and only two

**1. Upload.** You upload a PDF you already have legitimate access to. It is
processed for your account only, stored under a private media root, and never
re-hosted or served publicly.

**2. Open-access URL.** You paste a link. The backend fetches it *only* if the
host is on the open-access allowlist:

`arxiv.org` · `biorxiv.org` · `medrxiv.org` · PubMed Central · OpenReview ·
SSRN · DOAJ-indexed journals

For anything else, the backend extracts the DOI and asks the
[Unpaywall API](https://unpaywall.org/products/api) whether a legal open-access
copy exists. No OA version, no fetch — the request is rejected with a message
telling you to upload the PDF instead.

There is deliberately **no** generic scraper and **no** paywall-bypass path. The
allowlist and the Unpaywall check live in the backend (`ingestion` module), not
in the UI, so they cannot be skipped by calling the API directly.

---

## Quick start

```bash
cp .env.example .env
docker compose up --build
```

Then open <http://localhost:4200>.

First run downloads ~5 GB of Ollama models and builds four images; budget 15–25
minutes. Later runs start in under a minute.

| Service | URL | What it is |
|---|---|---|
| frontend | http://localhost:4200 | Angular 20, served by nginx, proxies `/api` |
| backend | http://localhost:8080 | Spring Boot 3.5 API |
| render-service | http://localhost:8000/docs | FastAPI — Manim + TTS |
| grobid | http://localhost:8070 | PDF → structured TEI-XML |
| ollama | http://localhost:11434 | Local LLM runtime |
| postgres | localhost:5432 | Postgres 17 + pgvector |

Health of the whole stack in one call:

```bash
curl http://localhost:8080/api/health
```

Or run the smoke test, which checks every service and prints the aggregate view:

```bash
bash scripts/smoke-test.sh
```

Note: `docker compose up --wait` returns as soon as the one-shot `ollama-init`
service exits, which can be before `backend` and `frontend` finish their health
checks. Give it another ~30s, or use the smoke test to confirm.

To use the GPU for Ollama, see [docs/OLLAMA.md](docs/OLLAMA.md):

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up
```

---

## Architecture

```
Angular (nginx) ──/api──> Spring Boot ──> Postgres + pgvector
                              │
                              ├──> GROBID           (PDF → sections, equations)
                              ├──> Ollama           (via Spring AI ChatClient)
                              └──> render-service   (Manim video + Piper TTS)
```

All LLM calls live in Spring Boot behind Spring AI's `ChatClient`. The Python
service is deliberately dumb: it accepts a validated scene script and returns a
rendered file. That keeps one coherent stack and one place where prompts,
structured output, and retries are managed.

### Pipeline

1. **Ingest** — upload or allowlisted fetch → sha256 → store raw file
2. **Parse** — GROBID TEI-XML (LaTeX source preferred when arXiv offers it)
3. **Segment** — logical sections with types (abstract, method, results, …)
4. **Extract concepts** — per section, the LLM flags what is worth animating
5. **Storyboard** — structured JSON: scene description, visual beats, narration
6. **Generate Manim code** — from the storyboard
7. **Validate** — syntax → layout sanity → narration timing → sandboxed run,
   with LLM correction on failure, capped at 3 retries
8. **Render** — Manim video + TTS narration, cached by paper+storyboard hash
9. **Read** — Angular scrollytelling reader, KaTeX math, Intersection-Observer
   triggered playback

### Layout

```
backend/          Spring Boot — ingestion, parsing, ai, rendering, api modules
frontend/         Angular 20 standalone + Tailwind
render-service/   FastAPI — Manim rendering and TTS
docs/             Ollama setup, open-access policy
docker-compose.yml
```

---

## Testing

Backend unit tests run inside the image build, which is the only place in this
environment with a route to Maven Central:

```bash
docker compose build backend
```

Skip them for a fast iteration build:

```bash
docker compose build backend --build-arg SKIP_TESTS=true
```

## Auth

v1 runs **single-user with no login**. Every paper is owned by a seeded `local`
user (`00000000-…-0001`). The `users` table and the `papers.owner_id` foreign key
exist from the first migration, and every repository query is already scoped by
owner — so enabling real auth later is a controller and security-config change,
not a schema migration.

---

## Build status

| Stage | What | State |
|---|---|---|
| 1 | Full containerised stack + end-to-end health check | ✅ done |
| 2 | Upload → GROBID → sections in Postgres → shown in Angular | ✅ done |
| 3 | Spring AI + Ollama: concept extraction + storyboard JSON | ⬜ |
| 4 | Manim microservice: code gen, validation gates, render | ⬜ |
| 5 | TTS narration synced to video | ⬜ |
| 6 | Open-access URL path + Unpaywall | ⬜ |
| 7 | Caching, job polling, scrollytelling scroll-sync | ⬜ |
| 8 | Error handling, retry UI, optional auth | ⬜ |

---

## Development without Docker

The containers are the source of truth, but for a fast frontend loop:

```bash
cd frontend && npm start     # proxies /api to localhost:8080
```

and run just the backing services:

```bash
docker compose up postgres ollama grobid render-service
```
