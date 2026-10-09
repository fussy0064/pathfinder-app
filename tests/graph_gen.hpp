// Shared helpers for the C++ tests: random road graphs written in the PFGR format.
#pragma once
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <random>
#include <string>
#include <vector>

#include "engine.hpp"

struct TE { uint32_t from, to, dist, time; uint8_t type; };
struct TG { std::vector<std::pair<int32_t, int32_t>> nodes; std::vector<TE> edges; };

inline void add_edge(TG& g, uint32_t a, uint32_t b, double kmh, bool both) {
  double d = pf::haversine_m(g.nodes[a].first * 1e-7, g.nodes[a].second * 1e-7,
                             g.nodes[b].first * 1e-7, g.nodes[b].second * 1e-7);
  uint32_t dist = (uint32_t)std::lround(d);
  uint32_t time = std::max<uint32_t>(1, (uint32_t)std::lround(d * 36.0 / kmh));
  g.edges.push_back({a, b, dist, time, 5});
  if (both) g.edges.push_back({b, a, dist, time, 5});
}

inline bool write_graph(const TG& g, const std::string& path) {
  size_t n = g.nodes.size();
  std::vector<uint32_t> first(n + 1, 0);
  for (auto& e : g.edges) ++first[e.from + 1];
  for (size_t i = 0; i < n; ++i) first[i + 1] += first[i];
  std::vector<pf::Edge> sorted(g.edges.size());
  std::vector<uint32_t> pos(first.begin(), first.end() - 1);
  for (auto& e : g.edges) {
    pf::Edge& o = sorted[pos[e.from]++];
    std::memset(&o, 0, sizeof(o));
    o.target = e.to; o.dist_m = e.dist; o.time_ds = e.time; o.road_type = e.type;
  }
  pf::Header h{};
  std::memcpy(h.magic, pf::kMagic, 4);
  h.version = pf::kVersion; h.num_nodes = (uint32_t)n; h.num_edges = (uint32_t)sorted.size();
  std::ofstream f(path, std::ios::binary | std::ios::trunc);
  f.write((const char*)&h, sizeof(h));
  for (size_t i = 0; i <= n; ++i) {
    pf::Node nd{};
    if (i < n) { nd.lat_e7 = g.nodes[i].first; nd.lon_e7 = g.nodes[i].second; }
    nd.first_edge = first[i];
    f.write((const char*)&nd, sizeof(nd));
  }
  f.write((const char*)sorted.data(), sorted.size() * sizeof(pf::Edge));
  return (bool)f;
}

inline TG make_grid(int W, int H, std::mt19937& rng) {
  TG g;
  std::uniform_int_distribution<int> jit(-2000, 2000), pick(0, 99);
  const double speeds[] = {20, 30, 50, 80, 120};
  for (int y = 0; y < H; ++y)
    for (int x = 0; x < W; ++x)
      g.nodes.push_back({100000000 + y * 10000 + jit(rng), 100000000 + x * 10000 + jit(rng)});
  auto id = [&](int x, int y) { return (uint32_t)(y * W + x); };
  for (int y = 0; y < H; ++y)
    for (int x = 0; x < W; ++x) {
      auto link = [&](int x2, int y2) {
        if (x2 >= W || y2 >= H || pick(rng) < 10) return;
        bool both = pick(rng) < 85;
        uint32_t a = id(x, y), b = id(x2, y2);
        if (!both && pick(rng) < 50) std::swap(a, b);
        add_edge(g, a, b, speeds[pick(rng) % 5], both);
      };
      link(x + 1, y); link(x, y + 1); link(x + 1, y + 1);
    }
  return g;
}

inline std::string tmp_path(const char* name) { return "/tmp/pf_test_" + std::to_string(getpid()) + "_" + name; }

inline std::vector<char> read_all(const std::string& p) {
  std::ifstream f(p, std::ios::binary);
  return std::vector<char>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}
inline void write_all(const std::string& p, const std::vector<char>& d) {
  std::ofstream f(p, std::ios::binary | std::ios::trunc);
  f.write(d.data(), (std::streamsize)d.size());
}

