# Pathfinder App

Offline shortest-path navigation app.
C++ routing engine + Flutter UI, connected with FFI. Data from OpenStreetMap.

## Structure
```
core/include/   graph_format.hpp (binary format + validator), pathfinder.h (C API)
core/src/       engine.cpp (mmap, spatial index, A*), c_api.cpp
tools/extractor .osm.pbf -> map_graph.bin
tools/graph_info validate + print graph stats
tools/route_cli try a route from the terminal
tests/          test_engine.cpp
mobile/         Flutter FFI plugin (see mobile/README.md)
app/            Flutter app (see app/README.md)
tools/places/   build places.db (search data)
tools/ch_builder  map_graph.bin -> map_graph.bin.ch (fast routing index)
tools/make_pack.py  build a downloadable pack + catalog.json
tools/bench, tools/bench/bench_map.cpp  speed tests
docs/           SECURITY.md, PACKS.md, RELEASE.md
data/sample/    tiny.osm test map
PROGRESS.md     where we are + what is next
```

## Build (Linux)
```
sudo apt install cmake g++ libosmium2-dev libprotozero-dev zlib1g-dev libexpat1-dev libbz2-dev
mkdir build && cd build && cmake .. && make
./extractor ../data/sample/tiny.osm map_graph.bin
./graph_info map_graph.bin
./route_cli map_graph.bin 10.0 10.0 10.002 10.002
ctest --output-on-failure   # run tests
```

Sanitizer build (finds memory bugs):
```
cmake .. -DPF_SANITIZE=ON -DPF_BUILD_TOOLS=OFF && make test_engine && ./test_engine
```
