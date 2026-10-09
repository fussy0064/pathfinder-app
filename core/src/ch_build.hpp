#pragma once
#include <string>
#include <vector>

#include "engine.hpp"

namespace pf {

struct ChBuildStats {
  uint32_t shortcuts = 0;
  double seconds = 0;
};

// Desktop/tool only (not part of the mobile library). Fills `out` with a PFCH file image.
bool build_ch(const MappedGraph& g, std::vector<char>& out, std::string& err, ChBuildStats* stats = nullptr,
              bool verbose = false);

}  // namespace pf
