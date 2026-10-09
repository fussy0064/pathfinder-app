#include <cmath>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <vector>

#include "engine.hpp"
#include "pathfinder.h"

struct pf_engine {
  pf::MappedGraph graph;
  pf::SpatialIndex index;
  std::unique_ptr<pf::Router> router;
  std::mutex mu;
};

struct pf_route_handle {
  double distance_m = 0, time_s = 0;
  std::vector<double> lat, lon;
};

namespace {
constexpr double kMaxSnapM = 5000.0;  // GPS point must be within 5 km of a road node

bool valid_coord(double lat, double lon) {
  return std::isfinite(lat) && std::isfinite(lon) && lat >= -90.0 && lat <= 90.0 &&
         lon >= -180.0 && lon <= 180.0;
}
int32_t to_e7(double deg) { return static_cast<int32_t>(std::llround(deg * 1e7)); }
void set_status(int* s, int v) { if (s) *s = v; }

// Shared by both route APIs: check input, snap both points, run A*. May throw (callers catch).
int run_query(pf_engine* e, double slat, double slon, double tlat, double tlon, pf::PathResult& r) {
  if (!e || !valid_coord(slat, slon) || !valid_coord(tlat, tlon)) return PF_ERR_BAD_ARGS;
  std::lock_guard<std::mutex> lock(e->mu);
  uint32_t s = 0, t = 0;
  double d = 0;
  if (!e->index.nearest(to_e7(slat), to_e7(slon), kMaxSnapM, s, d)) return PF_ERR_NO_START;
  if (!e->index.nearest(to_e7(tlat), to_e7(tlon), kMaxSnapM, t, d)) return PF_ERR_NO_TARGET;
  r = e->router->find(s, t, true);
  return r.found ? PF_OK : PF_ERR_NO_ROUTE;
}
}  // namespace

extern "C" {

PF_API const char* pf_version(void) { return "0.3.0"; }

PF_API const char* pf_status_message(int status) {
  switch (status) {
    case PF_OK: return "ok";
    case PF_ERR_BAD_ARGS: return "bad arguments";
    case PF_ERR_NO_START: return "no road near the start point";
    case PF_ERR_NO_TARGET: return "no road near the target point";
    case PF_ERR_NO_ROUTE: return "no route found";
    case PF_ERR_FILE: return "cannot open graph file";
    case PF_ERR_INVALID_GRAPH: return "graph file is invalid";
    default: return "internal error";
  }
}

PF_API pf_engine* pf_engine_open(const char* path, int* status) {
  set_status(status, PF_ERR_BAD_ARGS);
  if (!path) return nullptr;
  try {
    std::unique_ptr<pf_engine> e(new pf_engine());
    std::string err;
    if (!e->graph.open(path, err)) {
      bool io = (err == "cannot open file" || err == "not a regular file" || err == "mmap failed");
      set_status(status, io ? PF_ERR_FILE : PF_ERR_INVALID_GRAPH);
      return nullptr;
    }
    e->index.build(e->graph);
    e->router.reset(new pf::Router(e->graph));
    set_status(status, PF_OK);
    return e.release();
  } catch (...) {
    set_status(status, PF_ERR_INTERNAL);
    return nullptr;
  }
}

PF_API void pf_engine_close(pf_engine* e) { delete e; }

PF_API uint32_t pf_engine_node_count(const pf_engine* e) { return e ? e->graph.num_nodes() : 0; }

PF_API int pf_route_find(pf_engine* e, double slat, double slon, double tlat, double tlon, pf_route* out) {
  if (!out) return PF_ERR_BAD_ARGS;
  std::memset(out, 0, sizeof(*out));
  try {
    pf::PathResult r;
    int st = run_query(e, slat, slon, tlat, tlon, r);
    if (st != PF_OK) return st;
    size_t cnt = r.nodes.size();
    double* lat = static_cast<double*>(std::calloc(cnt, sizeof(double)));
    double* lon = static_cast<double*>(std::calloc(cnt, sizeof(double)));
    if (!lat || !lon) { std::free(lat); std::free(lon); return PF_ERR_INTERNAL; }
    for (size_t i = 0; i < cnt; ++i) {
      const pf::Node& nd = e->graph.node(r.nodes[i]);
      lat[i] = nd.lat_e7 * 1e-7;
      lon[i] = nd.lon_e7 * 1e-7;
    }
    out->distance_m = static_cast<double>(r.dist_m);
    out->time_s = r.cost_ds / 10.0;
    out->num_points = static_cast<uint32_t>(cnt);
    out->lat = lat; out->lon = lon;
    return PF_OK;
  } catch (...) {
    return PF_ERR_INTERNAL;
  }
}

PF_API void pf_route_free(pf_route* r) {
  if (!r) return;
  std::free(r->lat); std::free(r->lon);
  std::memset(r, 0, sizeof(*r));
}

PF_API int pf_route_compute(pf_engine* e, double slat, double slon, double tlat, double tlon,
                            pf_route_handle** out) {
  if (!out) return PF_ERR_BAD_ARGS;
  *out = nullptr;
  try {
    pf::PathResult r;
    int st = run_query(e, slat, slon, tlat, tlon, r);
    if (st != PF_OK) return st;
    std::unique_ptr<pf_route_handle> h(new pf_route_handle());
    h->distance_m = static_cast<double>(r.dist_m);
    h->time_s = r.cost_ds / 10.0;
    h->lat.reserve(r.nodes.size());
    h->lon.reserve(r.nodes.size());
    for (uint32_t id : r.nodes) {
      const pf::Node& nd = e->graph.node(id);
      h->lat.push_back(nd.lat_e7 * 1e-7);
      h->lon.push_back(nd.lon_e7 * 1e-7);
    }
    *out = h.release();
    return PF_OK;
  } catch (...) {
    return PF_ERR_INTERNAL;
  }
}

PF_API double pf_route_handle_distance_m(const pf_route_handle* h) { return h ? h->distance_m : 0.0; }
PF_API double pf_route_handle_time_s(const pf_route_handle* h) { return h ? h->time_s : 0.0; }
PF_API uint32_t pf_route_handle_point_count(const pf_route_handle* h) {
  return h ? static_cast<uint32_t>(h->lat.size()) : 0;
}
PF_API const double* pf_route_handle_lats(const pf_route_handle* h) { return h ? h->lat.data() : nullptr; }
PF_API const double* pf_route_handle_lons(const pf_route_handle* h) { return h ? h->lon.data() : nullptr; }
PF_API void pf_route_handle_free(pf_route_handle* h) { delete h; }

}  // extern "C"
