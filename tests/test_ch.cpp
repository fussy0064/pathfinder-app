// Contraction Hierarchies tests: exactness vs Dijkstra, path validity, bad/mismatched files, fuzz, C API.
#include <cstdio>

#include "ch_build.hpp"
#include "graph_gen.hpp"
#include "pathfinder.h"

static int g_fail = 0, g_checks = 0;
#define CHECK(c) do { ++g_checks; if (!(c)) { ++g_fail; std::printf("FAIL line %d: %s\n", __LINE__, #c); } } while (0)

static bool build_to_file(const pf::MappedGraph& mg, const std::string& path) {
  std::vector<char> img; std::string err;
  if (!pf::build_ch(mg, img, err)) return false;
  write_all(path, img);
  return true;
}

static void test_exactness() {
  for (int round = 0; round < 3; ++round) {
    std::mt19937 rng(100 + round);
    TG g = make_grid(round == 0 ? 15 : 35, round == 0 ? 15 : 35, rng);
    std::string gp = tmp_path("chg.bin"), cp = gp + ".ch";
    CHECK(write_graph(g, gp));
    pf::MappedGraph mg; std::string err;
    CHECK(mg.open(gp, err));
    std::vector<char> img; pf::ChBuildStats st;
    CHECK(pf::build_ch(mg, img, err, &st));
    write_all(cp, img);
    pf::ChGraph ch;
    CHECK(ch.open(cp, mg, err));
    pf::ChRouter cr(ch);
    pf::Router r(mg);
    std::uniform_int_distribution<uint32_t> pick(0, mg.num_nodes() - 1);
    int found = 0, bad = 0;
    for (int i = 0; i < 600; ++i) {
      uint32_t s = pick(rng), t = pick(rng);
      pf::PathResult a = cr.find(s, t), d = r.find(s, t, false);
      if (a.found != d.found) { ++bad; continue; }
      if (!a.found) continue;
      ++found;
      if (a.cost_ds != d.cost_ds) ++bad;
      if (a.nodes.front() != s || a.nodes.back() != t) ++bad;
      uint64_t sum = 0; bool ok = true;
      for (size_t k = 1; k < a.nodes.size() && ok; ++k) {
        uint32_t best = 0xFFFFFFFFu;
        for (auto* e = mg.edge_begin(a.nodes[k - 1]); e != mg.edge_end(a.nodes[k - 1]); ++e)
          if (e->target == a.nodes[k]) best = std::min(best, e->time_ds);
        if (best == 0xFFFFFFFFu) ok = false; else sum += best;
      }
      if (!ok || sum != a.cost_ds) ++bad;
    }
    CHECK(bad == 0); CHECK(found > 100);
    std::printf("[CH] %zu nodes: %d routes, 0 differences to Dijkstra, %u shortcuts, built in %.2f s\n",
                (size_t)mg.num_nodes(), found, st.shortcuts, st.seconds);
    CHECK(cr.find(3, 3).found && cr.find(3, 3).nodes.size() == 1);
    CHECK(!cr.find(3, mg.num_nodes()).found);
    unlink(gp.c_str()); unlink(cp.c_str());
  }
}

