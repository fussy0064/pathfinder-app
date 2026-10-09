#include "engine.hpp"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <limits>
#include <queue>

namespace pf {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr double kMetersPerDeg = 111320.0;
constexpr uint32_t kNone = 0xFFFFFFFFu;
constexpr int64_t kMaxCells = 1 << 20;
}  // namespace

double haversine_m(double lat1, double lon1, double lat2, double lon2) {
  const double d2r = kPi / 180.0;
  double dlat = (lat2 - lat1) * d2r, dlon = (lon2 - lon1) * d2r;
  double a = std::sin(dlat / 2) * std::sin(dlat / 2) +
             std::cos(lat1 * d2r) * std::cos(lat2 * d2r) * std::sin(dlon / 2) * std::sin(dlon / 2);
  return 2 * 6371000.0 * std::asin(std::min(1.0, std::sqrt(a)));
}

double snap_dist_m(int32_t lat1, int32_t lon1, int32_t lat2, int32_t lon2) {
  double c = std::cos(lat1 * 1e-7 * kPi / 180.0);
  double dy = (double(lat2) - lat1) * 1e-7 * kMetersPerDeg;
  double dx = (double(lon2) - lon1) * 1e-7 * kMetersPerDeg * c;
  return std::sqrt(dx * dx + dy * dy);
}

// ---------------- file mapping ----------------
void* map_file(const std::string& path, size_t& size, std::string& err) {
#if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
  err = "big-endian CPU not supported";
  return nullptr;
#endif
  int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) { err = "cannot open file"; return nullptr; }
  struct stat st;
  if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) {
    ::close(fd); err = "not a regular file"; return nullptr;
  }
  size = static_cast<size_t>(st.st_size);
  void* p = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
  ::close(fd);
  if (p == MAP_FAILED) { err = "mmap failed"; return nullptr; }
  return p;
}

void unmap_file(void* data, size_t size) {
  if (data) munmap(data, size);
}

// ---------------- MappedGraph ----------------
void MappedGraph::unmap() {
  unmap_file(data_, size_);
  data_ = nullptr; size_ = 0; hdr_ = nullptr; nodes_ = nullptr; edges_ = nullptr;
}

bool MappedGraph::open(const std::string& path, std::string& err) {
  unmap();
  size_t size = 0;
  void* p = map_file(path, size, err);
  if (!p) return false;
  if (!validate(p, size, err)) { unmap_file(p, size); return false; }
  data_ = p; size_ = size;
  hdr_ = static_cast<const Header*>(p);
  nodes_ = reinterpret_cast<const Node*>(hdr_ + 1);
  edges_ = reinterpret_cast<const Edge*>(nodes_ + hdr_->num_nodes + 1);
  return true;
}

// ---------------- SpatialIndex ----------------
void SpatialIndex::build(const MappedGraph& g) {
  g_ = &g;
  const uint32_t n = g.num_nodes();
  int32_t min_lat = g.node(0).lat_e7, max_lat = min_lat;
  int32_t min_lon = g.node(0).lon_e7, max_lon = min_lon;
  for (uint32_t i = 1; i < n; ++i) {  // bbox from real nodes, not from the header
    const Node& nd = g.node(i);
    min_lat = std::min(min_lat, nd.lat_e7); max_lat = std::max(max_lat, nd.lat_e7);
    min_lon = std::min(min_lon, nd.lon_e7); max_lon = std::max(max_lon, nd.lon_e7);
  }
  min_lat_ = min_lat; min_lon_ = min_lon;
  max_abs_lat_deg_ = std::max(std::abs(min_lat), std::abs(max_lat)) * 1e-7;
  const int64_t span_lat = int64_t(max_lat) - min_lat, span_lon = int64_t(max_lon) - min_lon;
  cell_ = 50000;
  for (;;) {
    nx_ = span_lon / cell_ + 1; ny_ = span_lat / cell_ + 1;
    if (nx_ * ny_ <= kMaxCells) break;
    cell_ *= 2;
  }
  auto cell_of = [&](const Node& nd) {
    int64_t x = std::min<int64_t>(nx_ - 1, (int64_t(nd.lon_e7) - min_lon_) / cell_);
    int64_t y = std::min<int64_t>(ny_ - 1, (int64_t(nd.lat_e7) - min_lat_) / cell_);
    return static_cast<size_t>(y * nx_ + x);
  };
  start_.assign(static_cast<size_t>(nx_ * ny_) + 1, 0);
  for (uint32_t i = 0; i < n; ++i) ++start_[cell_of(g.node(i)) + 1];
  for (size_t c = 0; c + 1 < start_.size(); ++c) start_[c + 1] += start_[c];
  items_.assign(n, 0);
  std::vector<uint32_t> pos(start_.begin(), start_.end() - 1);
  for (uint32_t i = 0; i < n; ++i) items_[pos[cell_of(g.node(i))]++] = i;
}

