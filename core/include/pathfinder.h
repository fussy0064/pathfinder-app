/* Pathfinder C API (stable ABI for Dart FFI / JNI).
 * Thread safety: one engine handle may be used from many threads (calls are serialized).
 * Memory: every pf_route filled by pf_route_find must be released with pf_route_free. */
#ifndef PATHFINDER_H
#define PATHFINDER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PF_API __attribute__((visibility("default"), used))

enum {
  PF_OK = 0,
  PF_ERR_BAD_ARGS = 1,
  PF_ERR_NO_START = 2,       /* no road node near the start point */
  PF_ERR_NO_TARGET = 3,      /* no road node near the target point */
  PF_ERR_NO_ROUTE = 4,       /* both points snapped, but no path exists */
  PF_ERR_FILE = 5,           /* cannot open / map the graph file */
  PF_ERR_INVALID_GRAPH = 6,  /* graph file failed validation */
  PF_ERR_INTERNAL = 7
};

typedef struct pf_engine pf_engine;

typedef struct {
  double distance_m;
  double time_s;
  uint32_t num_points;
  double* lat; /* num_points values */
  double* lon; /* num_points values */
} pf_route;

PF_API const char* pf_version(void);
PF_API const char* pf_status_message(int status);

/* Opens map_graph.bin. Returns NULL on failure and sets *status (may be NULL). */
PF_API pf_engine* pf_engine_open(const char* graph_path, int* status);
PF_API void pf_engine_close(pf_engine* engine);
PF_API uint32_t pf_engine_node_count(const pf_engine* engine);
/* 1 if a valid Contraction Hierarchies file "<graph_path>.ch" was found and is used (fast routing). */
PF_API int pf_engine_uses_ch(const pf_engine* engine);

/* Finds the fastest route. Returns PF_OK or an error code. */
PF_API int pf_route_find(pf_engine* engine, double start_lat, double start_lon,
                         double target_lat, double target_lon, pf_route* out);
PF_API void pf_route_free(pf_route* route);

/* ---- Handle API (used by Dart FFI: no struct layout to match) ----
 * pf_route_compute fills *out with a handle on PF_OK (and NULL otherwise).
 * Accessor pointers stay valid until pf_route_handle_free. */
typedef struct pf_route_handle pf_route_handle;
PF_API int pf_route_compute(pf_engine* engine, double start_lat, double start_lon,
                            double target_lat, double target_lon, pf_route_handle** out);
PF_API double pf_route_handle_distance_m(const pf_route_handle* h);
PF_API double pf_route_handle_time_s(const pf_route_handle* h);
PF_API uint32_t pf_route_handle_point_count(const pf_route_handle* h);
PF_API const double* pf_route_handle_lats(const pf_route_handle* h);
PF_API const double* pf_route_handle_lons(const pf_route_handle* h);
PF_API void pf_route_handle_free(pf_route_handle* h);

/* ---- Turn-by-turn + progress (v0.4.0) ----
 * Steps: step 0 is DEPART, the last step is ARRIVE. step_dists[i] = meters from step i to step i+1.
 * Turn types: see PF_TURN_* below. Turns are found from the route shape only (no street names). */
enum {
  PF_TURN_DEPART = 0, PF_TURN_STRAIGHT, PF_TURN_SLIGHT_LEFT, PF_TURN_LEFT, PF_TURN_SHARP_LEFT,
  PF_TURN_SLIGHT_RIGHT, PF_TURN_RIGHT, PF_TURN_SHARP_RIGHT, PF_TURN_UTURN, PF_TURN_ARRIVE
};
PF_API uint32_t pf_route_handle_step_count(const pf_route_handle* h);
PF_API const int32_t* pf_route_handle_step_types(const pf_route_handle* h);
PF_API const uint32_t* pf_route_handle_step_points(const pf_route_handle* h);
PF_API const double* pf_route_handle_step_dists(const pf_route_handle* h);

/* Match a GPS position to the route. out4 receives 4 doubles:
 * [0] along_m  [1] off_route_m  [2] remaining_m  [3] to_next_step_m.  *next_step = step index.
 * Not thread-safe per handle (one navigation = one thread). */
PF_API int pf_route_handle_progress(pf_route_handle* h, double lat, double lon,
                                    double* out4, int32_t* next_step);

/* After pf_route_handle_progress: voice cue for the next maneuver, returned ONCE (then reset to 0).
 * 0 = nothing to say, 1 = about 500 m ahead, 2 = about 200 m, 3 = now (50 m or less). */
PF_API int pf_route_handle_take_announce(pf_route_handle* h);
/* Direction of the route segment matched by the last progress call (degrees, 0 = north, 90 = east). */
PF_API double pf_route_handle_bearing_deg(const pf_route_handle* h);

#ifdef __cplusplus
}
#endif
#endif
