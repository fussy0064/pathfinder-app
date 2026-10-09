# Pathfinder App

Offline shortest-path navigation app.
C++ routing engine + Flutter UI, connected with FFI. Data from OpenStreetMap.

## Structure
```
core/include/   graph_format.hpp  (binary format + validator)
tools/extractor .osm.pbf -> map_graph.bin
tools/graph_info validate + print graph stats
data/sample/    tiny.osm test map
PROGRESS.md     where we are + what is next
```

## Build (Linux)
```
sudo apt install cmake g++ libosmium2-dev libprotozero-dev zlib1g-dev libexpat1-dev libbz2-dev
mkdir build && cd build && cmake .. && make
./extractor ../data/sample/tiny.osm map_graph.bin
./graph_info map_graph.bin
```
