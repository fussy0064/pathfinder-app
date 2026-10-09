#include "ch_build.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <queue>

#include "ch_format.hpp"

namespace pf {
namespace {

struct Arc { uint32_t from, to, time, dist, mid; };
constexpr uint64_t kInf = ~0ull;

class Builder {
 public:
  explicit Builder(const MappedGraph& g) : n_(g.num_nodes()), outs_(n_), ins_(n_), done_(n_, 0), cn_(n_, 0),
        wd_(n_, 0), wstamp_(n_, 0) {
    for (uint32_t u = 0; u < n_; ++u)
      for (const Edge* e = g.edge_begin(u); e != g.edge_end(u); ++e)
        if (e->target != u) add_or_update(u, e->target, e->time_ds, e->dist_m, kNoMid);
  }

  std::vector<uint32_t> run(bool verbose) {
    rank_.assign(n_, 0);
    using QE = std::pair<int64_t, uint32_t>;
    std::priority_queue<QE, std::vector<QE>, std::greater<QE>> pq;
    for (uint32_t v = 0; v < n_; ++v) pq.push({priority(v), v});
    uint32_t r = 0;
    while (!pq.empty()) {
      QE top = pq.top(); pq.pop();
      uint32_t v = top.second;
      if (done_[v]) continue;
      int64_t p = priority(v);
      if (!pq.empty() && p > pq.top().first) { pq.push({p, v}); continue; }  // lazy update
      std::vector<uint32_t> nbs;
      for (uint32_t ia : ins_[v]) nbs.push_back(arcs_[ia].from);
      for (uint32_t io : outs_[v]) nbs.push_back(arcs_[io].to);
      std::sort(nbs.begin(), nbs.end());
      nbs.erase(std::unique(nbs.begin(), nbs.end()), nbs.end());
      contract(v, true, kApplyLimit);
      done_[v] = 1;
      rank_[v] = r++;
      for (uint32_t nb : nbs) { ++cn_[nb]; pq.push({priority(nb), nb}); }
      if (verbose && r % 100000 == 0) std::fprintf(stderr, "  contracted %u / %u\n", r, n_);
    }
    return rank_;
  }

  const std::vector<Arc>& arcs() const { return arcs_; }

 private:
  static constexpr uint32_t kSimLimit = 40, kApplyLimit = 400;

  int64_t priority(uint32_t v) {
    int sc = contract(v, false, kSimLimit);
    return 2 * (int64_t(sc) - int64_t(ins_[v].size() + outs_[v].size())) + cn_[v];
  }

  int find_arc(uint32_t u, uint32_t w) const {
    for (uint32_t i : outs_[u]) if (arcs_[i].to == w) return static_cast<int>(i);
    return -1;
  }

  void add_or_update(uint32_t u, uint32_t w, uint32_t time, uint32_t dist, uint32_t mid) {
    int idx = find_arc(u, w);
    if (idx >= 0) {
      Arc& a = arcs_[idx];
      if (time < a.time || (time == a.time && dist < a.dist)) { a.time = time; a.dist = dist; a.mid = mid; }
      return;
    }
    arcs_.push_back({u, w, time, dist, mid});
    outs_[u].push_back(static_cast<uint32_t>(arcs_.size() - 1));
    ins_[w].push_back(static_cast<uint32_t>(arcs_.size() - 1));
  }

  static void erase_val(std::vector<uint32_t>& v, uint32_t x) {
    for (size_t i = 0; i < v.size(); ++i) if (v[i] == x) { v[i] = v.back(); v.pop_back(); return; }
  }

  // Dijkstra over uncontracted nodes, never entering `skip`. Settles at most `limit` nodes.
  void witness(uint32_t u, uint32_t skip, uint64_t maxd, uint32_t limit) {
    ++wgen_;
    using QE = std::pair<uint64_t, uint32_t>;
    std::priority_queue<QE, std::vector<QE>, std::greater<QE>> pq;
    wd_[u] = 0; wstamp_[u] = wgen_; pq.push({0, u});
    uint32_t settled = 0;
    while (!pq.empty()) {
      auto [d, x] = pq.top(); pq.pop();
      if (d > wd_[x]) continue;
      if (d > maxd || ++settled > limit) break;
      for (uint32_t ia : outs_[x]) {
        const Arc& a = arcs_[ia];
        if (a.to == skip) continue;
        uint64_t nd = d + a.time;
        if (wstamp_[a.to] != wgen_ || nd < wd_[a.to]) { wd_[a.to] = static_cast<uint32_t>(std::min<uint64_t>(nd, 0xFFFFFFFFu)); wstamp_[a.to] = wgen_; pq.push({nd, a.to}); }
      }
    }
  }
  uint64_t wdist(uint32_t w) const { return wstamp_[w] == wgen_ ? wd_[w] : kInf; }

