# Claude Code Automation Recommendations

Tailored automation recommendations for the **p.osgi.ds** project (OSGi Declarative Services, Maven Tycho 4.0.13, Java 8, Eclipse 2024-12). This document combines the initial top-pick recommendations with expanded options across all five categories.

---

## Codebase Profile

- **Type**: Java 8, OSGi Declarative Services, multi-bundle
- **Build**: Maven Tycho 4.0.13 on Eclipse 2024-12 target platform
- **Bundles**: `api`, `imp`, `app`, `rest`, `mcp.api`, `mcp.server`, `mcp.client`, `mcp.llm`, `chatbot`, `feature`, `imp.tests`, `distribution`, `fatjar`
- **Runtime surfaces**: Felix HTTP Jetty (port 8080), Felix WebConsole, MCP JSON-RPC servlet, OpenRouter LLM bridge, Swing chatbot
- **Already wired**: serena MCP, context7 MCP (global), telegram plugin, chrome-devtools plugin, commit-commands plugin, superpowers skills

**Three structural facts that shape every recommendation below**

1. **Manifest / Component-XML / Java class is a coherence triple.** Every DS service requires `META-INF/MANIFEST.MF` imports, an `OSGI-INF/<FQCN>.xml` descriptor, and a `@Component`-annotated class. Drift between any two = service won't bind at runtime.
2. **`com.kk.pde.ds.mcp.server/.../tools/*.java` is textbook scaffolding.** Eight tools follow an identical ~45-line shape (`@Component(service = IMcpTool.class)` → `getName / getDescription / getInputSchema / execute`). Anything this repetitive is a templating target.
3. **`mvn clean verify` is heavy** (Tycho target-platform resolution + p2 packaging — minutes long). Automation needs to lean on fast feedback (file coherence, manifest validation) rather than full rebuilds.

---

## 🔌 MCP Servers

### 1. context7 — project-scoped registration *(top pick)*
**Why**: You target Felix SCR 2.2.12, OSGi SCR-annotations 1.5.1, Tycho 4.0.13, and Felix HTTP Whiteboard — APIs that drift between minor versions. Global CLAUDE.md already favors context7, but a project-local `.mcp.json` means *every* clone of this repo gets it, and Claude reaches for it on Felix/Tycho/OSGi questions without prompting.
**Install** — drop at repo root as `.mcp.json`:
```json
{ "mcpServers": { "context7": { "command": "npx", "args": ["-y", "@upstash/context7-mcp"] } } }
```

### 2. GitHub MCP
**Why**: Recent commit history shows you're doing real GitHub work (`feat(chatbot)`, `fix(chatbot)`, conventional-commits style). A GitHub MCP lets Claude query issues, PRs, and CI status directly instead of via `gh` + shell parsing — handy when you want "summarize open PRs that touch the MCP server bundle".
**Install**: `claude mcp add github` (or via plugin marketplace).

### 3. Custom: OSGi WebConsole MCP *(bespoke, high-leverage)*
**Why**: Felix WebConsole exposes JSON at `/system/console/components.json`, `/system/console/bundles.json`, `/system/console/services.json`. A thin MCP server that wraps these endpoints would let Claude *introspect a running OSGi runtime* during diagnosis ("which `@Reference` is unsatisfied?", "is `IMcpToolRegistry` registered?"). This is a small Python or Node MCP — maybe 100 lines — but turns a manual `curl ... | jq` loop into native tool calls.
**Effort**: 1–2 hours to scaffold; pays back the first time you debug a service-binding bug.

### 4. Skip these (and why)
| MCP | Why skip |
|-----|----------|
| Playwright / chrome-devtools | UI is **Swing**, not web |
| Database MCPs (Postgres, Supabase, Prisma) | No database |
| Filesystem MCP | Read/Bash already cover this |
| Memory MCP | serena's memory bank already does this |
| Sentry MCP | No error-tracker integration in the repo |

