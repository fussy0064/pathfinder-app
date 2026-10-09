// Benchmark on a SYNTHETIC road network (grid with jitter, mixed speeds, some one-way streets and
// fast arterials). Not a real map: use it to compare engine versions, not to promise real timings.
// Usage: bench [grid_size=700] [queries=300] [seed=1]
#include <sys/resource.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <random>
#include <string>
#include <vector>

#include "ch_build.hpp"
#include "engine.hpp"

using Clock = std::chrono::steady_clock;
static double ms_since(Clock::time_point t) {
  return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}
static long rss_mb() { struct rusage u; getrusage(RUSAGE_SELF, &u); return u.ru_maxrss / 1024; }

struct E { uint32_t from, to, dist, time; uint8_t type; };

int main(int argc, char** argv) {
  const int W = argc > 1 ? std::atoi(argv[1]) : 700;
  const int Q = argc > 2 ? std::atoi(argv[2]) : 300;
  std::mt19937 rng(argc > 3 ? std::atoi(argv[3]) : 1);
  if (W < 4 || W > 3000 || Q < 1) { std::fprintf(stderr, "bad args\n"); return 1; }
  const uint32_t n = (uint32_t)W * W;

  // ~100 m block spacing; every 10th row/column is an arterial (60-90 km/h), others 20-50 km/h
  std::vector<int32_t> lat(n), lon(n);
  std::uniform_int_distribution<int> jit(-2000, 2000), pct(0, 99);
  for (int y = 0; y < W; ++y)
    for (int x = 0; x < W; ++x) {
      lat[y * W + x] = 100000000 + y * 9000 + jit(rng);
      lon[y * W + x] = 100000000 + x * 9000 + jit(rng);
    }
  std::vector<E> edges;
  edges.reserve((size_t)n * 5);
  auto add = [&](uint32_t a, uint32_t b, double kmh, bool both) {
    double d = pf::haversine_m(lat[a] * 1e-7, lon[a] * 1e-7, lat[b] * 1e-7, lon[b] * 1e-7);
    uint32_t dist = (uint32_t)std::lround(d), t = std::max<uint32_t>(1, (uint32_t)std::lround(d * 36.0 / kmh));
    edges.push_back({a, b, dist, t, 7});
    if (both) edges.push_back({b, a, dist, t, 7});
  };
  for (int y = 0; y < W; ++y)
    for (int x = 0; x < W; ++x) {
      uint32_t a = y * W + x;
      auto link = [&](int x2, int y2, bool arterial) {
        if (x2 >= W || y2 >= W || (!arterial && pct(rng) < 8)) return;
        double kmh = arterial ? 60 + pct(rng) % 31 : 20 + pct(rng) % 31;
        add(a, y2 * W + x2, kmh, arterial || pct(rng) < 90);
      };
      link(x + 1, y, y % 10 == 0);
      link(x, y + 1, x % 10 == 0);
    }
  std::vector<uint32_t> first(n + 1, 0);
  for (auto& e : edges) ++first[e.from + 1];
  for (uint32_t i = 0; i < n; ++i) first[i + 1] += first[i];
  std::vector<pf::Edge> sorted(edges.size());
  std::vector<uint32_t> pos(first.begin(), first.end() - 1);
  for (auto& e : edges) {
    pf::Edge& o = sorted[pos[e.from]++];
    std::memset(&o, 0, sizeof(o));
    o.target = e.to; o.dist_m = e.dist; o.time_ds = e.time; o.road_type = e.type;
  }
  const std::string path = "/tmp/pf_bench.bin";
  {
    pf::Header h{};
    std::memcpy(h.magic, pf::kMagic, 4);
    h.version = pf::kVersion; h.num_nodes = n; h.num_edges = (uint32_t)sorted.size();
    std::ofstream f(path, std::ios::binary | std::ios::trunc);
    f.write((const char*)&h, sizeof h);
    for (uint32_t i = 0; i <= n; ++i) { pf::Node nd{}; if (i < n) { nd.lat_e7 = lat[i]; nd.lon_e7 = lon[i]; } nd.first_edge = first[i]; f.write((const char*)&nd, sizeof nd); }
    f.write((const char*)sorted.data(), sorted.size() * sizeof(pf::Edge));
  }
  std::vector<E>().swap(edges); std::vector<pf::Edge>().swap(sorted);
  const long base_rss = rss_mb();

  auto t0 = Clock::now();
  pf::MappedGraph g; std::string err;
  if (!g.open(path, err)) { std::fprintf(stderr, "open: %s\n", err.c_str()); return 2; }
  double t_open = ms_since(t0);
  t0 = Clock::now();
  pf::SpatialIndex idx; idx.build(g);
  double t_idx = ms_since(t0);
  t0 = Clock::now();
  pf::Router r(g);
  double t_router = ms_since(t0);

  std::printf("graph: %u nodes, %u edges, file %.1f MB\n", g.num_nodes(), g.num_edges(),
              (sizeof(pf::Header) + (n + 1.0) * sizeof(pf::Node) + g.num_edges() * (double)sizeof(pf::Edge)) / 1048576.0);
  std::printf("open+validate: %.1f ms | spatial index: %.1f ms | router init: %.1f ms | RSS now %ld MB (before load %ld MB)\n",
              t_open, t_idx, t_router, rss_mb(), base_rss);

  struct Stat { std::vector<double> ms; std::vector<double> ex; };
  auto run = [&](bool astar, const char* name) {
    std::mt19937 q(99);
    std::uniform_int_distribution<uint32_t> pick(0, n - 1);
    Stat st;
    for (int i = 0; i < Q; ++i) {
      uint32_t s = pick(q), t = pick(q);
      auto a = Clock::now();
      pf::PathResult res = r.find(s, t, astar);
      double ms = ms_since(a);
      if (!res.found) continue;
      st.ms.push_back(ms); st.ex.push_back(res.expanded);
    }
    std::sort(st.ms.begin(), st.ms.end());
    auto pc = [&](double p) { return st.ms[std::min(st.ms.size() - 1, (size_t)(p * st.ms.size()))]; };
    double ex = 0; for (double e : st.ex) ex += e; ex /= st.ex.size();
    std::printf("%-9s %zu routes | median %.1f ms | p95 %.1f ms | max %.1f ms | avg nodes expanded %.0f\n",
                name, st.ms.size(), pc(0.5), pc(0.95), st.ms.back(), ex);
  };
  run(true, "A*");
  run(false, "Dijkstra");

  // ---- Contraction Hierarchies ----
  {
    std::vector<char> img;
    pf::ChBuildStats st;
    auto tb = Clock::now();
    if (!pf::build_ch(g, img, err, &st)) { std::fprintf(stderr, "ch: %s\n", err.c_str()); return 3; }
    std::printf("CH build (desktop, one time): %.1f s | %u shortcuts | file %.1f MB\n", ms_since(tb) / 1000.0,
                st.shortcuts, img.size() / 1048576.0);
    const std::string cpath = path + ".ch";
    { std::ofstream f(cpath, std::ios::binary | std::ios::trunc); f.write(img.data(), (std::streamsize)img.size()); }
    pf::ChGraph ch;
    auto tl = Clock::now();
    if (!ch.open(cpath, g, err)) { std::fprintf(stderr, "ch open: %s\n", err.c_str()); return 3; }
    std::printf("CH open+validate (app start): %.1f ms\n", ms_since(tl));
    pf::ChRouter cr(ch);
    std::mt19937 q(99);
    std::uniform_int_distribution<uint32_t> pick(0, n - 1);
    std::vector<double> ms; int diff = 0; double ex = 0;
    for (int i = 0; i < Q; ++i) {
      uint32_t s = pick(q), t = pick(q);
      auto a = Clock::now();
      pf::PathResult res = cr.find(s, t);
      double m = ms_since(a);
      pf::PathResult ref = r.find(s, t, true);   // same pairs as the A* run above
      if (res.found != ref.found || res.cost_ds != ref.cost_ds) ++diff;
      if (res.found) { ms.push_back(m); ex += res.expanded; }
    }
    std::sort(ms.begin(), ms.end());
    std::printf("CH        %zu routes | median %.2f ms | p95 %.2f ms | max %.2f ms | avg nodes expanded %.0f | differences to A*: %d\n",
                ms.size(), ms[ms.size() / 2], ms[(size_t)(0.95 * ms.size())], ms.back(), ex / ms.size(), diff);
    std::remove(cpath.c_str());
  }

  // "city drive" = short/medium trips (about 2-5 km): pick target near source
  {
    std::mt19937 q(7);
    std::uniform_int_distribution<int> px(0, W - 1), d(-40, 40);
    std::vector<double> ms;
    for (int i = 0; i < Q; ++i) {
      int sx = px(q), sy = px(q);
      int tx = std::min(W - 1, std::max(0, sx + d(q))), ty = std::min(W - 1, std::max(0, sy + d(q)));
      auto a = Clock::now();
      pf::PathResult res = r.find(sy * W + sx, ty * W + tx, true);
      double t = ms_since(a);
      if (res.found) ms.push_back(t);
    }
    std::sort(ms.begin(), ms.end());
    std::printf("A* short trips (<= ~4 km): %zu routes | median %.1f ms | p95 %.1f ms | max %.1f ms\n",
                ms.size(), ms[ms.size() / 2], ms[(size_t)(0.95 * ms.size())], ms.back());
  }
  std::remove(path.c_str());
  return 0;
}
