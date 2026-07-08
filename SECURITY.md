# Security Notes

This project is an **educational OSGi showcase intended to run on `localhost`**. Its
HTTP surface (Felix Jetty on port 8080) is deliberately unauthenticated so the demo is
frictionless to explore. That is fine on a trusted single-user machine and **not safe to
expose to a network**. This document records the known exposures and what to do before
putting any of this where others can reach it.

The build already includes the cheap, always-worthwhile defences (request-body size caps
on every servlet, so a single large POST can't OOM the shared Jetty). Everything else
below is documented rather than implemented, by design.

## Known exposures (localhost-only assumptions)

| # | Surface | Exposure |
|---|---------|----------|
| 1 | `POST /mcp`, `POST /llm/chat` | **No authentication, no `Origin`/CSRF check.** Anyone who can reach port 8080 — including a malicious web page open in a browser on the same machine, via a plain form POST — can invoke every registered MCP tool and drive `/llm/chat`, spending your OpenRouter credits. |
| 2 | `ingest_documents` MCP tool | Accepts **any absolute filesystem path** with no allowlist root. Combined with #1, a remote caller can ingest arbitrary readable files (`/Users/you/Documents`, …) and then read their contents back through `document_search` — an arbitrary-file-read primitive. |
| 3 | `http_fetch` MCP tool | Only checks the URL scheme. It will fetch **internal/link-local addresses** (`http://169.254.169.254/…`, `http://localhost:8080/system/console`) and follows redirects — an SSRF primitive, reachable both via #1 and via prompt-injection of the LLM. |
| 4 | `settings/chatbot.properties` | If the user saves an API key in the Swing **Settings** dialog it is written to this file in **plaintext** with default permissions, and the dialog pre-fills the real key back into the password field. A key sourced from env/`-D` is not persisted. |
| 5 | Logging | Chat prompts, LLM responses, and **retrieved document text** are logged at `INFO` (the default level), so document contents can land in `consoleLog`/log files. No API key is logged. |
| 6 | Felix WebConsole | Ships with the well-known demo credentials `admin`/`admin` (`distribution/configuration/config.ini`). |

## Before exposing this beyond localhost

If you ever bind to a non-loopback interface, do all of the following first:

- **Authenticate the HTTP surface.** Require a shared-secret header (or reuse the
  WebConsole's basic-auth approach) on `/mcp` and `/llm/chat`, and reject non-`application/json`
  content types. Add an `Origin` allowlist to defend against browser CSRF.
- **Constrain `ingest_documents`** to a configured allowlist root — resolve the real path
  and require it to start with the allowed directory. Blur "path does not exist" style
  errors so they can't be used as a filesystem-probing oracle.
- **Lock down `http_fetch`.** Resolve the host and **block private, loopback, and
  link-local ranges** (10/8, 172.16/12, 192.168/16, 127/8, 169.254/16, `::1`, fc00::/7),
  and disable redirects (`setInstanceFollowRedirects(false)`).
- **Don't persist keys** that came from env/`-D`; when persisting a user-entered key, set
  owner-only file permissions and show a placeholder (not the stored key) in the dialog.
- **Demote content logging** (chat text, retrieved documents) to `DEBUG`.
- **Change the WebConsole credentials** from `admin`/`admin`, or drop the WebConsole from
  the product entirely.

The relevant code lives in `com.kk.pde.ds.mcp.server` (servlet + tools),
`com.kk.pde.ds.mcp.llm` (agent + `/llm/chat`), `com.kk.pde.ds.chatbot` (`ChatConfig`,
`SettingsDialog`), and `com.kk.pde.ds.rag` (ingestion).
