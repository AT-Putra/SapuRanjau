## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships. **This is a doc-driven (ADR-based) project** — the graph maps decisions (`docs/06`), concepts (GDD/ARCH), modules, and the 15 data entities (`docs/08`), not code.

Rules:
- For questions about the project (architecture, decisions, "what depends on X", "why was Y decided", how concepts/entities/modules relate), **first run `graphify query "<question>"`** when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than reading every doc.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- **Update graph berjenjang — JANGAN tiap edit (mahal ~300k token). Ekstraksi WAJIB subagen, BUKAN Gemini.** `gemini-3-flash-preview` *meringkas* ADR-doc panjang (hasilkan ~12 node di mana subagen hasilkan ~140); `build_merge` lalu mengganti node file itu → menggerus graph. Jadi backend default = **subagen general-purpose**; **incremental (per-file, bukan full)** = penghematnya. Biarkan `GEMINI_API_KEY` unset untuk graphify. Pilih tier per ukuran perubahan:
  - **T0** — typo/reword/tanggal/format, tanpa entitas/edge baru → **jangan sentuh graph** (0 token).
  - **T1** — 1–beberapa edge/atribut (tambah dependency, flip status task, tweak rationale) → **`python graphify-out/patch_edge.py add SRC TGT`** (patch `graph.json` langsung, ~0 token; `graphify query/path` baca graph.json jadi langsung ke-query). `selftest` & `remove` tersedia. Node id: `docs_04_tasks_t_011`.
  - **T2** — satu doc **nambah/hapus entitas** (ADR/task/entitas baru) → **incremental subagen HANYA file itu** (~30–90k/doc besar). Beri subagen prompt `references/extraction-spec.md`; lalu `build_merge` + Step 4–6.
  - **T3** — overhaul struktural / banyak doc / ganti skema / hygiene → **full rebuild subagen** (~300k), docs di-split ~4-file/chunk biar tiap subagen padat. Rare: batas fase saja.
  - **Irama:** batch perubahan, update di *checkpoint* (akhir sesi ADR / fase selesai), **bukan per-commit**. Graph = cache navigasi; `docs/` = truth, boleh sengaja lag. Guard `to_json` (#479) menolak shrink → sinyal ekstraksi terlalu sparse, jangan paksa.

## Kotlin LSP (cclsp)

The `kotlin-lsp` MCP server (cclsp → JetBrains Kotlin LSP) gives **exact semantic navigation** for Kotlin code. Use it for *understanding*, Grep/Glob for *discovery*:

- Tracing where a symbol is defined, or finding all references to it → use `find_definition` / `find_references` (exact results) instead of Grep (text matches only). Also `get_hover` (type/signature/doc), `find_implementation`, `find_workspace_symbols`, and call-hierarchy tools.
- Use **Grep/Glob for discovery** (finding files, searching patterns/strings). Use **LSP for understanding** (definitions, references, type info).
- After locating a file with Grep/Glob, prefer LSP to navigate within it rather than reading the whole file.

Caveats (be honest about these): (1) the tools appear only after a **Claude Code restart** loads the MCP server; (2) kotlin-lsp is IntelliJ-based and **indexes on first use** — the first queries after startup can be slow or return partial results until indexing settles (cclsp logs an "initialization timeout, proceeding anyway" that is usually benign); (3) **correctness is still confirmed by compiling/testing** (`gradle compileKotlin` / tests) — LSP hints don't replace the build. Config: `.mcp.json` + `cclsp.json` (kt/kts → `tools/kotlin-lsp/bin/intellij-server.exe --stdio`).
