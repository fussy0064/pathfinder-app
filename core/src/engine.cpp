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

// ---------------- MappedGraph ----------------
void MappedGraph::unmap() {
  if (data_) munmap(data_, size_);
  data_ = nullptr; size_ = 0; hdr_ = nullptr; nodes_ = nullptr; edges_ = nullptr;
}

bool MappedGraph::open(const std::string& path, std::string& err) {
  unmap();
#if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
  err = "big-endian CPU not supported";
  return false;
#endif
  int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) { err = "cannot open file"; return false; }
  struct stat st;
  if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) {
    ::close(fd); err = "not a regular file"; return false;
  }
  size_t size = static_cast<size_t>(st.st_size);
  void* p = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
  ::close(fd);
  if (p == MAP_FAILED) { err = "mmap failed"; return false; }
  if (!validate(p, size, err)) { munmap(p, size); return false; }
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
Router::Router(const MappedGraph& g)
    : g_(g), cost_(g.num_nodes()), stamp_(g.num_nodes(), 0),
      parent_node_(g.num_nodes()), parent_edge_(g.num_nodes()) {}

PathResult Router::find(uint32_t s, uint32_t t, bool astar) {
  PathResult res;
  const uint32_t n = g_.num_nodes();
  if (s >= n || t >= n) return res;
  if (++gen_ == 0) { std::fill(stamp_.begin(), stamp_.end(), 0); gen_ = 1; }

  const double tlat = g_.node(t).lat_e7 * 1e-7, tlon = g_.node(t).lon_e7 * 1e-7;
  // Heuristic: straight distance at 140 km/h (max speed) * 0.65. The 0.65 covers rounding of
  // stored times, so the heuristic never overestimates and A* stays exact.
  auto h = [&](uint32_t v) -> uint64_t {
    if (!astar) return 0;
    const Node& nv = g_.node(v);
    double d = haversine_m(nv.lat_e7 * 1e-7, nv.lon_e7 * 1e-7, tlat, tlon);
    return static_cast<uint64_t>(d * 36.0 / 140.0 * 0.65);
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
