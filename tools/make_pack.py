#!/usr/bin/env python3
"""Build a downloadable map pack + catalog.json (for hosting on any HTTPS server).

Usage:
  make_pack.py build <input_dir> <out_dir> --id ID --name NAME --version VERSION [--merge]
  make_pack.py check <catalog.json>

<input_dir> may contain: map_graph.bin (required), map_graph.bin.ch, places.db, tiles.mbtiles
Result:  <out_dir>/catalog.json  and  <out_dir>/<id>/<files>
The rules in validate_catalog() are the same ones the Flutter app enforces (app/lib/data/pack_manager.dart).
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import sys

ALLOWED = ("map_graph.bin", "map_graph.bin.ch", "places.db", "tiles.mbtiles")
MAX_FILE = 4 * 1024 ** 3
ID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
PATH_RE = re.compile(r"^[A-Za-z0-9._/-]{1,200}$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")


def validate_catalog(doc):
    """Returns a list of problems (empty list = catalog is valid)."""
    errs = []
    if not isinstance(doc, dict) or doc.get("schema") != 1:
        return ["schema must be 1"]
    packs = doc.get("packs")
    if not isinstance(packs, list) or len(packs) > 100:
        return ["packs must be a list of at most 100"]
    seen = set()
    for i, p in enumerate(packs):
        tag = f"pack[{i}]"
        if not isinstance(p, dict):
            errs.append(f"{tag}: not an object"); continue
        pid = p.get("id")
        if not isinstance(pid, str) or not ID_RE.match(pid):
            errs.append(f"{tag}: bad id")
        elif pid in seen:
            errs.append(f"{tag}: duplicate id")
        else:
            seen.add(pid)
        for k, mx in (("name", 80), ("version", 32)):
            if not isinstance(p.get(k), str) or not 1 <= len(p[k]) <= mx:
                errs.append(f"{tag}: bad {k}")
        files = p.get("files")
        if not isinstance(files, list) or not 1 <= len(files) <= len(ALLOWED):
            errs.append(f"{tag}: bad files list"); continue
        names = [f.get("name") if isinstance(f, dict) else None for f in files]
        if "map_graph.bin" not in names:
            errs.append(f"{tag}: map_graph.bin is required")
        if len(set(names)) != len(names):
            errs.append(f"{tag}: duplicate file name")
        for f in files:
            if not isinstance(f, dict):
                errs.append(f"{tag}: file is not an object"); continue
            if f.get("name") not in ALLOWED:
                errs.append(f"{tag}: file name not allowed: {f.get('name')!r}")
            path = f.get("path")
            if (not isinstance(path, str) or not PATH_RE.match(path) or path.startswith("/")
                    or ".." in path.split("/") or "//" in path):
                errs.append(f"{tag}: bad path {path!r}")
            size = f.get("size")
            if not isinstance(size, int) or isinstance(size, bool) or not 1 <= size <= MAX_FILE:
                errs.append(f"{tag}: bad size")
            if not isinstance(f.get("sha256"), str) or not SHA_RE.match(f["sha256"]):
                errs.append(f"{tag}: bad sha256")
    return errs


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build(args):
    if not ID_RE.match(args.id):
        sys.exit("bad --id (use a-z, 0-9, _ and -)")
    src = args.input
    if not os.path.isfile(os.path.join(src, "map_graph.bin")):
        sys.exit("map_graph.bin not found in input dir")
    dst = os.path.join(args.out, args.id)
    os.makedirs(dst, exist_ok=True)
    files = []
    for name in ALLOWED:
        p = os.path.join(src, name)
        if not os.path.isfile(p):
            continue
        shutil.copyfile(p, os.path.join(dst, name))
        files.append({"name": name, "path": f"{args.id}/{name}", "size": os.path.getsize(p), "sha256": sha256_file(p)})
    pack = {"id": args.id, "name": args.name, "version": args.version, "files": files}
    cat_path = os.path.join(args.out, "catalog.json")
    doc = {"schema": 1, "packs": []}
    if args.merge and os.path.isfile(cat_path):
        doc = json.load(open(cat_path))
        doc["packs"] = [x for x in doc["packs"] if x.get("id") != args.id]
    doc["packs"].append(pack)
    errs = validate_catalog(doc)
    if errs:
        sys.exit("catalog invalid:\n  " + "\n  ".join(errs))
    tmp = cat_path + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(doc, fh, indent=2)
    os.replace(tmp, cat_path)
    total = sum(f["size"] for f in files)
    print(f"pack '{args.id}': {len(files)} files, {total / 1048576:.1f} MB -> {cat_path}")


def check(args):
    errs = validate_catalog(json.load(open(args.catalog)))
    print("catalog OK" if not errs else "catalog INVALID:\n  " + "\n  ".join(errs))
    sys.exit(1 if errs else 0)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("input"); b.add_argument("out")
    b.add_argument("--id", required=True); b.add_argument("--name", required=True)
    b.add_argument("--version", required=True); b.add_argument("--merge", action="store_true")
    c = sub.add_parser("check")
    c.add_argument("catalog")
    a = ap.parse_args()
    build(a) if a.cmd == "build" else check(a)