bool SpatialIndex::nearest(int32_t lat, int32_t lon, double max_m, uint32_t& node, double& dist_m) const {
  if (!g_ || items_.empty()) return false;
  auto clampi = [](int64_t v, int64_t lo, int64_t hi) { return v < lo ? lo : (v > hi ? hi : v); };
  const int64_t cx = clampi((int64_t(lon) - min_lon_) / cell_, 0, nx_ - 1);
  const int64_t cy = clampi((int64_t(lat) - min_lat_) / cell_, 0, ny_ - 1);
  const double worst_lat = std::max(max_abs_lat_deg_, std::abs(lat) * 1e-7);
  const double cos_min = std::max(0.01, std::cos(worst_lat * kPi / 180.0));
  const double cell_m = std::max(1.0, cell_ * 1e-7 * kMetersPerDeg * cos_min);  // safe lower bound
  const int64_t max_r = std::min<int64_t>(std::max(nx_, ny_), static_cast<int64_t>(max_m / cell_m) + 1);

  double best = std::numeric_limits<double>::infinity();
  uint32_t best_node = kNone;
  auto visit = [&](int64_t x, int64_t y) {
    size_t c = static_cast<size_t>(y * nx_ + x);
    for (uint32_t k = start_[c]; k < start_[c + 1]; ++k) {
      const Node& nd = g_->node(items_[k]);
      double d = snap_dist_m(lat, lon, nd.lat_e7, nd.lon_e7);
      if (d < best) { best = d; best_node = items_[k]; }
    }
  };
  for (int64_t r = 0; r <= max_r; ++r) {
    for (int64_t dy = -r; dy <= r; ++dy) {
      int64_t y = cy + dy;
      if (y < 0 || y >= ny_) continue;
      if (std::abs(dy) == r) {
        for (int64_t x = std::max<int64_t>(0, cx - r); x <= std::min(nx_ - 1, cx + r); ++x) visit(x, y);
      } else {
        if (cx - r >= 0) visit(cx - r, y);
        if (r > 0 && cx + r < nx_) visit(cx + r, y);
      }
    }
    if (best <= r * cell_m) break;  // nothing unexplored can be closer
  }
  if (best_node == kNone || best > max_m) return false;
  node = best_node; dist_m = best;
  return true;
}

// ---------------- Router (A* / Dijkstra) ----------------
Router::Router(const MappedGraph& g) : g_(g) {}

