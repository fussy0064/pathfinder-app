// Binary graph format "PFGR" v1 (little-endian). Shared by tools and the Phase 2 engine.
// Layout: [Header 32B][Node x (num_nodes+1) 12B][Edge x num_edges 16B]
// Forward-star: edges of node i are Edge[Node[i].first_edge .. Node[i+1].first_edge).
#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

namespace pf {

constexpr char     kMagic[4] = {'P', 'F', 'G', 'R'};
constexpr uint32_t kVersion  = 1;

enum RoadType : uint8_t {
  RT_MOTORWAY = 1, RT_TRUNK, RT_PRIMARY, RT_SECONDARY, RT_TERTIARY,
  RT_UNCLASSIFIED, RT_RESIDENTIAL, RT_SERVICE, RT_LIVING_STREET
};

struct Header {
  char     magic[4];
  uint32_t version;
  uint32_t num_nodes;
  uint32_t num_edges;
  int32_t  min_lat_e7, min_lon_e7, max_lat_e7, max_lon_e7;
};
struct Node {
  int32_t  lat_e7;
  int32_t  lon_e7;
  uint32_t first_edge;
};
struct Edge {
  uint32_t target;
  uint32_t dist_m;
  uint32_t time_ds;   // travel time in deciseconds
  uint8_t  road_type;
  uint8_t  pad[3];
};
static_assert(sizeof(Header) == 32, "Header size");
static_assert(sizeof(Node) == 12, "Node size");
static_assert(sizeof(Edge) == 16, "Edge size");

inline uint64_t expected_size(uint32_t n, uint32_t m) {
  return sizeof(Header) + (uint64_t)(n + 1) * sizeof(Node) + (uint64_t)m * sizeof(Edge);
}

// Security: never trust a file. Check every size and index before use.
inline bool validate(const void* data, size_t size, std::string& err) {
  if (!data || size < sizeof(Header)) { err = "file too small"; return false; }
  Header h;
  std::memcpy(&h, data, sizeof(h));
  if (std::memcmp(h.magic, kMagic, 4) != 0) { err = "bad magic"; return false; }
  if (h.version != kVersion) { err = "unsupported version"; return false; }
  if (h.num_nodes == 0 || h.num_nodes > 0x7FFFFFFFu || h.num_edges > 0x7FFFFFFFu) {
    err = "bad counts"; return false;
  }
  if (expected_size(h.num_nodes, h.num_edges) != size) { err = "size mismatch"; return false; }
  const Node* nodes = reinterpret_cast<const Node*>(static_cast<const char*>(data) + sizeof(Header));
  const Edge* edges = reinterpret_cast<const Edge*>(nodes + h.num_nodes + 1);
  if (nodes[0].first_edge != 0 || nodes[h.num_nodes].first_edge != h.num_edges) {
    err = "bad edge index bounds"; return false;
  }
  for (uint32_t i = 0; i < h.num_nodes; ++i)
    if (nodes[i].first_edge > nodes[i + 1].first_edge) { err = "edge index not sorted"; return false; }
  for (uint32_t e = 0; e < h.num_edges; ++e)
    if (edges[e].target >= h.num_nodes) { err = "edge target out of range"; return false; }
  return true;
}

}  // namespace pf
