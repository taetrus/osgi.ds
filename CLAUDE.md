# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an OSGi Declarative Services (DS) project built with Maven Tycho 4.0.13. It demonstrates service-oriented architecture with clear separation between API contracts, implementations, and consumers. The build produces a p2 repository and platform-specific product archives.

> **Security:** The HTTP surface (port 8080) is intentionally unauthenticated and is meant for **localhost only**. See [`SECURITY.md`](SECURITY.md) for the known exposures (`/mcp`, `/llm/chat`, `ingest_documents`, `http_fetch`) and the checklist to complete before exposing any of it beyond localhost.

## Additional Instructions

For every project, write a detailed FOR[kerem].md file that explains the whole project in plain language.

Explain the technical architecture, the structure of the codebase and how the various parts are connected, the technologies used, why we made these technical decisions, and lessons I can learn from it (this should include the bugs we ran into and how we fixed them, potential pitfalls and how to avoid them in the future, new technologies used, how good engineers think and work, best practices, etc).

It should be very engaging to read; don't make it sound like boring technical documentation/textbook. Where appropriate, use analogies and anecdotes to make it more understandable and memorable.

## Build Commands

```bash
# Full build (recommended for CI/CD)
mvn clean verify

# Quick build without tests
mvn clean package -DskipTests

# Build specific module
mvn clean package -pl com.kk.pde.ds.api

# Resume failed build from distribution
mvn verify -rf :distribution
```

## Build Outputs

After a successful build:
- **p2 Repository:** `distribution/target/repository/`
- **Product Archives:**
  - `distribution/target/products/com.kk.pde.ds.product-linux.gtk.x86_64.tar.gz`
  - `distribution/target/products/com.kk.pde.ds.product-win32.win32.x86_64.zip`
  - `distribution/target/products/com.kk.pde.ds.product-macosx.cocoa.x86_64.tar.gz`

## Running the Application

> **Java requirement**: Java 8+ required at runtime.

**Simplest — run directly from the source tree (scripts auto-detect OS and product path):**

```bash
# macOS / Linux (set OPENROUTER_API_KEY env var for chatbot/LLM features):
export OPENROUTER_API_KEY=your_key_here
./distribution/scripts/run.sh

# Windows:
set OPENROUTER_API_KEY=your_key_here
distribution\scripts\run.bat
```

The API key can also be passed as a system property: `./distribution/scripts/run.sh -Dopenrouter.api.key=your_key`

Any `-D` flags you pass to `run.sh`/`run.bat` are forwarded to the JVM (they are placed
**before** `-jar`, where JVM system properties must go). For example, to enable Document
Q&A and auto-ingest a folder of documents at startup:

```bash
export OPENROUTER_API_KEY=your_key_here
./distribution/scripts/run.sh \
    -Dopenrouter.base.url=https://your-embeddings-proxy/v1 \
    -Dopenrouter.embeddings.model=intfloat/multilingual-e5-large \
    -Drag.docs.dir=/path/to/your/documents
```

**Or from inside the built product directory:**

macOS product is wrapped in an `.app` bundle:
```bash
cd distribution/target/products/com.kk.pde.ds.product/macosx/cocoa/x86_64/Eclipse.app/Contents/Eclipse
java -jar plugins/org.eclipse.osgi_*.jar -configuration configuration -console -consoleLog
```

Linux:
```bash
cd distribution/target/products/com.kk.pde.ds.product/linux/gtk/x86_64
java -jar plugins/org.eclipse.osgi_*.jar -configuration configuration -console -consoleLog
```

**With remote debugging:**
```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 \
     -jar plugins/org.eclipse.osgi_*.jar -configuration configuration -console -consoleLog
```

The `-configuration configuration` flag is required to point the OSGi framework to the config.ini file.

## Felix WebConsole

Access the web console at: http://localhost:8080/system/console
- Username: `admin`
- Password: `admin`

The DS tab shows all Declarative Services components and their status.

## Module Structure