PathResult Router::find(uint32_t s, uint32_t t, bool astar) {
  PathResult res;
  const uint32_t n = g_.num_nodes();
  if (s >= n || t >= n) return res;
  if (cost_.empty()) {  // allocate on first use (not needed when CH answers the queries)
    cost_.assign(n, 0); stamp_.assign(n, 0); parent_node_.assign(n, 0); parent_edge_.assign(n, 0);
  }
  if (astar && !heuristic_ready_) {
    // Tightest valid guess: the slowest time-per-meter found on any edge of THIS graph.
    double c = 1e9;
    for (uint32_t u = 0; u < n; ++u) {
      const Node& a = g_.node(u);
      for (const Edge* e = g_.edge_begin(u); e != g_.edge_end(u); ++e) {
        const Node& b = g_.node(e->target);
        double d = haversine_m(a.lat_e7 * 1e-7, a.lon_e7 * 1e-7, b.lat_e7 * 1e-7, b.lon_e7 * 1e-7);
        if (d > 0.5) c = std::min(c, e->time_ds / d);
      }
    }
    min_ds_per_m_ = c > 1e8 ? 0.0 : c;
    heuristic_ready_ = true;
  }
  if (++gen_ == 0) { std::fill(stamp_.begin(), stamp_.end(), 0); gen_ = 1; }

  const double tlat = g_.node(t).lat_e7 * 1e-7, tlon = g_.node(t).lon_e7 * 1e-7;
  // Heuristic = straight distance * (slowest time per meter in this graph). Never too high, so A*
  // stays exact; floor() keeps it consistent.
  auto h = [&](uint32_t v) -> uint64_t {
    if (!astar) return 0;
    const Node& nv = g_.node(v);
    double d = haversine_m(nv.lat_e7 * 1e-7, nv.lon_e7 * 1e-7, tlat, tlon);
    return static_cast<uint64_t>(d * min_ds_per_m_);
  };

  struct QE {
    uint64_t f; uint32_t g, v;
    bool operator>(const QE& o) const { return f > o.f; }
  };
  std::priority_queue<QE, std::vector<QE>, std::greater<QE>> pq;
  stamp_[s] = gen_; cost_[s] = 0; parent_node_[s] = kNone; parent_edge_[s] = kNone;
  pq.push({h(s), 0, s});

  while (!pq.empty()) {
    QE cur = pq.top(); pq.pop();
    if (cur.g > cost_[cur.v]) continue;  // outdated queue entry
    ++res.expanded;
    if (cur.v == t) break;
    for (const Edge* e = g_.edge_begin(cur.v); e != g_.edge_end(cur.v); ++e) {
      uint64_t ng = uint64_t(cur.g) + e->time_ds;
      if (ng > 0xFFFFFFFFull) continue;  // overflow guard
      uint32_t v = e->target;
      if (stamp_[v] != gen_ || ng < cost_[v]) {
        stamp_[v] = gen_; cost_[v] = static_cast<uint32_t>(ng);
        parent_node_[v] = cur.v;
        parent_edge_[v] = static_cast<uint32_t>(e - g_.edges());
        pq.push({ng + h(v), static_cast<uint32_t>(ng), v});
      }
    }
  }
  if (stamp_[t] != gen_) return res;

  res.found = true;
  res.cost_ds = cost_[t];
  uint32_t v = t;
  for (uint32_t guard = 0; v != kNone && guard <= n; ++guard) {
    res.nodes.push_back(v);
    if (parent_edge_[v] != kNone) res.dist_m += g_.edges()[parent_edge_[v]].dist_m;
    v = parent_node_[v];
  }
  std::reverse(res.nodes.begin(), res.nodes.end());
  return res;
}

}  // namespace pf

