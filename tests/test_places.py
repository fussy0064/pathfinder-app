"""Tests the places.db builder and the exact search SQL used by the Flutter app."""
import os, sqlite3, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Must match app/lib/data/places_db.dart
SEARCH_SQL = ("SELECT id, name, kind, lat, lon FROM places WHERE name_norm LIKE ? ESCAPE '\\' "
              "ORDER BY (name_norm LIKE ? ESCAPE '\\') DESC, length(name_norm), name_norm LIMIT ?")

def escape_like(q):  # must match placesDb escapeLike() in Dart
    return q.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')

def search(db, q, limit=20):
    q = q.strip().casefold()
    if len(q) < 2:
        return []
    e = escape_like(q)
    return db.execute(SEARCH_SQL, (f'%{e}%', f'{e}%', limit)).fetchall()

def kinds_sql(n, near):  # must match PlacesDb.kindsSql in places_db.dart
    order = "((lat - ?) * (lat - ?) + ((lon - ?) * ?) * ((lon - ?) * ?))" if near else "name_norm"
    return ("SELECT id, name, kind, lat, lon FROM places WHERE kind IN (%s) ORDER BY %s LIMIT ?"
            % (",".join("?" * n), order))

def by_kinds(db, kinds, near=None, limit=10):
    import math
    params = list(kinds)
    if near:
        c = math.cos(near[0] * math.pi / 180)
        params += [near[0], near[0], near[1], c, near[1], c]
    params.append(limit)
    return db.execute(kinds_sql(len(kinds), near is not None), params).fetchall()

fails = 0
def check(c, msg):
    global fails
    if not c:
        fails += 1
        print("FAIL:", msg)

with tempfile.TemporaryDirectory() as d:
    out = os.path.join(d, "places.db")
    r = subprocess.run([sys.executable, os.path.join(ROOT, "tools/places/build_places.py"),
                        os.path.join(ROOT, "data/sample/places.osm"), out], capture_output=True, text=True)
    print(r.stdout.strip(), r.stderr.strip())
    check(r.returncode == 0, "builder exit code")
    db = sqlite3.connect(out)
    check(db.execute("PRAGMA user_version").fetchone()[0] == 1, "schema version")
    names = sorted(x[0] for x in db.execute("SELECT name FROM places"))
    check(names == ["100% Fresh_Market", "Blue Cafe", "City Hospital", "Main Street", "Red Cafe"], f"names {names}")
    check(not os.path.exists(out + ".tmp"), "temp file removed")
    check([x[1] for x in search(db, "cafe")] == ["Red Cafe", "Blue Cafe"], "substring search (shorter name first)")
    check([x[1] for x in search(db, "CITY")] == ["City Hospital"], "case-insensitive")
    check([x[1] for x in search(db, "ma")][:1] in (["Main Street"],), "prefix match ranks first")
    check(search(db, "c") == [], "1 char returns nothing")
    check(search(db, "%%") == [], "percent sign is literal, not wildcard")
    check([x[1] for x in search(db, "0% f")] == ["100% Fresh_Market"], "literal percent matches")
    check(search(db, "fre_h") == [], "underscore is literal, not a wildcard")
    check([x[1] for x in search(db, "fresh_m")] == ["100% Fresh_Market"], "literal underscore matches")
    check(search(db, "'; DROP TABLE places; --") == [], "injection text is just text")
    check(db.execute("SELECT count(*) FROM places").fetchone()[0] == 5, "table intact")
    check(len(search(db, "e", 20)) == 0, "single char blocked")
    check(len(search(db, "an", 1)) <= 1, "limit respected")
    # AI search: kinds + nearest first
    check([x[1] for x in by_kinds(db, ["amenity:hospital", "amenity:clinic"])] == ["City Hospital"], "kinds filter")
    check([x[1] for x in by_kinds(db, ["amenity:cafe"], near=(10.0, 10.0))] == ["Red Cafe", "Blue Cafe"], "nearest cafe first (from SW corner)")
    check([x[1] for x in by_kinds(db, ["amenity:cafe"], near=(10.002, 10.002))] == ["Blue Cafe", "Red Cafe"], "nearest cafe first (from NE corner)")
    check([x[1] for x in by_kinds(db, ["amenity:cafe"])] == ["Blue Cafe", "Red Cafe"], "no position: name order")
    check(len(by_kinds(db, ["amenity:cafe"], near=(10.0, 10.0), limit=1)) == 1, "limit respected")
    check(by_kinds(db, ["amenity:nothing"]) == [], "unknown kind: empty")
    check(by_kinds(db, ["amenity:cafe' OR '1'='1"]) == [], "kind text is a value, not SQL")
    check(db.execute("SELECT count(*) FROM places").fetchone()[0] == 5, "table still intact")
print("places tests:", "OK" if fails == 0 else f"{fails} FAILED")
sys.exit(1 if fails else 0)
