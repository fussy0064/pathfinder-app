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

#ifdef __cplusplus
}
#endif
#endif