  // Counts the shortcuts contracting v needs; with apply=true also adds them and removes v.
  int contract(uint32_t v, bool apply, uint32_t limit) {
    int count = 0;
    std::vector<Arc> pending;
    const std::vector<uint32_t> ins = ins_[v], outs = outs_[v];  // copies (adjacency may change)
    for (uint32_t ia : ins) {
      const Arc a = arcs_[ia];
      uint64_t maxd = 0; bool any = false;
      for (uint32_t io : outs) { if (arcs_[io].to == a.from) continue; maxd = std::max<uint64_t>(maxd, uint64_t(a.time) + arcs_[io].time); any = true; }
      if (!any) continue;
      witness(a.from, v, maxd, limit);
      for (uint32_t io : outs) {
        const Arc b = arcs_[io];
        if (b.to == a.from) continue;
        uint64_t via = uint64_t(a.time) + b.time;
        if (wdist(b.to) > via) {
          ++count;
          if (apply) pending.push_back({a.from, b.to, static_cast<uint32_t>(std::min<uint64_t>(via, 0xFFFFFFFFu)), a.dist + b.dist, v});
        }
      }
    }
    if (apply) {
      for (const Arc& s : pending) add_or_update(s.from, s.to, s.time, s.dist, s.mid);
      shortcuts_ += static_cast<uint32_t>(pending.size());
      for (uint32_t ia : ins_[v]) erase_val(outs_[arcs_[ia].from], ia);
      for (uint32_t io : outs_[v]) erase_val(ins_[arcs_[io].to], io);
      ins_[v].clear(); outs_[v].clear();
    }
    return count;
  }

 public:
  uint32_t shortcuts_ = 0;

 private:
  uint32_t n_;
  std::vector<Arc> arcs_;
  std::vector<std::vector<uint32_t>> outs_, ins_;
  std::vector<uint8_t> done_;
  std::vector<uint32_t> cn_, rank_, wd_, wstamp_;
  uint32_t wgen_ = 0;
};

}  // namespace

bool build_ch(const MappedGraph& g, std::vector<char>& out, std::string& err, ChBuildStats* stats, bool verbose) {
  auto t0 = std::chrono::steady_clock::now();
  const uint32_t n = g.num_nodes();
  if (n == 0) { err = "empty graph"; return false; }
  Builder b(g);
  std::vector<uint32_t> rank = b.run(verbose);

  std::vector<uint32_t> up_first(n + 1, 0), down_first(n + 1, 0);
  for (const Arc& a : b.arcs()) {
    if (rank[a.to] > rank[a.from]) ++up_first[a.from + 1]; else ++down_first[a.to + 1];
  }
  for (uint32_t i = 0; i < n; ++i) { up_first[i + 1] += up_first[i]; down_first[i + 1] += down_first[i]; }
  const uint32_t num_up = up_first[n], num_down = down_first[n];
  std::vector<ChEdge> up(num_up), down(num_down);
  std::vector<uint32_t> upos(up_first.begin(), up_first.end() - 1), dpos(down_first.begin(), down_first.end() - 1);
  for (const Arc& a : b.arcs()) {
    if (rank[a.to] > rank[a.from]) up[upos[a.from]++] = {a.to, a.time, a.dist, a.mid};
    else down[dpos[a.to]++] = {a.from, a.time, a.dist, a.mid};
  }

  ChHeader h{};
  std::memcpy(h.magic, kChMagic, 4);
  h.version = kChVersion; h.num_nodes = n; h.num_up = num_up; h.num_down = num_down;
  const Node* nodes = &g.node(0);
  h.graph_hash = graph_fingerprint(nodes, n, g.num_edges());

  out.assign(ch_expected_size(n, num_up, num_down), 0);
  char* p = out.data();
  std::memcpy(p, &h, sizeof h); p += sizeof h;
  std::memcpy(p, rank.data(), 4ull * n); p += 4ull * n;
  std::memcpy(p, up_first.data(), 4ull * (n + 1)); p += 4ull * (n + 1);
  std::memcpy(p, down_first.data(), 4ull * (n + 1)); p += 4ull * (n + 1);
  if (num_up) std::memcpy(p, up.data(), 16ull * num_up);
  p += 16ull * num_up;
  if (num_down) std::memcpy(p, down.data(), 16ull * num_down);

  if (stats) {
    stats->shortcuts = b.shortcuts_;
    stats->seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
  }
  return true;
}

}  // namespace pf
