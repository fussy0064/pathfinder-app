// route_cli: try the engine from the command line
// Usage: route_cli <map_graph.bin> <start_lat> <start_lon> <target_lat> <target_lon>
#include <cstdio>
#include <cstdlib>

#include "pathfinder.h"

int main(int argc, char** argv) {
  if (argc != 6) { std::fprintf(stderr, "Usage: route_cli <graph.bin> slat slon tlat tlon\n"); return 1; }
  int st = 0;
  pf_engine* e = pf_engine_open(argv[1], &st);
  if (!e) { std::fprintf(stderr, "open failed: %s\n", pf_status_message(st)); return 2; }
  static const char* kNames[] = {"depart", "straight", "slight left", "left", "sharp left",
                                 "slight right", "right", "sharp right", "u-turn", "arrive"};
  pf_route_handle* h = nullptr;
  st = pf_route_compute(e, std::atof(argv[2]), std::atof(argv[3]), std::atof(argv[4]), std::atof(argv[5]), &h);
  if (st != PF_OK) {
    std::printf("status: %d (%s)\n", st, pf_status_message(st));
  } else {
    uint32_t n = pf_route_handle_point_count(h), s = pf_route_handle_step_count(h);
    std::printf("status: 0 (ok)\ndistance: %.0f m\ntime: %.1f s\npoints: %u\n", pf_route_handle_distance_m(h),
                pf_route_handle_time_s(h), n);
    for (uint32_t i = 0; i < n; ++i) std::printf("  %.6f, %.6f\n", pf_route_handle_lats(h)[i], pf_route_handle_lons(h)[i]);
    std::printf("steps: %u\n", s);
    for (uint32_t i = 0; i < s; ++i)
      std::printf("  %-12s at point %u, %.0f m to next\n", kNames[pf_route_handle_step_types(h)[i]],
                  pf_route_handle_step_points(h)[i], pf_route_handle_step_dists(h)[i]);
  }
  pf_route_handle_free(h);
  pf_engine_close(e);
  return st == PF_OK ? 0 : 3;
}
