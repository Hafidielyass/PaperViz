# Open-access policy

PaperViz will process a paper by exactly two routes. This document is the
specification the `ingestion` module implements; the rules are enforced
server-side so they hold for any caller, not just our own UI.

## Route 1 — user upload

The user supplies the bytes. We assume they have legitimate access (their
university library, their own copy, a preprint they downloaded).

What we guarantee:

- The file is stored under the private media root, keyed by owner and content hash.
- It is served back only to its owner, through the API, never as a static path.
- It is never re-hosted, indexed, listed, or shared between users.
- Deleting the paper deletes the bytes.

## Route 2 — open-access URL

We fetch on the user's behalf **only** when the source is known to be open access.

### Step 1 — host allowlist

A URL whose host matches the allowlist is fetched directly:

| Source | Hosts |
|---|---|
| arXiv | `arxiv.org`, `export.arxiv.org` |
| bioRxiv | `biorxiv.org`, `connect.biorxiv.org` |
| medRxiv | `medrxiv.org` |
| PubMed Central | `ncbi.nlm.nih.gov/pmc`, `europepmc.org` |
| OpenReview | `openreview.net` |
| SSRN | `ssrn.com`, `papers.ssrn.com` |
| DOAJ-indexed journals | resolved per-request against the DOAJ API |

Subdomain matching is suffix-based on a registrable-domain boundary, so
`arxiv.org.evil.example` does **not** match.

### Step 2 — DOI + Unpaywall

For any other host we do not fetch the page. We extract a DOI (from the URL path,
query string, or a `doi:` prefix) and call:

```
GET https://api.unpaywall.org/v2/{doi}?email={contact}
```

- `is_oa: true` and a usable `best_oa_location.url_for_pdf` → we fetch **that**
  URL, not the URL the user pasted, and record the location as
  `papers.open_access_proof`.
- Otherwise → HTTP 422 with a message telling the user to upload the PDF.

### What we deliberately do not build

- No generic HTML scraping of publisher pages.
- No paywall bypass, no proxy chaining, no Sci-Hub-style mirrors.
- No cookie or credential replay against publisher sites.
- No fetch of a URL that neither matched the allowlist nor passed Unpaywall.

### Fetch hygiene

- A descriptive `User-Agent` with a contact address (`paperviz.unpaywall-email`).
- Redirects followed only while the destination still satisfies step 1 or 2.
- Response must be `application/pdf` (or a known LaTeX source archive) under the
  configured size cap.
- Private, loopback, and link-local addresses are refused, so a crafted URL
  cannot turn the backend into an SSRF pivot.
