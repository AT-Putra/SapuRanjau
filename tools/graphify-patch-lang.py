"""Re-apply our prompt patches to the installed graphify package.

graphify's stock prompts are tuned for English code corpora, which costs us three
things on an Indonesian doc corpus: labels get translated to English (ids shift →
duplicate nodes), extraction is selective rather than exhaustive (17 nodes where
subagents found 41), and nodes come back barely connected (18% orphans).

Run after every `uv tool upgrade graphifyy` — the upgrade replaces llm.py.
Idempotent; keeps a .bak of the pristine file.
"""
import os
import pathlib
import shutil
import sys

LLM = pathlib.Path(os.environ["APPDATA"]) / "uv/tools/graphifyy/Lib/site-packages/graphify/llm.py"

# (marker, anchor, text inserted before the anchor)
PATCHES = [
    (
        "LANGUAGE: write every",
        "Node ID format: lowercase, only [a-z0-9_], no dots or slashes.",
        'LANGUAGE: write every node/edge/hyperedge "label" in the SAME language as the source text.\n'
        "Never translate labels into English. Node ids stay ascii per the format rule below.\n\n"
        "COVERAGE: be exhaustive, not selective. Emit a node for EVERY distinct named thing the\n"
        "source introduces — module, component, service, layer, concept, decision, task, data\n"
        "entity, endpoint, config key, invariant, constraint — including ones mentioned only once.\n"
        "A document section that names ten things must yield ten nodes, not a summary of three.\n"
        "Under-extraction is the failure mode to avoid; prefer AMBIGUOUS over omitting.\n\n"
        # Pushing harder than this backfires: demanding ~2 edges/node made sol trade nodes
        # away (136 nodes/138 edges -> 100/111 on docs/02). Node+edge totals both matter.
        "EDGES: every node MUST take part in at least one edge — an isolated node is a failed\n"
        "extraction. Connect each entity to the decision, module, section, or entity that\n"
        "introduces, owns, uses, or constrains it. Emit roughly as many edges as nodes, or more.\n"
        'When nothing more specific fits, use "references" or "conceptually_related_to".\n\n'
        "LABEL SHAPE: type the label so it reads standalone, then keep the source wording:\n"
        '"Module: engine-core", "Entity: LevelConfig", "ADR-0006 · <judul keputusan>",\n'
        '"T-011 · <judul task>", "Endpoint: POST /tournament/level/action",\n'
        '"Concept: computeParMoves", "Table: level_score". A bare noun is not enough.\n'
        "Use ONE prefix vocabulary for the whole corpus — the source document's language.\n\n",
    ),
    (
        "same language as the member labels",
        "Respond ONLY with a JSON object mapping the community id",
        "Name each community in the same language as the member labels; never translate. ",
    ),
]

src = LLM.read_text(encoding="utf-8")
todo = [(m, a, t) for m, a, t in PATCHES if m not in src]
if not todo:
    print("already patched:", LLM)
    sys.exit(0)
for marker, anchor, _ in todo:
    if anchor not in src:
        sys.exit(f"anchor gone for {marker!r} — upstream changed the prompt, re-check by hand: {LLM}")

shutil.copy2(LLM, LLM.with_suffix(".py.bak"))
for _, anchor, text in todo:
    src = src.replace(anchor, text + anchor, 1)
LLM.unlink()  # break the hardlink into the uv cache before rewriting
LLM.write_text(src, encoding="utf-8")
print(f"patched ({len(todo)} block(s)):", LLM)