```
com.kk.pde.ds.target   → Target platform definition (Eclipse 2024-12)
com.kk.pde.ds.api      → IGreet interface (service contract)
com.kk.pde.ds.imp      → Greet implementation (service provider)
com.kk.pde.ds.app      → App consumer (@Reference injection, launches Clock + Chatbot UI)
com.kk.pde.ds.rest     → REST API via HTTP Whiteboard (@Reference injection)
com.kk.pde.ds.mcp.api  → MCP tool contract (IMcpTool, IMcpToolRegistry)
com.kk.pde.ds.mcp.server → MCP server (HTTP servlet + tool registry + built-in tools)
com.kk.pde.ds.mcp.client → MCP client (tests server via HTTP/JSON-RPC)
com.kk.pde.ds.mcp.llm  → OpenRouter LLM bridge (agent loop with tool calls)
com.kk.pde.ds.chatbot  → Swing chatbot UI (model selection, chat history, LLM integration)
com.kk.pde.ds.rag      → Document Q&A (RAG): parse/chunk/embed/store + search-as-tool
com.kk.pde.ds.ecf.api      → IRemoteGreet contract (ECF remote service interface)
com.kk.pde.ds.ecf.host     → RemoteGreetImpl exported as a remote service (ECF RSA)
com.kk.pde.ds.ecf.consumer → Imports IRemoteGreet via EDEF, @Reference injection
com.kk.pde.ds.spike.api    → Spike/experiment contract bundle
com.kk.pde.ds.spike.master → Spike master component
com.kk.pde.ds.spike.detail → Spike detail component
com.kk.pde.ds.feature  → Feature grouping all bundles
distribution           → p2 repository + product builds
fatjar                 → Standalone fat-JAR launcher (built separately, not in the reactor)
```

Test fragments (`eclipse-test-plugin`, run by tycho-surefire inside a live OSGi
framework): `com.kk.pde.ds.imp.tests` (Greet) and `com.kk.pde.ds.mcp.api.tests` (the
shared `Json` parser).

## REST API

The `com.kk.pde.ds.rest` module exposes the IGreet service via HTTP endpoints using OSGi HTTP Whiteboard pattern.

**Endpoints:**

| Method | URL | Description |
|--------|-----|-------------|
| GET | `/api/greet` | Returns JSON greeting |
| GET | `/api/greet/{name}` | Returns personalized greeting |
| POST | `/api/greet` | Echoes posted message |

**Test commands:**
```bash
# Basic greeting
curl http://localhost:8080/api/greet

# Personalized greeting
curl http://localhost:8080/api/greet/World

# Echo message
curl -X POST http://localhost:8080/api/greet -d '{"message":"Test"}'
```

**Response format:**
```json
{"message":"Hello from OSGi HTTP Whiteboard!","timestamp":1737734567890}
```

## Chatbot UI

The `com.kk.pde.ds.chatbot` bundle provides a Swing-based chat interface for interacting with LLMs.

**Features:**
- Multi-turn conversation with session history
- Model selection via editable dropdown (queries `/v1/models` endpoint)
- Works with any OpenAI-compatible API (OpenRouter, LM Studio, etc.)
- MCP tool integration — LLM can call registered tools during conversations
- Clear history button to reset conversation

**Configuration (environment variables or system properties):**

| Env Var | System Property | Default | Purpose |
|---------|----------------|---------|---------|
| `OPENROUTER_API_KEY` | `openrouter.api.key` | — | API key (required) |
| — | `openrouter.model` | `google/gemini-flash-1.5` | Default model ID |
| — | `openrouter.base.url` | `https://openrouter.ai/api/v1` | API base URL |

**Service binding flow:**
1. `OpenRouterAgent` injects `IMcpToolRegistry` for tool access
2. `ChatService` injects `OpenRouterAgent` for LLM communication
3. `App` injects `ChatService` and launches `ChatFrame` on the EDT
4. `ChatFrame` uses `SwingWorker` for non-blocking LLM calls

## Document Q&A (RAG)

The `com.kk.pde.ds.rag` bundle adds **retrieval-augmented generation**: it ingests local
documents and exposes retrieval to the LLM **as a tool** (`document_search`), so the
existing chat/tool loop decides when to retrieve. There is no always-on inject-then-answer
pipeline. The two tools register through the same `IMcpToolRegistry` as the built-in tools,
so **no existing module changed**.

**Pipeline (each stage an OSGi service behind an interface in `com.kk.pde.ds.rag.api`):**