---

## 🎯 Skills (highest-leverage category for this repo)

### 1. `ds-component` *(top pick — project skill)*
**Why**: Every new service requires three coordinated edits — a `@Component` Java class, a `Service-Component:` line in `META-INF/MANIFEST.MF` (or matching wildcard), and an `OSGI-INF/<FQCN>.xml` descriptor. Newcomers (and Claude) routinely forget one of the three.
**Path**: `.claude/skills/ds-component/SKILL.md`
**Invocation**: User-only (`disable-model-invocation: true`) — deliberate scaffolding, not background context.
**Skeleton**:
```yaml
---
name: ds-component
description: Scaffold a new OSGi Declarative Services component (Java + MANIFEST.MF + OSGI-INF XML) in a chosen bundle.
disable-model-invocation: true
---
# Args: <bundle-dir> <FQCN> <ServiceInterface>
# Steps:
# 1. Create src/.../<Class>.java with @Component(service = <Service>.class)
# 2. Confirm META-INF/MANIFEST.MF has Service-Component: OSGI-INF/*.xml (wildcard)
# 3. Generate OSGI-INF/<FQCN>.xml from template (scr v1.1.0 namespace)
# 4. Verify Import-Package includes the service interface's package
# 5. Verify Import-Package has org.osgi.service.component.annotations;resolution:=optional
```

### 2. `mcp-tool` *(top pick — project skill)*
**Why**: Look at `com.kk.pde.ds.mcp.server/src/.../tools/` — `CalculatorTool`, `DateTimeTool`, `EchoTool`, `GreetTool`, `HttpFetchTool`, `BundleListTool`, `SystemInfoTool` are essentially the same file with different bodies. A skill that takes a tool name + description + JSON-Schema and emits the class + DS descriptor turns 5 minutes of careful copy/paste into one command. Bonus: it can nudge a re-test via `com.kk.pde.ds.mcp.client/McpClient`.
**Path**: `.claude/skills/mcp-tool/SKILL.md`
**Invocation**: Both (user via `/mcp-tool weather …`; Claude when you say "add a tool that …").

### 3. `rest-endpoint`
**Why**: `com.kk.pde.ds.rest.GreetServlet` is the canonical HTTP Whiteboard pattern in the repo (`@Component`, `HTTP_WHITEBOARD_SERVLET_PATTERN`, `Servlet.class`). Each new endpoint repeats the same wiring. A skill bundles the pattern *plus* the MANIFEST `Import-Package` additions for `javax.servlet` + `org.osgi.service.http.whiteboard`.
**Path**: `.claude/skills/rest-endpoint/SKILL.md`
**Invocation**: Both.

### 4. `run-and-probe`
**Why**: The "did my change work end-to-end" loop here is: build → start product → wait for Jetty → `curl /api/greet` → `curl` the MCP servlet → kill. That's tedious enough to skip, which means regressions land. A user-invocable skill (`/run-and-probe`) encodes it once.
**Path**: `.claude/skills/run-and-probe/SKILL.md`
**Invocation**: User-only (side effect: starts a process).
**Outline**:
```yaml
---
name: run-and-probe
description: Start the OSGi product, wait for Jetty, smoke-test REST + MCP endpoints, then shut down.
disable-model-invocation: true
---
# 1. ./distribution/scripts/run.sh & (capture PID)
# 2. Poll http://localhost:8080/system/console/bundles.json until 200 (max 30s)
# 3. curl /api/greet, /api/greet/World, MCP /mcp endpoint
# 4. Print summary; kill PID
```

### 5. `for-kerem-update`
**Why**: Project CLAUDE.md mandates a `FOR_Kerem.md` file kept in sync with the codebase, using engaging prose / analogies. That's a *specific* writing voice and structure — a perfect skill so it doesn't get reinvented every time.
**Path**: `.claude/skills/for-kerem-update/SKILL.md`
**Invocation**: Both. Claude can also auto-invoke after a non-trivial feature ships.

