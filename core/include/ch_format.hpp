// Contraction Hierarchies file "PFCH" v1 (little-endian). Optional companion of map_graph.bin.
// Layout: [ChHeader 32B][rank u32 x n][up_first u32 x (n+1)][down_first u32 x (n+1)]
//         [up ChEdge x num_up][down ChEdge x num_down]
// up[u]   : edges u->v with rank[v] > rank[u]            (forward search)
// down[x] : real edges y->x stored with target y, rank[y] > rank[x]   (backward search)
// middle  : contracted node a shortcut jumps over (kNoMid for original edges); rank[middle] is
//           lower than both ends, so unpacking always ends.
#pragma once
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "graph_format.hpp"

namespace pf {

constexpr char     kChMagic[4] = {'P', 'F', 'C', 'H'};
constexpr uint32_t kChVersion  = 1;
constexpr uint32_t kNoMid      = 0xFFFFFFFFu;

struct ChHeader {
  char     magic[4];
  uint32_t version, num_nodes, num_up, num_down, reserved;
  uint64_t graph_hash;  // fingerprint of the graph this index was built for
};
struct ChEdge {
  uint32_t target, time_ds, dist_m, middle;
};
static_assert(sizeof(ChHeader) == 32, "ChHeader size");
static_assert(sizeof(ChEdge) == 16, "ChEdge size");

inline uint64_t ch_expected_size(uint32_t n, uint32_t up, uint32_t down) {
  return sizeof(ChHeader) + 4ull * n + 8ull * (uint64_t(n) + 1) + 16ull * (uint64_t(up) + down);
}

// FNV-1a 64 over the node array + counts. Ties a CH file to its graph file.
inline uint64_t graph_fingerprint(const Node* nodes, uint32_t n, uint32_t m) {
  uint64_t h = 1469598103934665603ull;
  auto mix = [&](const void* p, size_t len) {
    const unsigned char* b = static_cast<const unsigned char*>(p);
    for (size_t i = 0; i < len; ++i) { h ^= b[i]; h *= 1099511628211ull; }
  };
  mix(&n, sizeof n); mix(&m, sizeof m);
  mix(nodes, (size_t(n) + 1) * sizeof(Node));
  return h;
}

struct ChView {
  const ChHeader* h;
  const uint32_t *rank, *up_first, *down_first;
  const ChEdge *up, *down;
};
inline ChView ch_view(const void* data) {
  ChView v;
  v.h = static_cast<const ChHeader*>(data);
  v.rank = reinterpret_cast<const uint32_t*>(v.h + 1);
  v.up_first = v.rank + v.h->num_nodes;
  v.down_first = v.up_first + v.h->num_nodes + 1;
  v.up = reinterpret_cast<const ChEdge*>(v.down_first + v.h->num_nodes + 1);
  v.down = v.up + v.h->num_up;
  return v;
}

// Never trust a file: check sizes, indexes, rank order and the graph fingerprint.
inline bool validate_ch(const void* data, size_t size, uint32_t n_expected, uint64_t hash_expected,
                        std::string& err) {
  if (!data || size < sizeof(ChHeader)) { err = "ch: file too small"; return false; }
  ChHeader h;
  std::memcpy(&h, data, sizeof h);
  if (std::memcmp(h.magic, kChMagic, 4) != 0) { err = "ch: bad magic"; return false; }
  if (h.version != kChVersion) { err = "ch: unsupported version"; return false; }
  if (h.num_nodes != n_expected || h.num_nodes == 0) { err = "ch: node count differs from graph"; return false; }
  if (h.graph_hash != hash_expected) { err = "ch: built for a different graph"; return false; }
  if (h.num_up > 0x7FFFFFFFu || h.num_down > 0x7FFFFFFFu) { err = "ch: bad counts"; return false; }
  if (ch_expected_size(h.num_nodes, h.num_up, h.num_down) != size) { err = "ch: size mismatch"; return false; }
  const ChView v = ch_view(data);
  const uint32_t n = h.num_nodes;
  std::vector<bool> seen(n, false);
  for (uint32_t i = 0; i < n; ++i) {
    if (v.rank[i] >= n || seen[v.rank[i]]) { err = "ch: rank is not a permutation"; return false; }
    seen[v.rank[i]] = true;
  }
  if (v.up_first[0] != 0 || v.up_first[n] != h.num_up || v.down_first[0] != 0 || v.down_first[n] != h.num_down) {
    err = "ch: bad edge index bounds"; return false;
  }
  for (uint32_t i = 0; i < n; ++i)
    if (v.up_first[i] > v.up_first[i + 1] || v.down_first[i] > v.down_first[i + 1]) {
      err = "ch: edge index not sorted"; return false;
    }
  auto check = [&](const ChEdge& e, uint32_t owner) {
    if (e.target >= n || v.rank[e.target] <= v.rank[owner]) return false;  // target must be higher
    if (e.middle != kNoMid && (e.middle >= n || v.rank[e.middle] >= v.rank[owner] || v.rank[e.middle] >= v.rank[e.target]))
      return false;
    return true;
  };
  for (uint32_t u = 0; u < n; ++u) {
    for (uint32_t k = v.up_first[u]; k < v.up_first[u + 1]; ++k)
      if (!check(v.up[k], u)) { err = "ch: bad up edge"; return false; }
    for (uint32_t k = v.down_first[u]; k < v.down_first[u + 1]; ++k)
      if (!check(v.down[k], u)) { err = "ch: bad down edge"; return false; }
  }
  return true;
}

}  // namespace pf
