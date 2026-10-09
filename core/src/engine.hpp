#pragma once
#include <cstdint>
#include <string>
#include <vector>

#include "graph_format.hpp"

namespace pf {

double haversine_m(double lat1, double lon1, double lat2, double lon2);
// Fast flat-earth distance for short snapping distances. (lat1, lon1) is the query point.
double snap_dist_m(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7);

// Read-only memory-mapped graph. open() validates the whole file first.
class MappedGraph {
 public:
  MappedGraph() = default;
  ~MappedGraph() { unmap(); }
  MappedGraph(const MappedGraph&) = delete;
  MappedGraph& operator=(const MappedGraph&) = delete;

  bool open(const std::string& path, std::string& err);
  uint32_t num_nodes() const { return hdr_ ? hdr_->num_nodes : 0; }
  uint32_t num_edges() const { return hdr_ ? hdr_->num_edges : 0; }
  const Node& node(uint32_t i) const { return nodes_[i]; }
  const Edge* edge_begin(uint32_t i) const { return edges_ + nodes_[i].first_edge; }
  const Edge* edge_end(uint32_t i) const { return edges_ + nodes_[i + 1].first_edge; }
  const Edge* edges() const { return edges_; }

 private:
  void unmap();
  void* data_ = nullptr;
  size_t size_ = 0;
  const Header* hdr_ = nullptr;
  const Node* nodes_ = nullptr;
  const Edge* edges_ = nullptr;
};

// Uniform grid (CSR layout). Maps a GPS point to the nearest graph node.
class SpatialIndex {
 public:
  void build(const MappedGraph& g);
  bool nearest(int32_t lat_e7, int32_t lon_e7, double max_m, uint32_t& node, double& dist_m) const;

 private:
  const MappedGraph* g_ = nullptr;
  int32_t min_lat_ = 0, min_lon_ = 0;
  int64_t cell_ = 50000;  // cell size in 1e-7 degrees (0.005 deg ~ 555 m)
  int64_t nx_ = 0, ny_ = 0;
  double max_abs_lat_deg_ = 0;
  std::vector<uint32_t> start_, items_;
};

struct PathResult {
  bool found = false;
  uint64_t cost_ds = 0;   // travel time, deciseconds
  uint64_t dist_m = 0;
  uint32_t expanded = 0;  // nodes taken from the queue (for comparing A* and Dijkstra)
  std::vector<uint32_t> nodes;
};

// Single-threaded query object. Reuses its arrays between queries (generation stamps).
class Router {
 public:
  explicit Router(const MappedGraph& g);
  PathResult find(uint32_t source, uint32_t target, bool astar);

 private:
  const MappedGraph& g_;
  std::vector<uint32_t> cost_, stamp_, parent_node_, parent_edge_;
  uint32_t gen_ = 0;
};

}  // namespace pf