| Component | Role |
|-----------|------|
| `MultiFormatDocumentParser` | PDF → Apache PDFBox; `.docx`/`.pptx` → read the OOXML zip directly; `.html`/`.htm` → tag-strip; `.txt`/`.md` → UTF-8 |
| `SlidingWindowChunker` | ~400-token chunks, ~12% overlap, paragraph/sentence boundaries (kept under E5's 512-token window) |
| `OpenAiEmbeddingClient` | POSTs `/v1/embeddings`, mirrors the chat client's config; applies E5 `query:`/`passage:` role prefixes |
| `InMemoryVectorStore` | brute-force cosine over `float[]`, behind a swappable `VectorStore` interface (pgvector-ready) |
| `DocumentIngestionService` | orchestrates parse → chunk → embed → store, and answers `search` |
| `DocumentSearchTool` / `IngestDocumentsTool` | the two `IMcpTool`s the LLM can call |

**Supported formats:** `.pdf`, `.docx`, `.pptx`, `.html`/`.htm`, `.txt`/`.md`.

**Configuration (system properties / env vars):**

| Property | Env var | Default | Purpose |
|----------|---------|---------|---------|
| `openrouter.api.key` | `OPENROUTER_API_KEY` | — | API key (shared with the chat client) |
| `openrouter.base.url` | — | `https://openrouter.ai/api/v1` | embeddings POSTed to `{base}/embeddings` |
| `openrouter.embeddings.model` | `OPENROUTER_EMBEDDINGS_MODEL` | `intfloat/multilingual-e5-large` | embedding model |
| `rag.docs.dir` | — | — | folder auto-ingested at startup (recursive, daemon thread) |
| `rag.embedding.query.prefix` | — | `query: ` | E5 query prefix (blank it for non-E5 models like qwen3) |
| `rag.embedding.passage.prefix` | — | `passage: ` | E5 passage prefix |
| `rag.chunk.target.tokens` | — | `400` | chunk size |
| `rag.chunk.overlap.tokens` | — | `50` | chunk overlap |
| `rag.ocr.enabled` | `RAG_OCR_ENABLED` | `true` | OCR master switch (see OCR section below) |
| `rag.ocr.binary` | — | `tesseract` | path to the Tesseract binary |
| `rag.ocr.lang` | — | `eng` | Tesseract `-l` value, e.g. `eng+tur` |
| `rag.ocr.dpi` | — | `300` | raster DPI for scanned PDF pages |
| `rag.ocr.min.chars.per.page` | — | `16` | below this a PDF page is treated as a scan |
| `rag.ocr.timeout.seconds` | — | `60` | per-image OCR subprocess timeout |
| `rag.ocr.max.images.per.doc` | — | `200` | per-document embedded-image cap |

**Use it:** set `-Drag.docs.dir` at startup (see "Running the Application"), or ingest at
runtime via the chatbot (the model calls `ingest_documents`) or directly over the MCP HTTP
endpoint:

```bash
# Ingest a folder
curl -s -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' -d \
 '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ingest_documents","arguments":{"path":"/path/to/docs"}}}'

# Search (the LLM normally calls this itself during a chat)
curl -s -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' -d \
 '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"document_search","arguments":{"query":"your question","top_k":"5"}}}'
```

**OCR (text-on-images & scanned PDFs):** the parser recovers text rendered as pixels when a
Tesseract binary is available. Per PDF page: if the text layer is empty/sparse
(< `rag.ocr.min.chars.per.page`) the page is treated as a **scan** — rasterized via PDFBox
`PDFRenderer` at `rag.ocr.dpi` and OCR'd whole; otherwise its text is kept and each embedded
raster image on the page is OCR'd and appended. For `.docx`, images under `word/media/` are
OCR'd too. (`.pptx` embedded-image OCR is not yet implemented.) OCR is the **only** step that
shells out — `OcrEngine`/`TesseractCliOcrEngine` invoke `tesseract <img> stdout -l <lang>` as
a subprocess (no Tess4J/JNA, no new bundle deps). Requires the binary on the host
(`brew install tesseract` / `apt install tesseract-ocr`); if it is absent or
`rag.ocr.enabled=false`, ingestion silently falls back to text-only. Use
`-Djava.awt.headless=true` for server runs (PDFBox/ImageIO use AWT).

**Key facts / pitfalls:**
- **No Apache Tika.** Tika (every version) hard-requires slf4j 2.0, which cascades into a
  project-wide logging migration (Logback 1.3 + Aries SPI Fly) and breaks Felix Health Check
  (`org.slf4j.helpers [1.7,2.0)`). PDFBox logs via commons-logging (JCL), so the slf4j 1.7 /
  Logback 1.2 stack is untouched. `.docx`/`.pptx` are read as OOXML zips (no Apache POI).
- **OCR via the Tesseract CLI, not Tess4J.** The subprocess approach adds zero Java/OSGi
  dependencies and no slf4j transitive — same dependency-minimalism that ruled out Tika. The
  trade-off is an external binary requirement. Rasterization needs no extra dependency:
  PDFBox 2.0.32 already ships `PDFRenderer`.
- The embeddings client reuses the chat client's exact config source (`openrouter.*`), so the
  embeddings endpoint uses the same base URL and key.
- E5 models are asymmetric and prefix-sensitive, and truncate at 512 tokens — hence the role
  prefixes and the 400-token chunk default.
- See `com.kk.pde.ds.rag/README.md` for the full verification record and `FOR_Kerem_RAG.md`
  for the design narrative.

## ECF Remote Services

The `com.kk.pde.ds.ecf.*` bundles showcase **OSGi Remote Services** via Eclipse
Communication Framework (ECF). A service exported in one OSGi process (the *host*)
is consumed via DS `@Reference` in a separate process (the *consumer*) — the same
`@Reference` pattern as the local demo, stretched across a network socket.

**Topology:** two separate JVMs (two dedicated products), communicating over ECF's
**Generic provider** transport on `ecftcp://localhost:3288/server`. Discovery is
**file-based EDEF** (no ZooKeeper/mDNS daemon).

| Bundle | Role |
|--------|------|
| `com.kk.pde.ds.ecf.api` | `IRemoteGreet { String greet(String name); }` |
| `com.kk.pde.ds.ecf.host` | `RemoteGreetImpl` — `@Component` with `service.exported.interfaces=*`, `service.exported.configs=ecf.generic.server`, `ecf.exported.containerfactoryargs=ecftcp://localhost:3288/server` |
| `com.kk.pde.ds.ecf.consumer` | `RemoteGreetConsumer` (`@Reference`) + `GreetCommand` (Gogo `ecf:greet`) + EDEF file `OSGI-INF/remote-service/remote-greet-endpoint.xml` referenced from the `Remote-Service` manifest header |

**Run it (two terminals):**
```bash
mvn clean verify
./distribution/scripts/run-ecf-host.sh       # terminal 1 — exports IRemoteGreet
./distribution/scripts/run-ecf-consumer.sh   # terminal 2 — imports + invokes greet("ECF")
```
The consumer logs `Remote response: Hello, ECF! (served remotely by host)`. At the
consumer's `osgi>` prompt, `ecf:greet World` re-invokes on demand.

**Flow:**
1. Host `RemoteGreetImpl` registers `IRemoteGreet` with `service.exported.*` properties
2. ECF RSA exports it via the Generic server (TCP socket on :3288)
3. Consumer's `Remote-Service` header → RSA reads the EDEF → connects → registers a proxy `IRemoteGreet`
4. Consumer's `@Reference` binds the proxy; the method call round-trips to the host JVM

**Key facts / pitfalls:**
- ECF is pinned to the **3.14.x line** (last with `JavaSE-1.8` BREEs); newer ECF needs Java 11/17. Bundles come from Maven Central (groupId `org.eclipse.ecf`). The runtime here is actually Java 21, so even Java-17 Equinox bundles resolve.
- The ECF bundle set must be enumerated **explicitly** in the target platform — OSGi `Require-Bundle`/`Import-Package` are invisible to Maven's transitive resolver.
- The static EDEF's `ecf.endpoint.id.ns` must be `org.eclipse.ecf.core.identity.StringID` (the Generic provider's identity namespace class), and `ecf.rsvc.id=1` (deterministic for a single exported service). Capture these from the host's exported `ECFEndpointDescription` log if they change.
- The two products exclude Felix HTTP/WebConsole so the two JVMs don't both grab port 8080.

