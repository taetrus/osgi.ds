# com.kk.pde.ds.rag — Document Q&A (RAG) for the chatbot

Adds retrieval-augmented generation: ingest local documents and let the LLM
retrieve grounded passages **as a tool** (search-as-tool). The existing chat/tool
loop decides when to call it — there is no always-on inject-then-answer pipeline.

## What it does

```
DocumentSearchTool (IMcpTool "document_search")  ← the LLM's retrieval entry point
        │
DocumentIngestionService  — parse → chunk → embed → store, and search
   ├─ DocumentParser  (MultiFormatDocumentParser)
   ├─ TextChunker     (SlidingWindowChunker)
   ├─ EmbeddingClient (OpenAiEmbeddingClient → /v1/embeddings)
   └─ VectorStore  ← InMemoryVectorStore (brute-force cosine over float[])
IngestDocumentsTool (IMcpTool "ingest_documents")  ← load a folder on demand
```

Every collaborator is an OSGi service behind an interface in `…rag.api`, so any
piece (notably `VectorStore` → pgvector) can be swapped without touching callers.

## Supported formats

`.pdf` (Apache PDFBox 2.0.x) · `.docx` / `.pptx` (read directly from the OOXML zip —
no Apache POI, no extra dependencies) · `.html` / `.htm` · `.txt` / `.md` · standalone
image files `.png` / `.jpg` / `.jpeg` / `.tif` / `.tiff` / `.bmp` / `.gif` (OCR'd whole;
skipped with a warning when OCR is unavailable).

## Configuration (system properties / env vars)

Mirrors the existing chat client exactly, so the embeddings endpoint uses the
**same** base URL and credentials.

| Property | Env var | Default | Purpose |
|----------|---------|---------|---------|
| `openrouter.api.key` | `OPENROUTER_API_KEY` | — | API key (required) |
| `openrouter.base.url` | — | `https://openrouter.ai/api/v1` | embeddings POSTed to `{base}/embeddings` |
| `openrouter.embeddings.model` | `OPENROUTER_EMBEDDINGS_MODEL` | `intfloat/multilingual-e5-large` | embedding model |
| `rag.docs.dir` | — | — | folder auto-ingested at startup (recursive) |
| `rag.embedding.query.prefix` | — | `query: ` | E5 query role prefix (blank it for non-E5 models) |
| `rag.embedding.passage.prefix` | — | `passage: ` | E5 passage role prefix |
| `rag.chunk.target.tokens` | — | `400` | chunk size (kept < E5's 512 window) |
| `rag.chunk.overlap.tokens` | — | `50` | chunk overlap (~12%) |

> **E5 note:** `multilingual-e5-large` is asymmetric and prefix-sensitive — queries
> get `query: `, passages get `passage: `. It also truncates at 512 tokens, so the
> chunk target defaults to 400. Switching to `qwen3-embedding-8B`? Set the model and
> blank both prefixes.

## Running it end-to-end

```bash
mvn clean verify                         # builds the p2 repo + product
export OPENROUTER_API_KEY=your_key
./distribution/scripts/run.sh \
    -Dopenrouter.base.url=https://your-proxy/v1 \
    -Dopenrouter.embeddings.model=intfloat/multilingual-e5-large \
    -Drag.docs.dir=/path/to/your/documents
```

On startup you'll see each component activate and:

```
DocumentIngestionService - Auto-ingesting documents from /path/to/your/documents
DocumentIngestionService - Auto-ingest complete: Ingested N file(s), 0 failed, M chunk(s) added.
```

Then in the chatbot, ask a question about your documents — the model calls
`document_search` and answers with citations like `report.pdf (chunk 3)`.

### Ingest more at runtime

Ask the chatbot to ingest a path (it calls the `ingest_documents` tool), or hit
the MCP server directly:

```bash
curl -s -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' -d \
 '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ingest_documents","arguments":{"path":"/path/to/docs"}}}'

curl -s -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' -d \
 '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"document_search","arguments":{"query":"your question","top_k":"5"}}}'
```

## How it was verified

- **Build:** `mvn clean verify` — full Tycho reactor + product assembly green.
- **Runtime wiring:** launched the product; all seven DS components activate and
  both tools register and appear in the MCP `tools/list`. Logging (Logback 1.2.11)
  is unaffected.
- **Pipeline logic (offline):** parser correctly extracts `.txt` / `.html` / `.docx`;
  chunker produces overlapping chunks with citations; `InMemoryVectorStore` cosine
  ranking is correct.
- **End-to-end embed + retrieve:** against a mock OpenAI-compatible `/v1/embeddings`
  server, auto-ingest reported `Ingested 3 file(s), 0 failed, 3 chunk(s)` and
  `document_search` returned the passages with citations and scores.
- **Not verified here:** semantic ranking quality with the real `multilingual-e5-large`
  endpoint (needs the airgapped corporate proxy) — only the plumbing was exercised
  with a character-frequency mock.

## Airgap

No component reaches the public internet. The only network call is to the
configured embeddings endpoint. The launch scripts also set
`-Djavax.xml.accessExternal*=` to forbid external DTD/schema fetching during any
XML parsing.

## Why not Apache Tika?

Tika was the original plan, but every Tika release hard-requires **slf4j 2.0**,
which conflicts with this project's slf4j 1.7 / Logback 1.2 stack and breaks Felix
Health Check (it pins `org.slf4j.helpers [1.7,2.0)`). Rather than migrate the whole
runtime's logging (logback 1.3 + Aries SPI Fly + Felix HC upgrades), we extract
directly: PDFBox for PDF (logs via commons-logging, not slf4j) and a small OOXML
zip reader for Office formats. Zero disruption to the existing stack.
