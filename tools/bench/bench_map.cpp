// bench_map: timing + memory on a REAL graph file (A*, Dijkstra, and CH when available).
// Usage: bench_map <map_graph.bin> [--queries N] [--seed S] [--dump pairs.txt]
// If <map_graph.bin>.ch does not exist, the CH index is built in memory first (and timed).
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <random>
#include <string>
#include <vector>

#include "ch_build.hpp"
#include "engine.hpp"

using Clock = std::chrono::steady_clock;
static double ms_since(Clock::time_point t) { return std::chrono::duration<double, std::milli>(Clock::now() - t).count(); }
static long rss_kb() {
  std::ifstream f("/proc/self/status");
  std::string line;
  while (std::getline(f, line))
    if (line.rfind("VmRSS:", 0) == 0) return std::atol(line.c_str() + 6);
  return -1;
}

struct Stats { std::vector<double> t; unsigned long long expanded = 0; };
static void report(const char* name, Stats& s) {
  if (s.t.empty()) { std::printf("%-9s no routes\n", name); return; }
  std::sort(s.t.begin(), s.t.end());
  auto q = [&](double p) { return s.t[std::min(s.t.size() - 1, (size_t)(p * s.t.size()))]; };
  size_t under = std::count_if(s.t.begin(), s.t.end(), [](double v) { return v < 10.0; });
  std::printf("%-9s median %.3f ms | p95 %.3f | p99 %.3f | max %.3f | <10 ms: %.1f%% | avg nodes expanded %llu\n", name, q(0.5), q(0.95), q(0.99),
              s.t.back(), 100.0 * under / s.t.size(), s.expanded / s.t.size());
}

int main(int argc, char** argv) {
  if (argc < 2) { std::fprintf(stderr, "Usage: bench_map <map_graph.bin> [--queries N] [--seed S] [--dump file]\n"); return 1; }
  const std::string path = argv[1];
  std::string dump;
  int queries = 500; unsigned seed = 7;
  for (int i = 2; i + 1 < argc; i += 2) {
    if (!std::strcmp(argv[i], "--queries")) queries = std::atoi(argv[i + 1]);
    else if (!std::strcmp(argv[i], "--seed")) seed = (unsigned)std::atoi(argv[i + 1]);
    else if (!std::strcmp(argv[i], "--dump")) dump = argv[i + 1];
  }
  if (queries < 1 || queries > 1000000) { std::fprintf(stderr, "bad --queries\n"); return 1; }
  long rss0 = rss_kb();
  auto t0 = Clock::now();
  pf::MappedGraph g; std::string err;
  if (!g.open(path, err)) { std::fprintf(stderr, "open failed: %s\n", err.c_str()); return 2; }
  double t_open = ms_since(t0);
  auto t1 = Clock::now(); pf::SpatialIndex idx; idx.build(g); double t_idx = ms_since(t1);
  pf::Router r(g);
  std::printf("graph: %u nodes, %u edges | open+validate %.1f ms | spatial index %.1f ms | RSS +%ld KB (incl. mapped file)\n",
              g.num_nodes(), g.num_edges(), t_open, t_idx, rss_kb() - rss0);

  std::string cpath = path + ".ch", tmp_ch;
  if (access(cpath.c_str(), R_OK) != 0) {
    std::vector<char> img; pf::ChBuildStats st;
    auto tb = Clock::now();
    if (!pf::build_ch(g, img, err, &st)) { std::fprintf(stderr, "ch build: %s\n", err.c_str()); return 3; }
    tmp_ch = "/tmp/pf_benchmap_" + std::to_string(getpid()) + ".ch";
    { std::ofstream f(tmp_ch, std::ios::binary | std::ios::trunc); f.write(img.data(), (std::streamsize)img.size()); }
    cpath = tmp_ch;
    std::printf("CH build (one time, desktop): %.2f s | %u shortcuts | %.2f MB\n", ms_since(tb) / 1000.0, st.shortcuts, img.size() / 1048576.0);
  }
  pf::ChGraph ch; auto tl = Clock::now();
  if (!ch.open(cpath, g, err)) { std::fprintf(stderr, "ch open: %s\n", err.c_str()); return 3; }
  std::printf("CH open+validate (app start): %.1f ms\n", ms_since(tl));
  pf::ChRouter cr(ch);

  std::mt19937 rng(seed);
  std::uniform_int_distribution<uint32_t> pick(0, g.num_nodes() - 1);
  Stats a, d, c; int found = 0, bad = 0;
  std::ofstream out; if (!dump.empty()) out.open(dump);
  for (int i = 0; i < queries; ++i) {
    uint32_t s = pick(rng), t = pick(rng);
    auto ta = Clock::now(); pf::PathResult ra = r.find(s, t, true); double ma = ms_since(ta);
    auto td = Clock::now(); pf::PathResult rd = r.find(s, t, false); double md = ms_since(td);
    auto tc = Clock::now(); pf::PathResult rc = cr.find(s, t); double mc = ms_since(tc);
    if (ra.found != rd.found || ra.found != rc.found || (ra.found && (ra.cost_ds != rd.cost_ds || ra.cost_ds != rc.cost_ds))) ++bad;
    if (!ra.found) continue;
    ++found;
    a.t.push_back(ma); d.t.push_back(md); c.t.push_back(mc);
    a.expanded += ra.expanded; d.expanded += rd.expanded; c.expanded += rc.expanded;
    if (out) out << s << ' ' << t << ' ' << ra.cost_ds << '\n';
  }
  std::printf("queries: %d random pairs, %d routable, disagreements between A*, Dijkstra and CH: %d\n", queries, found, bad);
  report("A*", a); report("Dijkstra", d); report("CH", c);
  if (!tmp_ch.empty()) std::remove(tmp_ch.c_str());
  return bad ? 4 : 0;
}