namespace pf {

// ---------------- ChGraph ----------------
bool ChGraph::open(const std::string& path, const MappedGraph& g, std::string& err) {
  unmap_file(data_, size_);
  data_ = nullptr; size_ = 0;
  size_t size = 0;
  void* p = map_file(path, size, err);
  if (!p) return false;
  const uint64_t hash = graph_fingerprint(&g.node(0), g.num_nodes(), g.num_edges());
  if (!validate_ch(p, size, g.num_nodes(), hash, err)) { unmap_file(p, size); return false; }
  data_ = p; size_ = size;
  view_ = ch_view(p);
  return true;
}

// ---------------- ChRouter ----------------
// Finds the edge a->mid (stored in down[mid]) and mid->b (stored in up[mid]), then expands both.
bool ChRouter::unpack(uint32_t a, uint32_t b, uint32_t mid, std::vector<uint32_t>& out) const {
  const ChView& v = ch_.view();
  struct Frame { uint32_t a, b, mid; };
  std::vector<Frame> stack;
  stack.push_back({a, b, mid});
  const size_t limit = size_t(v.h->num_nodes) * 2 + 16;  // a valid file can never need more
  size_t steps = 0;
  while (!stack.empty()) {
    Frame f = stack.back(); stack.pop_back();
    if (++steps > limit) return false;
    if (f.mid == kNoMid) { out.push_back(f.b); continue; }
    const ChEdge* e1 = nullptr;  // a -> mid
    for (uint32_t k = v.down_first[f.mid]; k < v.down_first[f.mid + 1]; ++k)
      if (v.down[k].target == f.a && (!e1 || v.down[k].time_ds < e1->time_ds)) e1 = &v.down[k];
    const ChEdge* e2 = nullptr;  // mid -> b
    for (uint32_t k = v.up_first[f.mid]; k < v.up_first[f.mid + 1]; ++k)
      if (v.up[k].target == f.b && (!e2 || v.up[k].time_ds < e2->time_ds)) e2 = &v.up[k];
    if (!e1 || !e2) return false;
    stack.push_back({f.mid, f.b, e2->middle});  // second half is handled last
    stack.push_back({f.a, f.mid, e1->middle});
  }
  return true;
}

PathResult ChRouter::find(uint32_t s, uint32_t t) {
  PathResult res;
  const ChView& v = ch_.view();
  const uint32_t n = v.h->num_nodes;
  if (s >= n || t >= n) return res;
  if (s == t) { res.found = true; res.nodes.push_back(s); return res; }
  if (f_.dist.empty()) {
    for (Side* sd : {&f_, &b_}) {
      sd->dist.assign(n, 0); sd->stamp.assign(n, 0); sd->parent.assign(n, 0); sd->parent_edge.assign(n, 0);
    }
  }
  if (++gen_ == 0) { std::fill(f_.stamp.begin(), f_.stamp.end(), 0); std::fill(b_.stamp.begin(), b_.stamp.end(), 0); gen_ = 1; }

  using QE = std::pair<uint32_t, uint32_t>;  // (dist, node)
  std::priority_queue<QE, std::vector<QE>, std::greater<QE>> pqf, pqb;
  f_.stamp[s] = gen_; f_.dist[s] = 0; f_.parent[s] = kNoMid; pqf.push({0, s});
  b_.stamp[t] = gen_; b_.dist[t] = 0; b_.parent[t] = kNoMid; pqb.push({0, t});
  uint64_t best = ~0ull;
  uint32_t meet = kNoMid;
  bool forward_turn = true;

  auto step = [&](Side& me, Side& other, std::priority_queue<QE, std::vector<QE>, std::greater<QE>>& pq,
                  const uint32_t* first, const ChEdge* edges) {
    QE top = pq.top(); pq.pop();
    uint32_t d = top.first, u = top.second;
    if (d > me.dist[u]) return;  // stale
    ++res.expanded;
    if (other.stamp[u] == gen_ && uint64_t(d) + other.dist[u] < best) { best = uint64_t(d) + other.dist[u]; meet = u; }
    for (uint32_t k = first[u]; k < first[u + 1]; ++k) {
      uint64_t nd = uint64_t(d) + edges[k].time_ds;
      if (nd > 0xFFFFFFFFull) continue;
      uint32_t w = edges[k].target;
      if (me.stamp[w] != gen_ || nd < me.dist[w]) {
        me.stamp[w] = gen_; me.dist[w] = static_cast<uint32_t>(nd);
        me.parent[w] = u; me.parent_edge[w] = k;
        pq.push({static_cast<uint32_t>(nd), w});
      }
    }
  };
  for (;;) {
    bool f_ok = !pqf.empty() && pqf.top().first < best;
    bool b_ok = !pqb.empty() && pqb.top().first < best;
    if (!f_ok && !b_ok) break;
    if (f_ok && (!b_ok || forward_turn)) step(f_, b_, pqf, v.up_first, v.up);
    else step(b_, f_, pqb, v.down_first, v.down);
    forward_turn = !forward_turn;
  }
  if (meet == kNoMid) return res;

  // forward half: s ... meet (edges are in up[])
  std::vector<std::pair<uint32_t, const ChEdge*>> fwd;  // (from, edge) from meet back to s
  for (uint32_t x = meet; f_.parent[x] != kNoMid; x = f_.parent[x]) fwd.push_back({f_.parent[x], &v.up[f_.parent_edge[x]]});
  res.nodes.push_back(s);
  for (size_t i = fwd.size(); i-- > 0;) {
    const ChEdge& e = *fwd[i].second;
    res.dist_m += e.dist_m;
    if (!unpack(fwd[i].first, e.target, e.middle, res.nodes)) { res.nodes.clear(); res.dist_m = 0; return res; }
  }
  // backward half: meet ... t (real edge x -> next is stored in down[next] with target x)
  for (uint32_t x = meet; b_.parent[x] != kNoMid; x = b_.parent[x]) {
    uint32_t next = b_.parent[x];
    const ChEdge& e = v.down[b_.parent_edge[x]];
    res.dist_m += e.dist_m;
    if (!unpack(x, next, e.middle, res.nodes)) { res.nodes.clear(); res.dist_m = 0; return res; }
  }
  res.found = true;
  res.cost_ds = best;
  return res;
}

}  // namespace pf
