// Extractor: .osm.pbf / .osm  ->  map_graph.bin (PFGR v1)
// Usage: extractor <input.osm.pbf> <output.bin>
#include <osmium/handler.hpp>
#include <osmium/handler/node_locations_for_ways.hpp>
#include <osmium/index/map/flex_mem.hpp>
#include <osmium/io/any_input.hpp>
#include <osmium/visitor.hpp>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <unordered_map>
#include <vector>

#include "graph_format.hpp"

namespace {

struct RawEdge { uint32_t from, to, dist, time; uint8_t type; };

uint8_t road_type(const char* hw) {
  static const struct { const char* tag; uint8_t rt; } table[] = {
    {"motorway", pf::RT_MOTORWAY},   {"motorway_link", pf::RT_MOTORWAY},
    {"trunk", pf::RT_TRUNK},         {"trunk_link", pf::RT_TRUNK},
    {"primary", pf::RT_PRIMARY},     {"primary_link", pf::RT_PRIMARY},
    {"secondary", pf::RT_SECONDARY}, {"secondary_link", pf::RT_SECONDARY},
    {"tertiary", pf::RT_TERTIARY},   {"tertiary_link", pf::RT_TERTIARY},
    {"unclassified", pf::RT_UNCLASSIFIED}, {"residential", pf::RT_RESIDENTIAL},
    {"service", pf::RT_SERVICE},     {"living_street", pf::RT_LIVING_STREET}};
  for (const auto& r : table) if (std::strcmp(hw, r.tag) == 0) return r.rt;
  return 0;  // not routable (footway, building, etc.)
}

double default_kmh(uint8_t rt) {
  switch (rt) {
    case pf::RT_MOTORWAY: return 100; case pf::RT_TRUNK: return 80;
    case pf::RT_PRIMARY: return 60;   case pf::RT_SECONDARY: return 50;
    case pf::RT_TERTIARY: return 40;  case pf::RT_UNCLASSIFIED: return 40;
    case pf::RT_RESIDENTIAL: return 30; case pf::RT_SERVICE: return 20;
    default: return 10;
  }
}

double parse_kmh(const char* s, double fallback) {
  if (!s) return fallback;
  char* end = nullptr;
  double v = std::strtod(s, &end);
  if (end == s || !std::isfinite(v) || v <= 0) return fallback;
  if (std::strstr(end, "mph")) v *= 1.609344;
  return std::min(140.0, std::max(5.0, v));
}

double haversine_m(double lat1, double lon1, double lat2, double lon2) {
  const double R = 6371000.0, d2r = M_PI / 180.0;
  double dlat = (lat2 - lat1) * d2r, dlon = (lon2 - lon1) * d2r;
  double a = std::sin(dlat / 2) * std::sin(dlat / 2) +
             std::cos(lat1 * d2r) * std::cos(lat2 * d2r) * std::sin(dlon / 2) * std::sin(dlon / 2);
  return 2 * R * std::asin(std::min(1.0, std::sqrt(a)));
}

struct WayHandler : public osmium::handler::Handler {
  std::unordered_map<int64_t, uint32_t> id_map;
  std::vector<int32_t> lat, lon;
  std::vector<RawEdge> edges;
  uint64_t ways_used = 0;

  uint32_t get_id(const osmium::NodeRef& n) {
    auto it = id_map.find(n.ref());
    if (it != id_map.end()) return it->second;
    uint32_t id = static_cast<uint32_t>(lat.size());
    id_map.emplace(n.ref(), id);
    lat.push_back(n.location().y());  // osmium stores degrees * 1e7
    lon.push_back(n.location().x());
    return id;
  }

