"""Tests tools/make_pack.py: catalog contents, hashes, and rejection of hostile catalogs."""
import copy, hashlib, importlib.util, json, os, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("make_pack", os.path.join(ROOT, "tools/make_pack.py"))
mp = importlib.util.module_from_spec(spec); spec.loader.exec_module(mp)

fails = 0
def check(c, msg):
    global fails
    if not c:
        fails += 1; print("FAIL:", msg)

with tempfile.TemporaryDirectory() as d:
    src, out = os.path.join(d, "in"), os.path.join(d, "out")
    os.makedirs(src)
    open(os.path.join(src, "map_graph.bin"), "wb").write(b"graph-bytes" * 10)
    open(os.path.join(src, "places.db"), "wb").write(b"places" * 7)
    open(os.path.join(src, "notes.txt"), "wb").write(b"must not be packed")
    r = subprocess.run([sys.executable, mp.__file__, "build", src, out, "--id", "demo-city", "--name", "Demo City", "--version", "1"],
                       capture_output=True, text=True)
    print(r.stdout.strip(), r.stderr.strip())
    check(r.returncode == 0, "build exit code")
    cat = json.load(open(os.path.join(out, "catalog.json")))
    files = {f["name"]: f for f in cat["packs"][0]["files"]}
    check(sorted(files) == ["map_graph.bin", "places.db"], f"only allowed files packed: {sorted(files)}")
    check(files["map_graph.bin"]["sha256"] == hashlib.sha256(b"graph-bytes" * 10).hexdigest(), "sha256 correct")
    check(files["map_graph.bin"]["size"] == 110, "size correct")
    check(os.path.isfile(os.path.join(out, "demo-city", "map_graph.bin")), "file copied")
    check(not os.path.exists(os.path.join(out, "demo-city", "notes.txt")), "unlisted file not copied")
    check(mp.validate_catalog(cat) == [], "own catalog valid")
    # merge keeps other packs
    r2 = subprocess.run([sys.executable, mp.__file__, "build", src, out, "--id", "second", "--name", "Second", "--version", "2", "--merge"], capture_output=True, text=True)
    check(r2.returncode == 0 and len(json.load(open(os.path.join(out, "catalog.json")))["packs"]) == 2, "merge adds a pack")
    # no graph -> refuse
    os.remove(os.path.join(src, "map_graph.bin"))
    r3 = subprocess.run([sys.executable, mp.__file__, "build", src, out, "--id", "x", "--name", "X", "--version", "1"], capture_output=True, text=True)
    check(r3.returncode != 0, "missing graph rejected")

good = {"schema": 1, "packs": [{"id": "a", "name": "A", "version": "1", "files": [
    {"name": "map_graph.bin", "path": "a/map_graph.bin", "size": 5, "sha256": "0" * 64}]}]}
check(mp.validate_catalog(good) == [], "good catalog")
def mutate(fn):
    c = copy.deepcopy(good); fn(c); return mp.validate_catalog(c)
f0 = lambda c: c["packs"][0]["files"][0]
cases = {
    "path traversal": lambda c: f0(c).update(path="../../etc/passwd"),
    "absolute path": lambda c: f0(c).update(path="/etc/passwd"),
    "backslash": lambda c: f0(c).update(path="a\\b"),
    "url in path": lambda c: f0(c).update(path="http://evil.example/x"),
    "disallowed name": lambda c: f0(c).update(name="run.sh"),
    "size zero": lambda c: f0(c).update(size=0),
    "size huge": lambda c: f0(c).update(size=10 ** 13),
    "size bool": lambda c: f0(c).update(size=True),
    "bad sha": lambda c: f0(c).update(sha256="ABC"),
    "upper sha": lambda c: f0(c).update(sha256="A" * 64),
    "bad id": lambda c: c["packs"][0].update(id="../x"),
    "wrong schema": lambda c: c.update(schema=2),
    "graph missing": lambda c: c["packs"][0].update(files=[{"name": "places.db", "path": "a/p", "size": 1, "sha256": "0" * 64}]),
    "duplicate files": lambda c: c["packs"][0]["files"].append(copy.deepcopy(f0(c))),
}
for name, fn in cases.items():
    check(len(mutate(fn)) > 0, f"rejects {name}")
check(mp.validate_catalog([]) != [] and mp.validate_catalog("x") != [] and mp.validate_catalog(None) != [], "rejects non-objects")
print("pack tests:", "OK" if fails == 0 else f"{fails} FAILED")
sys.exit(1 if fails else 0)
