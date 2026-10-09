// Engine tests: A* vs Dijkstra, nearest-node vs brute force, validator, fuzzing, C API.
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <thread>
#include <random>
#include <string>
#include <vector>

#include "engine.hpp"
#include "pathfinder.h"

static int g_fail = 0, g_checks = 0;
#define CHECK(c) do { ++g_checks; if (!(c)) { ++g_fail; std::printf("FAIL line %d: %s\n", __LINE__, #c); } } while (0)

struct TE { uint32_t from, to, dist, time; uint8_t type; };
struct TG { std::vector<std::pair<int32_t, int32_t>> nodes; std::vector<TE> edges; };

static void add_edge(TG& g, uint32_t a, uint32_t b, double kmh, bool both) {
  double d = pf::haversine_m(g.nodes[a].first * 1e-7, g.nodes[a].second * 1e-7,
                             g.nodes[b].first * 1e-7, g.nodes[b].second * 1e-7);
  uint32_t dist = (uint32_t)std::lround(d);
  uint32_t time = std::max<uint32_t>(1, (uint32_t)std::lround(d * 36.0 / kmh));
  g.edges.push_back({a, b, dist, time, 5});
  if (both) g.edges.push_back({b, a, dist, time, 5});
}

static bool write_graph(const TG& g, const std::string& path) {
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

static TG make_grid(int W, int H, std::mt19937& rng) {
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

static std::string tmp_path(const char* name) { return "/tmp/pf_test_" + std::to_string(getpid()) + "_" + name; }

static void test_astar_vs_dijkstra() {
  std::mt19937 rng(12345);
  TG g = make_grid(40, 40, rng);
  std::string p = tmp_path("grid.bin");
  CHECK(write_graph(g, p));
  pf::MappedGraph mg; std::string err;
  CHECK(mg.open(p, err));
  pf::Router r(mg);
  std::uniform_int_distribution<uint32_t> pick(0, mg.num_nodes() - 1);
  unsigned long long ex_a = 0, ex_d = 0; int found = 0;
  for (int i = 0; i < 500; ++i) {
    uint32_t s = pick(rng), t = pick(rng);
    pf::PathResult a = r.find(s, t, true), d = r.find(s, t, false);
    CHECK(a.found == d.found);
    if (!a.found) continue;
    ++found;
    CHECK(a.cost_ds == d.cost_ds);
    ex_a += a.expanded; ex_d += d.expanded;
    // path must be connected and its cheapest-edge cost must equal the reported cost
    CHECK(a.nodes.front() == s && a.nodes.back() == t);
    uint64_t sum = 0; bool ok = true;
    for (size_t k = 1; k < a.nodes.size() && ok; ++k) {
      uint32_t best = 0xFFFFFFFFu;
      for (auto* e = mg.edge_begin(a.nodes[k - 1]); e != mg.edge_end(a.nodes[k - 1]); ++e)
        if (e->target == a.nodes[k]) best = std::min(best, e->time_ds);
      if (best == 0xFFFFFFFFu) ok = false; else sum += best;
    }
    CHECK(ok); CHECK(sum == a.cost_ds);
  }
  CHECK(found > 100);
  std::printf("[A* vs Dijkstra] %d routes found, same cost every time. Nodes expanded: A*=%llu Dijkstra=%llu\n",
              found, ex_a, ex_d);
  pf::PathResult same = r.find(5, 5, true);
  CHECK(same.found && same.cost_ds == 0 && same.nodes.size() == 1);
  CHECK(!r.find(5, mg.num_nodes(), true).found);  // out-of-range id
  unlink(p.c_str());
}

static void test_nearest() {
  std::mt19937 rng(777);
  TG g = make_grid(30, 30, rng);
  std::string p = tmp_path("near.bin");
  CHECK(write_graph(g, p));
  pf::MappedGraph mg; std::string err;
  CHECK(mg.open(p, err));
  pf::SpatialIndex idx; idx.build(mg);
  std::uniform_int_distribution<int> la(99900000, 100400000), lo(99900000, 100400000);
  int hits = 0;
  for (double max_m : {5000.0, 150.0}) {
    for (int i = 0; i < 1000; ++i) {
      int32_t qlat = la(rng), qlon = lo(rng);
      double bd = 1e18;
      for (uint32_t k = 0; k < mg.num_nodes(); ++k)
        bd = std::min(bd, pf::snap_dist_m(qlat, qlon, mg.node(k).lat_e7, mg.node(k).lon_e7));
      uint32_t node = 0; double d = 0;
      bool got = idx.nearest(qlat, qlon, max_m, node, d);
      CHECK(got == (bd <= max_m));
      if (got) { ++hits; CHECK(std::fabs(d - bd) < 1e-6); }
    }
  }
  std::printf("[Nearest node] matches brute force (%d hits)\n", hits);
  // far away point -> no node
  uint32_t node; double d;
  CHECK(!idx.nearest(500000000, 500000000, 5000.0, node, d));
  unlink(p.c_str());
}

static std::vector<char> read_all(const std::string& p) {
  std::ifstream f(p, std::ios::binary);
  return std::vector<char>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}
static void write_all(const std::string& p, const std::vector<char>& d) {
  std::ofstream f(p, std::ios::binary | std::ios::trunc);
  f.write(d.data(), (std::streamsize)d.size());
}

static void test_validator_and_fuzz() {
  std::mt19937 rng(42);
  TG g = make_grid(12, 12, rng);
  std::string good = tmp_path("good.bin"), bad = tmp_path("bad.bin");
  CHECK(write_graph(g, good));
  std::vector<char> data = read_all(good);
  std::string err;
  CHECK(pf::validate(data.data(), data.size(), err));

  auto expect_bad = [&](std::vector<char> d, const char* what) {
    std::string e; bool ok = pf::validate(d.data(), d.size(), e);
    CHECK(!ok); if (ok) std::printf("  should reject: %s\n", what);
  };
  { auto d = data; d.resize(d.size() - 1); expect_bad(d, "truncated"); }
  { auto d = data; d.resize(10); expect_bad(d, "tiny"); }
  { auto d = data; d[0] = 'X'; expect_bad(d, "magic"); }
  { auto d = data; d[4] = 9; expect_bad(d, "version"); }
  { auto d = data; pf::Header* h = (pf::Header*)d.data(); h->num_nodes = 0; expect_bad(d, "zero nodes"); }
  { auto d = data; pf::Header* h = (pf::Header*)d.data(); h->num_edges = 0xFFFFFFF0u; expect_bad(d, "huge edges"); }
  { auto d = data; pf::Header* h = (pf::Header*)d.data();
    pf::Edge* e = (pf::Edge*)(d.data() + sizeof(pf::Header) + (size_t)(h->num_nodes + 1) * sizeof(pf::Node));
    e[0].target = h->num_nodes; expect_bad(d, "target out of range"); }
  { auto d = data; pf::Node* n = (pf::Node*)(d.data() + sizeof(pf::Header)); n[3].first_edge = 0xFFFFFFFFu;
    expect_bad(d, "bad first_edge"); }

  // Fuzz: flip random bytes. The loader must never crash. If it accepts the file, queries must be safe.
  std::uniform_int_distribution<size_t> where(0, data.size() - 1);
  std::uniform_int_distribution<int> val(0, 255), nflip(1, 4);
  int accepted = 0, rejected = 0;
  for (int i = 0; i < 1500; ++i) {
    auto d = data;
    for (int k = nflip(rng); k > 0; --k) d[where(rng)] = (char)val(rng);
    write_all(bad, d);
    pf::MappedGraph mg;
    if (!mg.open(bad, err)) { ++rejected; continue; }
    ++accepted;
    pf::SpatialIndex idx; idx.build(mg);
    pf::Router r(mg);
    std::uniform_int_distribution<uint32_t> pick(0, mg.num_nodes() - 1);
    for (int q = 0; q < 5; ++q) { r.find(pick(rng), pick(rng), true); r.find(pick(rng), pick(rng), false); }
    uint32_t node; double dd;
    idx.nearest(100000000, 100000000, 5000.0, node, dd);
  }
  std::printf("[Fuzz] 1500 corrupted files: %d rejected, %d accepted and queried safely, 0 crashes\n", rejected, accepted);
  unlink(good.c_str()); unlink(bad.c_str());
}

static void test_one_way() {
  // 0 -> 1 -> 2 one-way chain, plus isolated node 3
  TG g;
  for (int i = 0; i < 4; ++i) g.nodes.push_back({100000000, 100000000 + i * 10000});
  add_edge(g, 0, 1, 50, false); add_edge(g, 1, 2, 50, false);
  std::string p = tmp_path("oneway.bin");
  CHECK(write_graph(g, p));
  pf::MappedGraph mg; std::string err; CHECK(mg.open(p, err));
  pf::Router r(mg);
  CHECK(r.find(0, 2, true).found);
  CHECK(!r.find(2, 0, true).found);  // wrong direction
  CHECK(!r.find(0, 3, true).found);  // unreachable
  unlink(p.c_str());
}

static void test_c_api() {
  TG g;
  for (int i = 0; i < 4; ++i) g.nodes.push_back({100000000, 100000000 + i * 10000});
  add_edge(g, 0, 1, 50, true); add_edge(g, 1, 2, 50, true);
  std::string p = tmp_path("capi.bin");
  CHECK(write_graph(g, p));

  int st = -1;
  CHECK(pf_engine_open(nullptr, &st) == nullptr && st == PF_ERR_BAD_ARGS);
  CHECK(pf_engine_open("/nonexistent/file.bin", &st) == nullptr && st == PF_ERR_FILE);
  { std::vector<char> junk(100, 'x'); write_all(p + ".junk", junk);
    CHECK(pf_engine_open((p + ".junk").c_str(), &st) == nullptr && st == PF_ERR_INVALID_GRAPH);
    unlink((p + ".junk").c_str()); }

  pf_engine* e = pf_engine_open(p.c_str(), &st);
  CHECK(e && st == PF_OK && pf_engine_node_count(e) == 4);
  pf_route r;
  CHECK(pf_route_find(e, 10.0, 10.0, 10.0, 10.002, &r) == PF_OK);
  CHECK(r.num_points == 3 && r.distance_m > 200 && r.distance_m < 240 && r.time_s > 14 && r.time_s < 18);
  std::printf("[C API] route: %u points, %.0f m, %.1f s\n", r.num_points, r.distance_m, r.time_s);
  pf_route_free(&r); pf_route_free(&r);  // double free must be safe
  CHECK(r.lat == nullptr);
  CHECK(pf_route_find(e, 10.0, 10.0, 10.0, 10.003, &r) == PF_ERR_NO_ROUTE);   // node 3 isolated
  CHECK(pf_route_find(e, 50.0, 50.0, 10.0, 10.0, &r) == PF_ERR_NO_START);
  CHECK(pf_route_find(e, 10.0, 10.0, 50.0, 50.0, &r) == PF_ERR_NO_TARGET);
  CHECK(pf_route_find(e, NAN, 10.0, 10.0, 10.0, &r) == PF_ERR_BAD_ARGS);
  CHECK(pf_route_find(e, 91.0, 10.0, 10.0, 10.0, &r) == PF_ERR_BAD_ARGS);
  CHECK(pf_route_find(e, 10.0, 181.0, 10.0, 10.0, &r) == PF_ERR_BAD_ARGS);
  CHECK(pf_route_find(e, 10.0, 10.0, 10.0, 10.0, nullptr) == PF_ERR_BAD_ARGS);
  CHECK(pf_route_find(nullptr, 10.0, 10.0, 10.0, 10.0, &r) == PF_ERR_BAD_ARGS);
  pf_engine_close(e); pf_engine_close(nullptr);
  unlink(p.c_str());
}

static void test_handle_api_and_threads() {
  TG g;
  for (int i = 0; i < 4; ++i) g.nodes.push_back({100000000, 100000000 + i * 10000});
  add_edge(g, 0, 1, 50, true); add_edge(g, 1, 2, 50, true);
  std::string p = tmp_path("handle.bin");
  CHECK(write_graph(g, p));
  int st = -1;
  pf_engine* e = pf_engine_open(p.c_str(), &st);
  CHECK(e != nullptr);

  pf_route_handle* h = reinterpret_cast<pf_route_handle*>(0x1);
  CHECK(pf_route_compute(e, 10.0, 10.0, 10.0, 10.002, &h) == PF_OK && h != nullptr);
  CHECK(pf_route_handle_point_count(h) == 3);
  CHECK(pf_route_handle_lats(h) && pf_route_handle_lons(h));
  CHECK(std::fabs(pf_route_handle_lons(h)[2] - 10.002) < 1e-9);
  CHECK(pf_route_handle_distance_m(h) > 200 && pf_route_handle_distance_m(h) < 240);
  CHECK(pf_route_handle_time_s(h) > 14 && pf_route_handle_time_s(h) < 18);
  pf_route_handle_free(h); pf_route_handle_free(nullptr);

  CHECK(pf_route_compute(e, 10.0, 10.0, 10.0, 10.003, &h) == PF_ERR_NO_ROUTE && h == nullptr);
  CHECK(pf_route_compute(e, NAN, 10.0, 10.0, 10.0, &h) == PF_ERR_BAD_ARGS && h == nullptr);
  CHECK(pf_route_compute(e, 10.0, 10.0, 10.0, 10.0, nullptr) == PF_ERR_BAD_ARGS);
  CHECK(pf_route_handle_point_count(nullptr) == 0 && pf_route_handle_lats(nullptr) == nullptr);

  // 8 threads share one engine (calls are serialized inside)
  std::vector<std::thread> ts;
  std::vector<int> ok(8, 0);
  for (int k = 0; k < 8; ++k)
    ts.emplace_back([&, k] {
      for (int i = 0; i < 200; ++i) {
        pf_route_handle* hh = nullptr;
        if (pf_route_compute(e, 10.0, 10.0, 10.0, 10.002, &hh) == PF_OK && pf_route_handle_point_count(hh) == 3) ++ok[k];
        pf_route_handle_free(hh);
      }
    });
  for (auto& t : ts) t.join();
  bool all = true; for (int v : ok) all = all && v == 200;
  CHECK(all);
  std::printf("[Handle API] correct; 8 threads x 200 routes on one engine: all OK\n");
  pf_engine_close(e);
  unlink(p.c_str());
}

int main() {
  test_astar_vs_dijkstra();
  test_nearest();
  test_validator_and_fuzz();
  test_one_way();
  test_c_api();
  test_handle_api_and_threads();
  std::printf("\n%d checks, %d failed\n", g_checks, g_fail);
  return g_fail ? 1 : 0;
}