  void way(const osmium::Way& w) {
    const char* hw = w.tags()["highway"];
    if (!hw) return;
    uint8_t rt = road_type(hw);
    if (!rt) return;
    const char* access = w.tags()["access"];
    if (access && (!std::strcmp(access, "no") || !std::strcmp(access, "private"))) return;
    if (w.nodes().size() < 2) return;

    // oneway: 1 = forward only, -1 = reverse only, 0 = both
    int dir = 0;
    const char* ow = w.tags()["oneway"];
    const char* jn = w.tags()["junction"];
    if (ow && (!std::strcmp(ow, "yes") || !std::strcmp(ow, "1") || !std::strcmp(ow, "true"))) dir = 1;
    else if (ow && !std::strcmp(ow, "-1")) dir = -1;
    else if (!ow && (rt == pf::RT_MOTORWAY || (jn && !std::strcmp(jn, "roundabout")))) dir = 1;

    double kmh = parse_kmh(w.tags()["maxspeed"], default_kmh(rt));
    bool used = false;
    for (size_t i = 1; i < w.nodes().size(); ++i) {
      const auto& a = w.nodes()[i - 1];
      const auto& b = w.nodes()[i];
      if (!a.location().valid() || !b.location().valid() || a.ref() == b.ref()) continue;
      double d = haversine_m(a.location().lat(), a.location().lon(), b.location().lat(), b.location().lon());
      uint32_t dist = static_cast<uint32_t>(std::lround(d));
      uint32_t time = std::max<uint32_t>(1, static_cast<uint32_t>(std::lround(d * 36.0 / kmh)));
      uint32_t ia = get_id(a), ib = get_id(b);
      if (dir >= 0) edges.push_back({ia, ib, dist, time, rt});
      if (dir <= 0) edges.push_back({ib, ia, dist, time, rt});
      used = true;
    }
    if (used) ++ways_used;
  }
};

}  // namespace

int main(int argc, char** argv) {
  if (argc != 3) { std::cerr << "Usage: extractor <input.osm.pbf> <output.bin>\n"; return 1; }
  const std::string in = argv[1], out = argv[2];
  WayHandler h;
  try {
    using Index = osmium::index::map::FlexMem<osmium::unsigned_object_id_type, osmium::Location>;
    using LocHandler = osmium::handler::NodeLocationsForWays<Index>;
    Index index;
    LocHandler loc(index);
    loc.ignore_errors();  // allow region extracts with missing nodes
    osmium::io::Reader reader{in, osmium::osm_entity_bits::node | osmium::osm_entity_bits::way};
    osmium::apply(reader, loc, h);
    reader.close();
  } catch (const std::exception& e) {
    std::cerr << "Read error: " << e.what() << "\n";
    return 2;
  }

  const size_t n = h.lat.size();
  if (n == 0 || n >= 0x7FFFFFFFu || h.edges.size() >= 0x7FFFFFFFu) {
    std::cerr << "No routable roads found (or graph too large)\n";
    return 3;
  }

  // Counting sort edges by source node (forward-star)
  std::vector<uint32_t> first(n + 1, 0);
  for (const auto& e : h.edges) ++first[e.from + 1];
  for (size_t i = 0; i < n; ++i) first[i + 1] += first[i];
  std::vector<pf::Edge> sorted(h.edges.size());
  std::vector<uint32_t> pos(first.begin(), first.end() - 1);
  for (const auto& e : h.edges) {
    pf::Edge& o = sorted[pos[e.from]++];
    o.target = e.to; o.dist_m = e.dist; o.time_ds = e.time; o.road_type = e.type;
    std::memset(o.pad, 0, sizeof(o.pad));
  }

  pf::Header hd{};
  std::memcpy(hd.magic, pf::kMagic, 4);
  hd.version = pf::kVersion;
  hd.num_nodes = static_cast<uint32_t>(n);
  hd.num_edges = static_cast<uint32_t>(sorted.size());
  hd.min_lat_e7 = *std::min_element(h.lat.begin(), h.lat.end());
  hd.max_lat_e7 = *std::max_element(h.lat.begin(), h.lat.end());
  hd.min_lon_e7 = *std::min_element(h.lon.begin(), h.lon.end());
  hd.max_lon_e7 = *std::max_element(h.lon.begin(), h.lon.end());

  // Write to a temp file, then rename (no half-written output)
  const std::string tmp = out + ".tmp";
  {
    std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
    if (!f) { std::cerr << "Cannot write " << tmp << "\n"; return 4; }
    f.write(reinterpret_cast<const char*>(&hd), sizeof(hd));
    for (size_t i = 0; i <= n; ++i) {
      pf::Node nd{};
      if (i < n) { nd.lat_e7 = h.lat[i]; nd.lon_e7 = h.lon[i]; }
      nd.first_edge = first[i];
      f.write(reinterpret_cast<const char*>(&nd), sizeof(nd));
    }
    f.write(reinterpret_cast<const char*>(sorted.data()), sorted.size() * sizeof(pf::Edge));
    if (!f) { std::cerr << "Write failed\n"; return 4; }
  }
  if (std::rename(tmp.c_str(), out.c_str()) != 0) { std::cerr << "Rename failed\n"; return 4; }

  std::cout << "Ways used : " << h.ways_used << "\nNodes     : " << n
            << "\nEdges     : " << sorted.size() << "\nWrote     : " << out << "\n";
  return 0;
}