### 6. `bundle-bump`
**Why**: Bundle versions live in two places — `META-INF/MANIFEST.MF` (`Bundle-Version`) and the bundle's `pom.xml`. They must move together. A skill that takes `<bundle-dir> <new-version>` updates both and warns about dependent bundles' `Import-Package` version ranges.
**Invocation**: User-only.

---

## ⚡ Hooks

### 1. Bundle-coherence guard (PostToolUse) *(top pick)*
**Why**: Most OSGi runtime failures here trace back to editing one file in the trio and forgetting the others. The manifest uses `Service-Component: OSGI-INF/*.xml` (wildcard), so the *correct* check is:
- Every Java class with `@Component` has a matching `OSGI-INF/<FQCN>.xml`
- Every XML's `<implementation class="…">` points at an existing `.java`
- Every `@Reference` injects a type whose package is in the bundle's `Import-Package`

A fast Python/bash script after any edit under `*/META-INF/MANIFEST.MF`, `*/OSGI-INF/*.xml`, or `**/src/**/*.java` emits warnings without rebuilding.

**Where**: `.claude/settings.json`
```json
{
  "hooks": {
    "PostToolUse": [{
      "matcher": "Edit|Write",
      "hooks": [{
        "type": "command",
        "command": ".claude/scripts/check-bundle-coherence.sh \"$CLAUDE_FILE_PATH\""
      }]
    }]
  }
}
```
Script `exit 0` silently on non-bundle paths; `exit 0` with stderr warning on drift.

### 2. Heavy-build guard (PreToolUse on Bash)
**Why**: `mvn clean verify` takes minutes on this Tycho project. Easy to fire by reflex; harder to wait out. A PreToolUse hook that matches `mvn` commands and warns when `clean` is combined with `verify` or `install` *without* a `-pl <module>` flag saves a lot of distracted waiting.
```json
{
  "hooks": {
    "PreToolUse": [{
      "matcher": "Bash",
      "hooks": [{
        "type": "command",
        "command": "case \"$CLAUDE_COMMAND\" in *'mvn clean verify'*|*'mvn clean install'*) [[ \"$CLAUDE_COMMAND\" != *'-pl '* ]] && echo 'WARN: full Tycho rebuild; add -pl <module> if scoped' >&2 ;; esac; exit 0"
      }]
    }]
  }
}
```

### 3. Target-platform-edit guard (PreToolUse on Edit/Write)
**Why**: Editing `com.kk.pde.ds.target/*.target` invalidates Tycho's resolved cache. The next build needs `mvn -U` or `mvn clean` to actually pick up the change. Easy to forget; gives a build that mysteriously "doesn't see" your new dependency. A hook that matches `*.target` writes and prints "remember `mvn clean` next build" saves the head-scratching.

### 4. Permissions cleanup (manual, not a hook)
**Why**: `.claude/settings.local.json` is full of stale Windows-path permissions (`D:\Dev\…`, `cmd /c`, `taskkill`) from past machines — noise at every prompt decision. Run `/fewer-permission-prompts` once to regenerate from current activity. Not strictly a hook, but worth doing.

### 5. Stop-hook for uncommitted work
**Why**: Long sessions on this repo touch many bundles. A Stop hook that runs `git status --porcelain | head` and prints a one-line summary if anything is dirty stops you from closing a session with hours of unstaged changes.

---

## 🤖 Subagents

