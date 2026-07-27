"""Merge nodes that name the same thing from different source files.

graphify extracts one file per request, so a decision cited in six documents comes
back as six nodes — each labelled from that document's angle ("ADR-0021 · Periode,
hadiah, pemenang" from 08_DATA_SCHEMA, "ADR-0021 · Notifikasi pemenang" from
RELEASE). graphify only dedups exact ids, so they survive as separate islands and
a query for ADR-0021 reaches whichever fragment matched first.

Merging keeps every edge and keeps the label from the entity's home document
(ADR-* → docs/06, T-* → docs/04), so the surviving node carries the authoritative
title plus the union of all cross-document links. Absorbed labels are kept in
`aliases` so per-document phrasings stay searchable.

Only unambiguous identities merge: an ADR/T code, a corpus filename, or a fully
identical typed label. "Table: board" and "Entity: Board" stay distinct.

Run after every extraction, before cluster-only:
    python tools/graphify-dedup-crossfile.py [--apply]
Without --apply it only reports.
"""
import json
import pathlib
import re
import sys
from collections import Counter, defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
GRAPH = ROOT / "graphify-out" / "graph.json"
MANIFEST = ROOT / "graphify-out" / "manifest.json"

CODE = re.compile(r"^(ADR-\d{3,4}|T-\d{2,3})\b", re.I)
PREFIX = re.compile(r"^([A-Za-zÀ-ÿ][\w /-]{0,24}?)\s*[:·]\s*(.+)$")
# Sol varies the type prefix per file; fold the synonyms so they group.
SYNONYM = {
    "modul": "module", "module": "module", "konsep": "concept", "concept": "concept",
    "entitas": "entity", "entity": "entity", "tabel": "table", "table": "table",
    "komponen": "component", "component": "component", "dokumen": "doc",
    "dokumen terkait": "doc", "document": "doc", "layanan": "service",
    "service": "service", "teknologi": "tech", "technology": "tech",
    "endpoint": "endpoint", "invariant": "invariant", "invarian": "invariant",
}
HOME = {"ADR": "docs/06_PROGRESS_DECISIONS.md", "T": "docs/04_TASKS.md"}


def load_filenames() -> set[str]:
    try:
        return {pathlib.PurePosixPath(f).name.lower() for f in json.loads(MANIFEST.read_text(encoding="utf-8"))}
    except OSError:
        return set()


def key_of(label: str, filenames: set[str]) -> str | None:
    lab = (label or "").strip()
    m = CODE.match(lab)
    if m:
        return "code:" + m.group(1).upper()
    pm = PREFIX.match(lab)
    prefix, rest = (pm.group(1), pm.group(2)) if pm else ("", lab)
    bare = pathlib.PurePosixPath(rest.strip()).name.lower()
    if bare in filenames:               # "Dokumen: 06_X.md" == "Dokumen terkait: docs/06_X.md"
        return "file:" + bare
    if not lab:
        return None
    return f"lbl:{SYNONYM.get(prefix.lower().strip(), prefix.lower().strip())}|{rest.strip().lower()}"


def main() -> None:
    apply = "--apply" in sys.argv
    g = json.loads(GRAPH.read_text(encoding="utf-8"))
    nodes, links = g["nodes"], g["links"]

    deg = Counter()
    for l in links:
        deg[l["source"]] += 1
        deg[l["target"]] += 1

    groups: dict[str, list[dict]] = defaultdict(list)
    filenames = load_filenames()
    for n in nodes:
        k = key_of(n.get("label", ""), filenames)
        if k:
            groups[k].append(n)

    remap: dict[str, str] = {}
    aliases: dict[str, set[str]] = defaultdict(set)
    merged_groups = 0
    for k, members in groups.items():
        if len(members) < 2:
            continue
        home = HOME.get(k[5:].split("-")[0].upper()) if k.startswith("code:") else None
        canon = max(members, key=lambda n: (n.get("source_file") == home,
                                            deg[n["id"]], len(n.get("label", ""))))
        merged_groups += 1
        for n in members:
            if n["id"] != canon["id"]:
                remap[n["id"]] = canon["id"]
                aliases[canon["id"]].add(n.get("label", ""))

    # Nodes with no source_file cannot be traced back to any document; in this corpus they
    # are sentence-shrapnel ("Konsep: agent", "Tanggal: 2026-07-07") that only link to each
    # other. Every legitimate node carries a source_file.
    sourceless = {n["id"] for n in nodes if not n.get("source_file")}
    kept = [n for n in nodes if n["id"] not in remap and n["id"] not in sourceless]
    for n in kept:
        if n["id"] in aliases:
            n["aliases"] = sorted(a for a in aliases[n["id"]] if a and a != n.get("label"))

    alive = {n["id"] for n in kept}
    seen, new_links = set(), []
    for l in links:
        s, t = remap.get(l["source"], l["source"]), remap.get(l["target"], l["target"])
        if s == t:                       # self-loop created by the merge
            continue
        if s not in alive or t not in alive:
            continue
        sig = (s, t, l.get("relation"))
        if sig in seen:
            continue
        seen.add(sig)
        new_links.append({**l, "source": s, "target": t})

    for h in g.get("hyperedges", []):
        h["nodes"] = list(dict.fromkeys(remap.get(x, x) for x in h.get("nodes", [])))

    print(f"grup dilebur : {merged_groups}")
    print(f"tanpa source : {len(sourceless)} node dibuang")
    print(f"node         : {len(nodes)} -> {len(kept)}  (-{len(remap) + len(sourceless)})")
    print(f"link         : {len(links)} -> {len(new_links)}")
    if not apply:
        print("\n(laporan saja — jalankan dengan --apply untuk menulis graph.json)")
        return

    g["nodes"], g["links"] = kept, new_links
    GRAPH.write_text(json.dumps(g, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\nditulis: {GRAPH}")
    print("next: graphify cluster-only . --backend openai && graphify label . --backend openai")


main()
