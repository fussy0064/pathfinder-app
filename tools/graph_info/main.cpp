// graph_info: validate and print stats of a map_graph.bin (uses mmap, like the Phase 2 engine)
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <iostream>
#include <map>
#include <string>

#include "graph_format.hpp"

int main(int argc, char** argv) {
  if (argc != 2) { std::cerr << "Usage: graph_info <map_graph.bin>\n"; return 1; }
  int fd = open(argv[1], O_RDONLY);
  if (fd < 0) { std::cerr << "Cannot open file\n"; return 2; }
  struct stat st;
  if (fstat(fd, &st) != 0 || st.st_size <= 0) { close(fd); std::cerr << "Bad file\n"; return 2; }
  size_t size = static_cast<size_t>(st.st_size);
  void* data = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
  close(fd);
  if (data == MAP_FAILED) { std::cerr << "mmap failed\n"; return 2; }

  std::string err;
  if (!pf::validate(data, size, err)) {
    std::cerr << "INVALID: " << err << "\n";
    munmap(data, size);
    return 3;
  }
  const pf::Header* h = static_cast<const pf::Header*>(data);
  const pf::Node* nodes = reinterpret_cast<const pf::Node*>(h + 1);
  const pf::Edge* edges = reinterpret_cast<const pf::Edge*>(nodes + h->num_nodes + 1);

  std::map<int, unsigned> by_type;
  unsigned long long total_m = 0;
  for (uint32_t i = 0; i < h->num_edges; ++i) { ++by_type[edges[i].road_type]; total_m += edges[i].dist_m; }

  std::cout << "VALID graph\nVersion : " << h->version << "\nNodes   : " << h->num_nodes
            << "\nEdges   : " << h->num_edges << "\nSize    : " << size << " bytes"
            << "\nBBox    : lat " << h->min_lat_e7 / 1e7 << " .. " << h->max_lat_e7 / 1e7
            << ", lon " << h->min_lon_e7 / 1e7 << " .. " << h->max_lon_e7 / 1e7
            << "\nTotal edge length: " << total_m / 1000.0 << " km\nEdges by road_type:\n";
  for (auto& kv : by_type) std::cout << "  type " << kv.first << ": " << kv.second << "\n";
  std::cout << "Node 0 -> " << (nodes[1].first_edge - nodes[0].first_edge) << " outgoing edge(s)\n";
  munmap(data, size);
  return 0;
}