static void test_bad_files() {
  std::mt19937 rng(5);
  TG g = make_grid(12, 12, rng);
  std::string gp = tmp_path("chb.bin"), cp = gp + ".ch";
  CHECK(write_graph(g, gp));
  pf::MappedGraph mg; std::string err; CHECK(mg.open(gp, err));
  std::vector<char> img; CHECK(pf::build_ch(mg, img, err));
  const uint64_t hash = pf::graph_fingerprint(&mg.node(0), mg.num_nodes(), mg.num_edges());
  CHECK(pf::validate_ch(img.data(), img.size(), mg.num_nodes(), hash, err));

  auto bad = [&](std::vector<char> d, const char* what) {
    std::string e; bool ok = pf::validate_ch(d.data(), d.size(), mg.num_nodes(), hash, e);
    CHECK(!ok); if (ok) std::printf("  should reject: %s\n", what);
  };
  { auto d = img; d.resize(d.size() - 1); bad(d, "truncated"); }
  { auto d = img; d[0] = 'X'; bad(d, "magic"); }
  { auto d = img; d[4] = 7; bad(d, "version"); }
  { auto d = img; pf::ChHeader* h = (pf::ChHeader*)d.data(); h->graph_hash ^= 1; bad(d, "wrong graph hash"); }
  { auto d = img; pf::ChHeader* h = (pf::ChHeader*)d.data(); h->num_nodes += 1; bad(d, "node count"); }
  { auto d = img; uint32_t* rank = (uint32_t*)(d.data() + sizeof(pf::ChHeader)); rank[1] = rank[0]; bad(d, "rank not permutation"); }
  { auto d = img; pf::ChView v = pf::ch_view(d.data()); pf::ChEdge* up = const_cast<pf::ChEdge*>(v.up);
    up[0].target = mg.num_nodes(); bad(d, "target out of range"); }
  { auto d = img; pf::ChView v = pf::ch_view(d.data()); pf::ChEdge* up = const_cast<pf::ChEdge*>(v.up);
    up[0].middle = up[0].target; bad(d, "middle not lower than ends"); }

  // graph and CH from different maps must not mix
  TG g2 = make_grid(12, 12, rng);
  std::string gp2 = tmp_path("chb2.bin");
  CHECK(write_graph(g2, gp2));
  pf::MappedGraph mg2; CHECK(mg2.open(gp2, err));
  write_all(cp, img);
  pf::ChGraph ch; CHECK(!ch.open(cp, mg2, err));
  CHECK(ch.open(cp, mg, err));

  // fuzz: flip random bytes, then load; if accepted, queries must not crash (ASan checks memory)
  std::mt19937 fr(77);
  std::uniform_int_distribution<size_t> where(0, img.size() - 1);
  std::uniform_int_distribution<int> val(0, 255), nflip(1, 4);
  int accepted = 0, rejected = 0;
  for (int i = 0; i < 1500; ++i) {
    auto d = img;
    for (int k = nflip(fr); k > 0; --k) d[where(fr)] = (char)val(fr);
    write_all(cp, d);
    pf::ChGraph c2;
    if (!c2.open(cp, mg, err)) { ++rejected; continue; }
    ++accepted;
    pf::ChRouter cr(c2);
    std::uniform_int_distribution<uint32_t> pick(0, mg.num_nodes() - 1);
    for (int q = 0; q < 6; ++q) cr.find(pick(fr), pick(fr));
  }
  std::printf("[CH fuzz] 1500 corrupted files: %d rejected, %d accepted and queried safely, 0 crashes\n", rejected, accepted);
  unlink(gp.c_str()); unlink(gp2.c_str()); unlink(cp.c_str());
}

static void test_c_api_uses_ch() {
  std::mt19937 rng(9);
  TG g = make_grid(25, 25, rng);
  std::string gp = tmp_path("chc.bin"), cp = gp + ".ch";
  CHECK(write_graph(g, gp));
  int st = 0;
  pf_engine* plain = pf_engine_open(gp.c_str(), &st);
  CHECK(plain && !pf_engine_uses_ch(plain));
  pf::MappedGraph mg; std::string err; CHECK(mg.open(gp, err));
  CHECK(build_to_file(mg, cp));
  pf_engine* fast = pf_engine_open(gp.c_str(), &st);
  CHECK(fast && pf_engine_uses_ch(fast));
  int same = 0, total = 0;
  std::uniform_int_distribution<uint32_t> pick(0, mg.num_nodes() - 1);
  for (int i = 0; i < 200; ++i) {
    const pf::Node& a = mg.node(pick(rng)); const pf::Node& b = mg.node(pick(rng));
    pf_route_handle *h1 = nullptr, *h2 = nullptr;
    int s1 = pf_route_compute(plain, a.lat_e7 * 1e-7, a.lon_e7 * 1e-7, b.lat_e7 * 1e-7, b.lon_e7 * 1e-7, &h1);
    int s2 = pf_route_compute(fast, a.lat_e7 * 1e-7, a.lon_e7 * 1e-7, b.lat_e7 * 1e-7, b.lon_e7 * 1e-7, &h2);
    ++total;
    if (s1 == s2 && (s1 != PF_OK || std::fabs(pf_route_handle_time_s(h1) - pf_route_handle_time_s(h2)) < 1e-9)) ++same;
    pf_route_handle_free(h1); pf_route_handle_free(h2);
  }
  CHECK(same == total);
  std::printf("[CH C API] with and without CH give the same time/status on %d/%d routes\n", same, total);
  // a broken .ch file must be ignored (A* fallback), not crash or fail the open
  { std::vector<char> junk(500, 'x'); write_all(cp, junk); }
  pf_engine* fb = pf_engine_open(gp.c_str(), &st);
  CHECK(fb && st == PF_OK && !pf_engine_uses_ch(fb));
  CHECK(pf_engine_uses_ch(nullptr) == 0);
  pf_engine_close(plain); pf_engine_close(fast); pf_engine_close(fb);
  unlink(gp.c_str()); unlink(cp.c_str());
}

int main() {
  test_exactness();
  test_bad_files();
  test_c_api_uses_ch();
  std::printf("%d checks, %d failed\n", g_checks, g_fail);
  return g_fail ? 1 : 0;
}
