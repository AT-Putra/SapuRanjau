## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships. **This is a doc-driven (ADR-based) project** — the graph maps decisions (`docs/06`), concepts (GDD/ARCH), modules, and the 15 data entities (`docs/08`), not code.

Rules:
- For questions about the project (architecture, decisions, "what depends on X", "why was Y decided", how concepts/entities/modules relate), **first run `graphify query "<question>"`** when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than reading every doc.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- **Keep the graph current: after any change to `docs/**`, `README.md`, `AGENTS.md`, or `RELEASE.md` (new/changed ADR, entity, module, concept), run `/graphify --update`** to re-extract changed files into the graph. This is a doc corpus → update re-runs semantic extraction on the changed files. `GEMINI_API_KEY` is set and validated (model `gemini-3-flash-preview`), so extraction runs via Gemini (`backend="gemini"`), not subagents. Do this as the last step after a docs/ADR editing session, same discipline as syncing `06`.

## Kotlin LSP (cclsp)

The `kotlin-lsp` MCP server (cclsp → JetBrains Kotlin LSP) gives **exact semantic navigation** for Kotlin code. Use it for *understanding*, Grep/Glob for *discovery*:

- Tracing where a symbol is defined, or finding all references to it → use `find_definition` / `find_references` (exact results) instead of Grep (text matches only). Also `get_hover` (type/signature/doc), `find_implementation`, `find_workspace_symbols`, and call-hierarchy tools.
- Use **Grep/Glob for discovery** (finding files, searching patterns/strings). Use **LSP for understanding** (definitions, references, type info).
- After locating a file with Grep/Glob, prefer LSP to navigate within it rather than reading the whole file.

Caveats (be honest about these): (1) the tools appear only after a **Claude Code restart** loads the MCP server; (2) kotlin-lsp is IntelliJ-based and **indexes on first use** — the first queries after startup can be slow or return partial results until indexing settles (cclsp logs an "initialization timeout, proceeding anyway" that is usually benign); (3) **correctness is still confirmed by compiling/testing** (`gradle compileKotlin` / tests) — LSP hints don't replace the build. Config: `.mcp.json` + `cclsp.json` (kt/kts → `tools/kotlin-lsp/bin/intellij-server.exe --stdio`).
