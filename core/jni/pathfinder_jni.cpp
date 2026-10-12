// JNI bridge: Java class com.pathfinder.app.nativeapi.NativeEngine  ->  C API (pathfinder.h).
// Safety: every handle that comes from Java is checked against a registry of live handles,
// so a stale, double-freed or forged handle returns an error instead of crashing the app.
// Array arguments are length-checked. No C++ exception can cross into Java (the C API catches them).
#include <jni.h>

#include <cstdint>
#include <mutex>
#include <unordered_set>
#include <vector>

#include "pathfinder.h"

namespace {

std::mutex g_mu;
std::unordered_set<const void*> g_engines;
std::unordered_set<const void*> g_routes;

void* as_ptr(jlong h) { return reinterpret_cast<void*>(static_cast<intptr_t>(h)); }
jlong as_handle(const void* p) { return static_cast<jlong>(reinterpret_cast<intptr_t>(p)); }

bool is_live(const std::unordered_set<const void*>& set, jlong h) {
  if (h == 0) return false;
  std::lock_guard<std::mutex> lock(g_mu);
  return set.count(as_ptr(h)) == 1;
}
void add(std::unordered_set<const void*>& set, const void* p) {
  std::lock_guard<std::mutex> lock(g_mu);
  set.insert(p);
}
bool take(std::unordered_set<const void*>& set, jlong h) {
  if (h == 0) return false;
  std::lock_guard<std::mutex> lock(g_mu);
  return set.erase(as_ptr(h)) == 1;
}
void set_status(JNIEnv* env, jintArray arr, int value) {
  if (!arr || env->GetArrayLength(arr) < 1) return;
  jint v = value;
  env->SetIntArrayRegion(arr, 0, 1, &v);
}
jdoubleArray to_doubles(JNIEnv* env, const double* src, jsize n) {
  jdoubleArray a = env->NewDoubleArray(n);
  if (a && n > 0 && src) env->SetDoubleArrayRegion(a, 0, n, src);
  return a;
}

}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_pathfinder_app_nativeapi_NativeEngine_##name

JNI_FN(jstring, version)(JNIEnv* env, jclass) { return env->NewStringUTF(pf_version()); }

JNI_FN(jlong, open)(JNIEnv* env, jclass, jstring path, jintArray status) {
  if (!path) { set_status(env, status, PF_ERR_BAD_ARGS); return 0; }
  const char* c = env->GetStringUTFChars(path, nullptr);
  if (!c) { set_status(env, status, PF_ERR_INTERNAL); return 0; }
  int st = PF_ERR_INTERNAL;
  pf_engine* e = pf_engine_open(c, &st);
  env->ReleaseStringUTFChars(path, c);
  set_status(env, status, st);
  if (!e) return 0;
  add(g_engines, e);
  return as_handle(e);
}

JNI_FN(void, close)(JNIEnv*, jclass, jlong engine) {
  if (take(g_engines, engine)) pf_engine_close(static_cast<pf_engine*>(as_ptr(engine)));
}

JNI_FN(jint, nodeCount)(JNIEnv*, jclass, jlong engine) {
  if (!is_live(g_engines, engine)) return 0;
  return static_cast<jint>(pf_engine_node_count(static_cast<pf_engine*>(as_ptr(engine))));
}

JNI_FN(jboolean, usesCh)(JNIEnv*, jclass, jlong engine) {
  if (!is_live(g_engines, engine)) return JNI_FALSE;
  return pf_engine_uses_ch(static_cast<pf_engine*>(as_ptr(engine))) == 1 ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jlong, route)(JNIEnv* env, jclass, jlong engine, jdouble slat, jdouble slon, jdouble tlat, jdouble tlon,
                     jintArray status) {
  if (!is_live(g_engines, engine)) { set_status(env, status, PF_ERR_BAD_ARGS); return 0; }
  pf_route_handle* h = nullptr;
  int st = pf_route_compute(static_cast<pf_engine*>(as_ptr(engine)), slat, slon, tlat, tlon, &h);
  set_status(env, status, st);
  if (st != PF_OK || !h) return 0;
  add(g_routes, h);
  return as_handle(h);
}

