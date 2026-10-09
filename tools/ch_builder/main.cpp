// ch_builder: map_graph.bin -> map_graph.bin.ch (Contraction Hierarchies index, desktop only)
// Usage: ch_builder <map_graph.bin> [output.ch]     (default output: <input>.ch)
#include <cstdio>
#include <fstream>
#include <iostream>

#include "ch_build.hpp"

int main(int argc, char** argv) {
  if (argc < 2 || argc > 3) { std::cerr << "Usage: ch_builder <map_graph.bin> [output.ch]\n"; return 1; }
  const std::string in = argv[1], out = argc == 3 ? argv[2] : in + ".ch";
  pf::MappedGraph g; std::string err;
  if (!g.open(in, err)) { std::cerr << "Cannot load graph: " << err << "\n"; return 2; }
  std::vector<char> img; pf::ChBuildStats st;
  std::cerr << "Building hierarchy for " << g.num_nodes() << " nodes...\n";
  if (!pf::build_ch(g, img, err, &st, true)) { std::cerr << "Build failed: " << err << "\n"; return 3; }
  const std::string tmp = out + ".tmp";  // write to temp, then rename (no half-written output)
  { std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
    f.write(img.data(), (std::streamsize)img.size());
    if (!f) { std::cerr << "Write failed\n"; return 4; } }
  if (std::rename(tmp.c_str(), out.c_str()) != 0) { std::cerr << "Rename failed\n"; return 4; }
  std::printf("CH written: %s (%.1f MB), %u shortcuts, %.1f s\n", out.c_str(), img.size() / 1048576.0, st.shortcuts, st.seconds);
  return 0;
}