## Architecture

**Service binding flow:**
1. `Greet` component registers as `IGreet` service
2. `App` component has `@Reference` on `setApi(IGreet)`
3. Felix SCR injects `Greet` into `App`
4. `App.start()` calls `api.greet()` → prints "Hello world!"

## Key Configuration Files

| File | Purpose |
|------|---------|
| `com.kk.pde.ds.target/*.target` | Target platform — Maven-location dependencies (no p2 repo URLs), incl. JUnit 5 + Equinox test harness |
| `distribution/p2.product` | Product definition (bundles, start levels) |
| `distribution/category.xml` | p2 repository category structure |
| `*/META-INF/MANIFEST.MF` | OSGi bundle metadata |
| `*/OSGI-INF/*.xml` | DS component declarations |

## Technology Stack

- **Java 8+** (all bundles and the fat JAR launcher target Java 8 bytecode)
- **Tycho 4.0.13** (Maven OSGi build)
- **Eclipse 2024-12** target platform
- **OSGi Declarative Services** via annotations
- **Felix SCR** runtime
- **Felix HTTP Jetty** with HTTP Whiteboard for REST API
- **Felix WebConsole** with DS plugin
- **Eclipse Communication Framework (ECF) 3.14.x** for OSGi Remote Services (RSA) with the Generic provider
- **Apache PDFBox 2.0.x** (+ fontbox, commons-logging) for PDF text extraction in the RAG bundle
- **Logback** for logging (default level: INFO)