JNI_FN(void, routeFree)(JNIEnv*, jclass, jlong route) {
  if (take(g_routes, route)) pf_route_handle_free(static_cast<pf_route_handle*>(as_ptr(route)));
}

JNI_FN(jdoubleArray, routeInfo)(JNIEnv* env, jclass, jlong route) {
  if (!is_live(g_routes, route)) return nullptr;
  auto* h = static_cast<pf_route_handle*>(as_ptr(route));
  const double v[2] = {pf_route_handle_distance_m(h), pf_route_handle_time_s(h)};
  return to_doubles(env, v, 2);
}

JNI_FN(jdoubleArray, routeLats)(JNIEnv* env, jclass, jlong route) {
  if (!is_live(g_routes, route)) return nullptr;
  auto* h = static_cast<pf_route_handle*>(as_ptr(route));
  return to_doubles(env, pf_route_handle_lats(h), static_cast<jsize>(pf_route_handle_point_count(h)));
}

JNI_FN(jdoubleArray, routeLons)(JNIEnv* env, jclass, jlong route) {
  if (!is_live(g_routes, route)) return nullptr;
  auto* h = static_cast<pf_route_handle*>(as_ptr(route));
  return to_doubles(env, pf_route_handle_lons(h), static_cast<jsize>(pf_route_handle_point_count(h)));
}

JNI_FN(jintArray, routeStepTypes)(JNIEnv* env, jclass, jlong route) {
  if (!is_live(g_routes, route)) return nullptr;
  auto* h = static_cast<pf_route_handle*>(as_ptr(route));
  const jsize n = static_cast<jsize>(pf_route_handle_step_count(h));
  const int32_t* src = pf_route_handle_step_types(h);
  jintArray a = env->NewIntArray(n);
  if (a && n > 0 && src) env->SetIntArrayRegion(a, 0, n, reinterpret_cast<const jint*>(src));
  return a;
}

JNI_FN(jintArray, routeStepPoints)(JNIEnv* env, jclass, jlong route) {
  if (!is_live(g_routes, route)) return nullptr;
  auto* h = static_cast<pf_route_handle*>(as_ptr(route));
  const jsize n = static_cast<jsize>(pf_route_handle_step_count(h));
  const uint32_t* src = pf_route_handle_step_points(h);
  std::vector<jint> tmp(static_cast<size_t>(n));
  for (jsize i = 0; i < n && src; ++i) tmp[static_cast<size_t>(i)] = static_cast<jint>(src[i]);
  jintArray a = env->NewIntArray(n);
  if (a && n > 0) env->SetIntArrayRegion(a, 0, n, tmp.data());
  return a;
}

JNI_FN(jdoubleArray, routeStepDists)(JNIEnv* env, jclass, jlong route) {
  if (!is_live(g_routes, route)) return nullptr;
  auto* h = static_cast<pf_route_handle*>(as_ptr(route));
  return to_doubles(env, pf_route_handle_step_dists(h), static_cast<jsize>(pf_route_handle_step_count(h)));
}

// out (length >= 7): along_m, off_route_m, remaining_m, to_next_m, bearing_deg, next_step, announce
JNI_FN(jint, progress)(JNIEnv* env, jclass, jlong route, jdouble lat, jdouble lon, jdoubleArray out) {
  if (!is_live(g_routes, route) || !out || env->GetArrayLength(out) < 7) return PF_ERR_BAD_ARGS;
  auto* h = static_cast<pf_route_handle*>(as_ptr(route));
  double o4[4] = {0, 0, 0, 0};
  int32_t next = 0;
  int st = pf_route_handle_progress(h, lat, lon, o4, &next);
  if (st != PF_OK) return st;
  const jdouble res[7] = {o4[0], o4[1], o4[2], o4[3], pf_route_handle_bearing_deg(h), static_cast<jdouble>(next),
                          static_cast<jdouble>(pf_route_handle_take_announce(h))};
  env->SetDoubleArrayRegion(out, 0, 7, res);
  return PF_OK;
}
