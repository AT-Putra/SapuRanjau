## Navigasi docs

Proyek doc-driven (ADR-based). `docs/` = truth, 8 file / ~189 KB, header seragam.

- Daftar ADR: `grep -n "^### ADR-" docs/06_PROGRESS_DECISIONS.md`
- Dampak sebuah ADR/task: `grep -rn "ADR-0031" docs/` (ada 464 rujukan silang eksplisit di korpus)
- Baca ADR-nya langsung — jangan simpulkan dari judul.

Graphify dibuang 2026-07-28 (korpus terlalu kecil untuk membayar indeks LLM); `graphify-out/` masih di disk, tidak dirawat, tidak dipakai.

## Kotlin LSP (cclsp)

The `kotlin-lsp` MCP server (cclsp → JetBrains Kotlin LSP) gives **exact semantic navigation** for Kotlin code. Use it for *understanding*, Grep/Glob for *discovery*:

- Tracing where a symbol is defined, or finding all references to it → use `find_definition` / `find_references` (exact results) instead of Grep (text matches only). Also `get_hover` (type/signature/doc), `find_implementation`, `find_workspace_symbols`, and call-hierarchy tools.
- Use **Grep/Glob for discovery** (finding files, searching patterns/strings). Use **LSP for understanding** (definitions, references, type info).
- After locating a file with Grep/Glob, prefer LSP to navigate within it rather than reading the whole file.

Caveats (be honest about these): (1) the tools appear only after a **Claude Code restart** loads the MCP server; (2) kotlin-lsp is IntelliJ-based and **indexes on first use** — the first queries after startup can be slow or return partial results until indexing settles (cclsp logs an "initialization timeout, proceeding anyway" that is usually benign); (3) **correctness is still confirmed by compiling/testing** (`gradle compileKotlin` / tests) — LSP hints don't replace the build. Config: `.mcp.json` + `cclsp.json` (kt/kts → `tools/kotlin-lsp/bin/intellij-server.exe --stdio`).
