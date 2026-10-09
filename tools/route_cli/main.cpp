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
  pf_route r;
  st = pf_route_find(e, std::atof(argv[2]), std::atof(argv[3]), std::atof(argv[4]), std::atof(argv[5]), &r);
  if (st != PF_OK) {
    std::printf("status: %d (%s)\n", st, pf_status_message(st));
  } else {
    std::printf("status: 0 (ok)\ndistance: %.0f m\ntime: %.1f s\npoints: %u\n", r.distance_m, r.time_s, r.num_points);
    for (uint32_t i = 0; i < r.num_points; ++i) std::printf("  %.6f, %.6f\n", r.lat[i], r.lon[i]);
  }
  pf_route_free(&r);
  pf_engine_close(e);
  return st == PF_OK ? 0 : 3;
}
