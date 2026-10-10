"""Checks app/lib/l10n/strings.dart: same keys and placeholders in English and Swahili,
no empty text, and every t('key') used in the app exists."""
import glob, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = open(os.path.join(ROOT, "app/lib/l10n/strings.dart"), encoding="utf-8").read()

def table(name):
    m = re.search(r"static const Map<String, String> %s = \{(.*?)\n  \};" % name, src, re.S)
    assert m, f"table {name} not found"
    out = {}
    for line in m.group(1).splitlines():
        mm = re.match(r"^\s*'([A-Za-z0-9_]+)':\s*'((?:[^'\\]|\\.)*)',\s*$", line)
        if mm:
            assert mm.group(1) not in out, f"duplicate key {mm.group(1)} in {name}"
            out[mm.group(1)] = mm.group(2)
        elif line.strip() and not line.strip().startswith("//"):
            print("UNPARSED LINE in", name, ":", line[:80]); sys.exit(1)
    return out

en, sw = table("_en"), table("_sw")
fails = 0
def check(c, msg):
    global fails
    if not c:
        fails += 1; print("FAIL:", msg)

check(set(en) == set(sw), f"key sets differ: only EN {sorted(set(en)-set(sw))}, only SW {sorted(set(sw)-set(en))}")
ph = lambda s: sorted(re.findall(r"\{(\w+)\}", s))
for k in sorted(set(en) & set(sw)):
    check(ph(en[k]) == ph(sw[k]), f"placeholders differ for '{k}': {ph(en[k])} vs {ph(sw[k])}")
    check(en[k].strip() != "" and sw[k].strip() != "", f"empty text for '{k}'")
same = [k for k in en if en[k] == sw.get(k) and k not in ("fastRouting",)]
print("keys:", len(en), "| identical in both languages (ok only for codes/names):", same)

code = ""
for f in glob.glob(os.path.join(ROOT, "app/lib/**/*.dart"), recursive=True):
    if not f.endswith("strings.dart"):
        code += open(f, encoding="utf-8").read() + "\n"
# keys passed to t(...) / tr(...) / _fail(...) / _setMessage(...) / AiException(...) must exist
called = set(re.findall(r"\b(?:t|tr|_fail|_setMessage|AiException)\('([A-Za-z0-9_]+)'", code))
check(not (called - set(en)), f"keys used in code but not defined: {sorted(called - set(en))}")
# every key must appear as a string literal somewhere (direct or indirect use)
literals = set(re.findall(r"'([A-Za-z0-9_]+)'", code))
unused = sorted(k for k in en if k not in literals)
check(not unused, f"keys defined but never referenced: {unused}")
# no leftover hard-coded English in widgets
for f in glob.glob(os.path.join(ROOT, "app/lib/ui/*.dart")):
    for n, line in enumerate(open(f, encoding="utf-8"), 1):
        m = re.search(r"\bText\((?:'|\")([A-Za-z][^'\"$]{2,})", line)
        if m and m.group(1) not in ("English", "Kiswahili"):
            check(False, f"hard-coded text in {os.path.basename(f)}:{n}: {m.group(1)[:40]}")
print("keys referenced from code:", len(called), "| total keys:", len(en))
print("l10n tests:", "OK" if fails == 0 else f"{fails} FAILED")
sys.exit(1 if fails else 0)