### 1. `osgi-wiring-doctor` *(top pick)*
**Why**: Service-not-binding bugs are by far the slowest to diagnose here. They require cross-referencing MANIFEST `Import-Package`, OSGI-INF `<reference>` cardinality, target-platform exports, and Felix WebConsole `/system/console/components` state. A subagent that knows the playbook keeps the main context clean.
**Path**: `.claude/agents/osgi-wiring-doctor.md`
```yaml
---
name: osgi-wiring-doctor
description: Diagnose why an OSGi @Reference is not resolving. Reads bundle manifests, DS component XML, target platform, and (if running) Felix WebConsole DS state.
tools: Read, Grep, Bash, WebFetch
---
You are an OSGi Declarative Services diagnostic specialist…
Playbook:
1. Read the failing component's @Reference declarations
2. For each referenced interface, locate the @Component that provides it
3. Verify the providing bundle's MANIFEST exports the interface package
4. Verify the consuming bundle's MANIFEST imports the interface package
5. Verify the OSGI-INF XML names match Java FQCNs
6. If the product is running, curl /system/console/components.json and report unsatisfied references
```

### 2. `manifest-import-fixer`
**Why**: A specific, narrow subagent for the "missing Import-Package" failure mode. Given a Java compile error like "package X is not visible", finds which bundle exports `X`, checks the consumer's MANIFEST, proposes the exact `Import-Package` line addition. Narrow scope = high success rate.

### 3. `for-kerem-writer`
**Why**: `FOR_Kerem.md` is long-form, engaging prose with analogies. Writing it well takes context window space and a different "voice" than code work. A subagent isolates that work — give it a diff summary and let it write.
**Tools**: Read, Grep, Bash (for `git log`).

### 4. `mcp-tool-tester`
**Why**: After generating a new `IMcpTool`, you want to exercise it via the existing `com.kk.pde.ds.mcp.client/McpClient`. A subagent that knows the client's CLI surface, runs it against a chosen tool, and reports JSON-RPC response shape — closes the loop after the `mcp-tool` skill.

### 5. `product-launcher`
**Why**: Bigger sibling of the `run-and-probe` skill. Starts the product, tails Logback output, parses for DS failures and bundle-resolution errors, summarizes startup health. Useful for "I just changed `feature.xml` — does the product still start cleanly?"

---

## 📦 Plugins

### Already installed / using
- **`commit-commands`** — `/commit-commands:commit` matches the existing conventional-commits style in this repo (`feat(chatbot):`, `fix(chatbot):`).
- **`superpowers`** — `/superpowers:writing-plans` and `/superpowers:test-driven-development` are well-suited for non-trivial bundle work where the manifest/XML/Java triple needs orchestration.
- **`chrome-devtools-mcp`** — currently unused; would only matter if you added a web frontend.

### Worth invoking (not installing)
- **`document-skills:mcp-builder`** — You're building MCP-shaped tools in OSGi. The official mcp-builder skill encodes Anthropic's tool-design guidance (naming, error handling, response shaping) and applies directly even though your transport is bespoke. Use it as a *reviewer*: "Use mcp-builder to review `HttpFetchTool.java`".
- **`/code-review ultra`** — Before merging significant work to `main`, the multi-agent cloud review catches cross-bundle issues a single pass misses. User-triggered, billed.

### Skip
- **`frontend-design`** — Swing only; no fit.
- **`anthropic-agent-skills` (docx/xlsx/pptx)** — Not part of this project's deliverable set.

---

## Recommended Implementation Order

If you implement only three things, do these in this order — each strictly raises the value of the next:

1. **`/fewer-permission-prompts`** — Clean settings.local.json first; every subsequent automation will configure permissions, so start from a clean baseline.
2. **`.claude/skills/ds-component/`** + **`.claude/skills/mcp-tool/`** — These pay for themselves on the very next component you add.
3. **Bundle-coherence hook** — Continuous safety net for the coherence-triple invariant; backstops the skills.

Then layer in subagents (`osgi-wiring-doctor` first), then the bespoke MCP server (Felix WebConsole wrapper), as you hit the friction each one removes.

---

## Want more?

Ask for deeper expansion on any single category — e.g., "show me five more hook patterns" or "design the OSGi WebConsole MCP". And if you want me to actually build any of these (the `ds-component` skill, the coherence hook, the `osgi-wiring-doctor` subagent), just say which one.
