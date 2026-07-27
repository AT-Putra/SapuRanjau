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

Guard: a file coming back below FLOOR_TOLERANCE of what the RUNNING graph held a
moment earlier restores the graph, drops the cache entries that run wrote, and
stops the loop. The floor is re-read per iteration and allows a little slack —
cross-file dedup merges duplicate nodes on every `extract` call, so per-file
counts drift down a percent or two with nothing lost.

Do NOT "simplify" this by deleting graphify-out and rebuilding from zero: with no
graph on disk graphify ignores the manifest and extracts the whole corpus in one
pass (65k tokens, 2 chunks, 980 -> 295 nodes on 2026-07-27). Per-file isolation
depends on an existing graph.

Usage: python tools/graphify-extract-per-file.py [--only docs/06.md ...]
"""
import hashlib
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
CACHE = OUT / "cache" / "semantic"  # hasil ekstraksi ber-kunci konten file

# Berapa banyak penyusutan per-file yang masih dianggap wajar. Dedup lintas-file melebur node
# duplikat tiap kali `extract` dipanggil, jadi hitungan per-file memang bergeser turun sedikit
# tanpa ada yang hilang — terukur 2026-07-27: AGENTS.md 64->63 (-1,6%) di iterasi yang bahkan
# TIDAK memanggil LLM. Keruntuhan sungguhan jauh di luar itu (RELEASE.md 125->31 = -75%).
# Ambang 10% duduk di jurang antara keduanya.
FLOOR_TOLERANCE = 0.9
GRAPHIFY = pathlib.Path(os.environ["USERPROFILE"]) / ".local/bin/graphify.exe"

def counts() -> Counter:
    if not GRAPH.exists():   # rebuild bersih: graph belum ada sampai file pertama diekstrak
        return Counter()
    g = json.loads(GRAPH.read_text(encoding="utf-8"))
    return Counter(n.get("source_file") for n in g["nodes"])


def cache_entries() -> set:
    return set(CACHE.rglob("*.json")) if CACHE.exists() else set()


def drop_new_cache(kept: set) -> int:
    """Delete the semantic-cache entries this iteration just wrote.

    The floor guard restores graph.json, but graphify keys its semantic cache by
    file CONTENT — so a rejected extraction stays cached, and every retry replays
    it verbatim without ever calling the LLM (`semantic cache: 1 hit / 0 miss`,
    `0 re-extracted`). Observed 2026-07-27: one bad run poisoned the cache and the
    next two attempts failed identically for free. Restoring the graph without
    dropping the cache makes the guard look like it worked while guaranteeing the
    same failure forever.
    """
    dropped = 0
    for f in cache_entries() - kept:
        f.unlink(missing_ok=True)
        dropped += 1
    return dropped


def sync_manifest() -> list:
    """Stamp every manifest row to the file currently on disk.

    The loop isolates one file per request by dropping ONE row from the manifest,
    which only holds if graphify considers every OTHER file unchanged. Editing
    docs is precisely why we re-extract, so that assumption is false on arrival:
    on 2026-07-27 the first iteration pulled all 8 edited docs into a single
    60k-token chunk and each came back about a third of its size — the very
    collapse this script exists to prevent.

    Change detection is mtime + MD5 (graphify/detect.py `detect_incremental`),
    so we can reproduce it exactly instead of hoping. Files still get re-extracted
    one by one; this only stops iteration 1 from dragging the others along.
    """
    m = json.loads(MANIFEST.read_text(encoding="utf-8"))
    stale = []
    for rel, entry in m.items():
        p = ROOT / rel
        if not p.is_file():
            continue
        digest = hashlib.md5(p.read_bytes(), usedforsecurity=False).hexdigest()
        if not isinstance(entry, dict) or entry.get("semantic_hash") != digest:
            stale.append(rel)
        m[rel] = {"mtime": p.stat().st_mtime, "ast_hash": digest, "semantic_hash": digest}
    MANIFEST.write_text(json.dumps(m, indent=2), encoding="utf-8")
    return stale


def selftest() -> None:
    """`python tools/graphify-extract-per-file.py --selftest` — no LLM, no network."""
    import tempfile
    global ROOT, MANIFEST, CACHE
    with tempfile.TemporaryDirectory() as tmp:
        ROOT = pathlib.Path(tmp)
        MANIFEST = ROOT / "manifest.json"
        CACHE = ROOT / "cache" / "semantic"
        doc = ROOT / "a.md"
        doc.write_text("isi baru", encoding="utf-8")
        MANIFEST.write_text(json.dumps({"a.md": {"mtime": 0, "ast_hash": "x", "semantic_hash": "basi"}}), encoding="utf-8")

        assert sync_manifest() == ["a.md"], "hash basi harus terdeteksi berubah"
        row = json.loads(MANIFEST.read_text(encoding="utf-8"))["a.md"]
        assert row["semantic_hash"] == hashlib.md5(doc.read_bytes(), usedforsecurity=False).hexdigest()
        assert row["mtime"] == doc.stat().st_mtime
        assert sync_manifest() == [], "pass kedua tak boleh melihat perubahan"

        (CACHE / "bucket").mkdir(parents=True)
        lama = CACHE / "bucket" / "lama.json"
        lama.write_text("{}", encoding="utf-8")
        kept = cache_entries()
        baru = CACHE / "bucket" / "baru.json"
        baru.write_text("{}", encoding="utf-8")
        assert drop_new_cache(kept) == 1, "hanya entri iterasi ini yang dibuang"
        assert lama.exists() and not baru.exists()
    print("selftest OK")


# Efek samping mulai DI SINI — di atas ini definisi saja, supaya `--selftest` tak menyentuh
# environment, tak menulis ke package graphify, dan bisa jalan di mesin tanpa kredensial.
if "--selftest" in sys.argv:
    selftest()
    sys.exit(0)

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

changed = sync_manifest()
print(f"manifest disinkronkan: {len(changed)} file berubah sejak ekstraksi terakhir"
      + (" -> " + ", ".join(changed) if changed else ""), flush=True)

# Urutan kerja saja (file gemuk dulu) — bukan sumber floor.
initial = counts()
targets = sorted((f for f in json.loads(MANIFEST.read_text(encoding="utf-8"))),
                 key=lambda f: -initial.get(f, 0))

# `--only a.md b.md`: ekstrak ulang hanya file yang memang basi. Graph = cache navigasi yang boleh
# sengaja lag (CLAUDE.md), jadi memaksa 12 file padahal 5 yang berubah cuma membakar uang dan
# memberi 7 kesempatan tambahan bagi variasi LLM untuk menjatuhkan guard.
if "--only" in sys.argv:
    picked = [a for a in sys.argv[sys.argv.index("--only") + 1:] if not a.startswith("--")]
    unknown = [p for p in picked if p not in targets]
    if unknown:
        sys.exit(f"--only: tak ada di manifest: {', '.join(unknown)}")
    targets = [t for t in targets if t in picked]
    print(f"--only: {len(targets)} file dari {len(initial)} — {', '.join(targets)}", flush=True)

for i, rel in enumerate(targets, 1):
    # Floor diambil dari graph BERJALAN, bukan snapshot awal loop. Dedup lintas-file memindahkan
    # node antar-file secara sah tiap kali file lain diekstrak ulang, jadi snapshot awal makin
    # basi di iterasi belakangan dan guard menyala tanpa sebab. Terbukti 2026-07-27: AGENTS.md
    # 64 -> 63 padahal hasilnya datang dari CACHE (`semantic cache: 1 hit`, `0 re-extracted`) —
    # ekstraksinya byte-identik dengan yang tadi menghasilkan 64, yang bergeser cuma atribusi
    # dedup. Guard memblokir iterasi yang tak mengekstrak apa pun.
    # Keruntuhan asli tetap tertangkap: hasil rusak anjlok dibanding keadaan beberapa detik lalu.
    floor = counts().get(rel, 0)
    if GRAPH.exists():
        LOOP_BAK.write_bytes(GRAPH.read_bytes())
    m = json.loads(MANIFEST.read_text(encoding="utf-8"))
    m.pop(rel, None)                       # mark this one file as "changed"
    MANIFEST.write_text(json.dumps(m, indent=2), encoding="utf-8")

    print(f"\n=== [{i}/{len(targets)}] {rel} (floor {floor}) ===", flush=True)
    cache_before = cache_entries()
    r = subprocess.run(
        [str(GRAPHIFY), "extract", ".", "--backend", "openai", "--no-gitignore"],
        cwd=ROOT, capture_output=True, text=True,
    )
    for line in ((r.stdout or "") + "\n" + (r.stderr or "")).splitlines():
        if any(k in line for k in ("chunk", "wrote", "tokens:", "Prune", "semantic cache", "re-extracted")):
            print("   ", line.strip(), flush=True)
    if r.returncode != 0:
        print("   FAILED:", (r.stderr or "")[-500:], flush=True)
        drop_new_cache(cache_before)

    got = counts().get(rel, 0)
    print(f"    -> {rel}: {floor} -> {got} nodes", flush=True)
    if got < floor * FLOOR_TOLERANCE:
        GRAPH.write_bytes(LOOP_BAK.read_bytes())
        dropped = drop_new_cache(cache_before)
        sys.exit(f"STOP: {rel} menyusut {floor} -> {got} (di bawah {FLOOR_TOLERANCE:.0%} floor); "
                 f"graph dipulihkan, {dropped} cache entry dibuang — jalankan ulang untuk mencoba lagi.")

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
