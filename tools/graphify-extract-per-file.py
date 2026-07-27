"""Re-extract the knowledge graph one source file per LLM request.

Why not plain `graphify extract .`: graphify packs files into chunks up to
--token-budget (default 60k). Our whole 12-doc corpus fits in one chunk, so the
model answers with a single response's worth of nodes for all of it — that is
what collapsed the graph from 239 nodes to 114. Incremental mode only
re-extracts *changed* files, so dropping one entry from manifest.json forces
exactly one file per request.

Do not "fix" this with a small --token-budget: files above 20k chars are sliced
into FileSlices, and slices spread across chunks lose track of their own path —
they come back as `unknown_adr_0032`, `release_dokumen` and friends.

Prereqs: env from .claude/settings.local.json (OPENAI_BASE_URL, OPENAI_API_KEY,
OPENAI_MODEL, GRAPHIFY_MAX_OUTPUT_TOKENS) and `python tools/graphify-patch-lang.py`.

Guard: a file whose node count comes back below what the graph already has
restores the graph and stops the loop. Usage: python tools/graphify-extract-per-file.py
"""
import json
import os
import pathlib
import subprocess
import sys
from collections import Counter

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "graphify-out"
GRAPH = OUT / "graph.json"
MANIFEST = OUT / "manifest.json"
LOOP_BAK = OUT / ".loop-bak.json"
GRAPHIFY = pathlib.Path(os.environ["USERPROFILE"]) / ".local/bin/graphify.exe"

missing = [k for k in ("OPENAI_BASE_URL", "OPENAI_API_KEY", "OPENAI_MODEL") if not os.environ.get(k)]
if missing:
    sys.exit(f"set {', '.join(missing)} first (see .claude/settings.local.json)")
os.environ.setdefault("GRAPHIFY_MAX_OUTPUT_TOKENS", "32768")

# A `uv tool upgrade graphifyy` silently reverts our prompt patches, and running
# unpatched degrades the graph quietly. Re-apply here so it cannot be forgotten.
p = subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name("graphify-patch-lang.py"))],
                   capture_output=True, text=True)
print(p.stdout.strip() or p.stderr.strip(), flush=True)
if p.returncode != 0:
    sys.exit("prompt patch failed — fix that before extracting")


def counts() -> Counter:
    g = json.loads(GRAPH.read_text(encoding="utf-8"))
    return Counter(n.get("source_file") for n in g["nodes"])


# Floor = what the graph holds right now; no file may come back thinner than it is.
before = counts()
targets = sorted((f for f in json.loads(MANIFEST.read_text(encoding="utf-8"))),
                 key=lambda f: -before.get(f, 0))

for i, rel in enumerate(targets, 1):
    floor = before.get(rel, 0)
    LOOP_BAK.write_bytes(GRAPH.read_bytes())
    m = json.loads(MANIFEST.read_text(encoding="utf-8"))
    m.pop(rel, None)                       # mark this one file as "changed"
    MANIFEST.write_text(json.dumps(m, indent=2), encoding="utf-8")

    print(f"\n=== [{i}/{len(targets)}] {rel} (floor {floor}) ===", flush=True)
    r = subprocess.run(
        [str(GRAPHIFY), "extract", ".", "--backend", "openai", "--no-gitignore"],
        cwd=ROOT, capture_output=True, text=True,
    )
    for line in ((r.stdout or "") + "\n" + (r.stderr or "")).splitlines():
        if any(k in line for k in ("chunk", "wrote", "tokens:", "Prune")):
            print("   ", line.strip(), flush=True)
    if r.returncode != 0:
        print("   FAILED:", (r.stderr or "")[-500:], flush=True)

    got = counts().get(rel, 0)
    print(f"    -> {rel}: {floor} -> {got} nodes", flush=True)
    if got < floor:
        GRAPH.write_bytes(LOOP_BAK.read_bytes())
        sys.exit(f"STOP: {rel} shrank ({floor} -> {got}); graph restored, loop aborted.")

final = counts()
print("\nDONE. per-file counts:", flush=True)
for k, v in final.most_common():
    print(f"  {v:5d}  {k}", flush=True)
print("TOTAL", sum(final.values()), flush=True)

# Every re-extracted file re-emits its own copy of any decision it cites, so the
# cross-file merge has to run again after the loop, not once.
print("\n=== dedup lintas-file ===", flush=True)
subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name("graphify-dedup-crossfile.py")),
                "--apply"], cwd=ROOT, check=True)
print("\nnext: graphify cluster-only . --backend openai && graphify label . --backend openai")
