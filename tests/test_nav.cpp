// Navigation tests: turn steps and route progress, on shapes with known answers.
#include <cmath>
#include <cstdio>
#include <vector>

#include "engine.hpp"
#include "nav.hpp"
#include "pathfinder.h"

static int g_fail = 0, g_checks = 0;
#define CHECK(c) do { ++g_checks; if (!(c)) { ++g_fail; std::printf("FAIL line %d: %s\n", __LINE__, #c); } } while (0)

static const double kLat0 = 10.0, kLon0 = 10.0;
static const double kMx = 111320.0 * std::cos(kLat0 * M_PI / 180.0), kMy = 111320.0;

struct Pts { std::vector<double> lat, lon; };
// points given in meters (east x, north y) from the origin
static Pts from_m(const std::vector<std::pair<double, double>>& m) {
  Pts p;
  for (auto& q : m) { p.lon.push_back(kLon0 + q.first / kMx); p.lat.push_back(kLat0 + q.second / kMy); }
  return p;
}
static std::vector<std::pair<double, double>> line(double x0, double y0, double x1, double y1, double step) {
  std::vector<std::pair<double, double>> v;
  double len = std::hypot(x1 - x0, y1 - y0);
  int n = std::max(1, (int)std::lround(len / step));
  for (int i = 0; i <= n; ++i) v.push_back({x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n});
  return v;
}
static void append(std::vector<std::pair<double, double>>& a, const std::vector<std::pair<double, double>>& b) {
  a.insert(a.end(), b.begin() + 1, b.end());  // skip duplicated joint
}
static std::vector<int32_t> types(const pf::NavRoute& r) { return r.steps().type; }

static void test_bearing() {
  CHECK(std::fabs(pf::bearing_deg(10, 10, 10.01, 10) - 0) < 1e-6);
  CHECK(std::fabs(pf::bearing_deg(10, 10, 10, 10.01) - 90) < 0.1);
  CHECK(std::fabs(pf::bearing_deg(10, 10, 9.99, 10) - 180) < 1e-6);
  CHECK(std::fabs(pf::bearing_deg(10, 10, 10, 9.99) - 270) < 0.1);
}

static void test_steps() {
  { // straight line: only depart + arrive
    Pts p = from_m(line(0, 0, 500, 0, 20));
    pf::NavRoute r(p.lat, p.lon);
    CHECK(types(r) == (std::vector<int32_t>{pf::TURN_DEPART, pf::TURN_ARRIVE}));
    CHECK(std::fabs(r.total_m() - 500) < 1.0);
    CHECK(std::fabs(r.steps().dist[0] - 500) < 1.0 && r.steps().dist[1] == 0);
  }
  { // east, then north = left turn (90 deg)
    auto m = line(0, 0, 100, 0, 25); append(m, line(100, 0, 100, 100, 25));
    Pts p = from_m(m); pf::NavRoute r(p.lat, p.lon);
    CHECK(types(r) == (std::vector<int32_t>{pf::TURN_DEPART, pf::TURN_LEFT, pf::TURN_ARRIVE}));
    CHECK(std::fabs(r.steps().dist[0] - 100) < 1.0 && std::fabs(r.steps().dist[1] - 100) < 1.0);
  }
  { // east, then south = right turn
    auto m = line(0, 0, 100, 0, 25); append(m, line(100, 0, 100, -100, 25));
    Pts p = from_m(m); pf::NavRoute r(p.lat, p.lon);
    CHECK(types(r) == (std::vector<int32_t>{pf::TURN_DEPART, pf::TURN_RIGHT, pf::TURN_ARRIVE}));
  }
  { // 45 degree bend to the right = slight right
    auto m = line(0, 0, 100, 0, 25); append(m, line(100, 0, 170.7, -70.7, 25));
    Pts p = from_m(m); pf::NavRoute r(p.lat, p.lon);
    CHECK(types(r) == (std::vector<int32_t>{pf::TURN_DEPART, pf::TURN_SLIGHT_RIGHT, pf::TURN_ARRIVE}));
  }
  { // 135 degree turn to the left = sharp left
    auto m = line(0, 0, 100, 0, 25); append(m, line(100, 0, 29.3, 70.7, 25));
    Pts p = from_m(m); pf::NavRoute r(p.lat, p.lon);
    CHECK(types(r) == (std::vector<int32_t>{pf::TURN_DEPART, pf::TURN_SHARP_LEFT, pf::TURN_ARRIVE}));
  }
  { // out and back = u-turn
    auto m = line(0, 0, 100, 0, 25); append(m, line(100, 0, 0, 0.5, 25));
    Pts p = from_m(m); pf::NavRoute r(p.lat, p.lon);
    CHECK(types(r) == (std::vector<int32_t>{pf::TURN_DEPART, pf::TURN_UTURN, pf::TURN_ARRIVE}));
  }
  { // gentle curve (R=300 m, 90 deg): no instruction
    std::vector<std::pair<double, double>> m;
    for (int i = 0; i <= 40; ++i) { double a = (M_PI / 2) * i / 40; m.push_back({300 * std::sin(a), 300 * (1 - std::cos(a))}); }
    Pts p = from_m(m); pf::NavRoute r(p.lat, p.lon);
    CHECK(types(r).size() == 2);
  }
  { // tight 90 degree corner drawn with an arc (R=20 m): ONE left turn, not many
    std::vector<std::pair<double, double>> m = line(0, 0, 100, 0, 10);
    for (int i = 1; i <= 12; ++i) { double a = (M_PI / 2) * i / 12; m.push_back({100 + 20 * std::sin(a), 20 * (1 - std::cos(a))}); }
    append(m, line(120, 20, 120, 120, 10));
    Pts p = from_m(m); pf::NavRoute r(p.lat, p.lon);
    CHECK(types(r).size() == 3 && types(r)[1] == pf::TURN_LEFT);
  }
  { // two turns close together (S-bend, 40 m apart) stay separate
    auto m = line(0, 0, 100, 0, 10); append(m, line(100, 0, 100, 40, 10));
    append(m, line(100, 40, 200, 40, 10));
    Pts p = from_m(m); pf::NavRoute r(p.lat, p.lon);
    CHECK(types(r) == (std::vector<int32_t>{pf::TURN_DEPART, pf::TURN_LEFT, pf::TURN_RIGHT, pf::TURN_ARRIVE}));
  }
  { // single point route (start == target)
    Pts p = from_m({{0, 0}});
    pf::NavRoute r(p.lat, p.lon);
    CHECK(types(r) == (std::vector<int32_t>{pf::TURN_DEPART, pf::TURN_ARRIVE}));
    pf::Progress pr = r.update(kLat0, kLon0);
    CHECK(pr.remaining_m == 0 && pr.next_step == 1);
  }
  { bool threw = false; try { pf::NavRoute r({}, {}); } catch (...) { threw = true; } CHECK(threw); }
}

static void test_progress() {
  // east 100 m, north 100 m (left turn at 100 m). Total 200 m.
  auto m = line(0, 0, 100, 0, 25); append(m, line(100, 0, 100, 100, 25));
  Pts p = from_m(m);
  pf::NavRoute r(p.lat, p.lon);
  auto at = [&](double x, double y) { return r.update(kLat0 + y / kMy, kLon0 + x / kMx); };

  pf::Progress a = at(40, 0);
  CHECK(std::fabs(a.along_m - 40) < 1.0 && a.off_m < 0.5);
  CHECK(std::fabs(a.remaining_m - 160) < 1.0);
  CHECK(a.next_step == 1 && std::fabs(a.to_next_m - 60) < 1.0);   // next = LEFT turn in 60 m
  pf::Progress b = at(60, 20);                                      // 20 m north of the road
  CHECK(std::fabs(b.off_m - 20) < 0.5 && std::fabs(b.along_m - 60) < 1.0);
  pf::Progress c = at(100, 50);                                     // past the corner
  CHECK(std::fabs(c.along_m - 150) < 1.0 && c.next_step == 2 && std::fabs(c.to_next_m - 50) < 1.0);
  pf::Progress d = at(100, 100);
  CHECK(d.remaining_m < 1.0 && d.next_step == 2);
  pf::Progress e = at(100, 130);                                    // 30 m beyond the end
  CHECK(e.remaining_m < 1.0 && std::fabs(e.off_m - 30) < 0.5);
  pf::Progress f = at(-50, 0);                                      // before the start
  CHECK(f.along_m < 1.0 && std::fabs(f.off_m - 50) < 0.5 && f.next_step == 1);

  // out-and-back road: progress must keep growing on the way back
  auto mm = line(0, 0, 300, 0, 20); append(mm, line(300, 0, 0, 0, 20));
  Pts q = from_m(mm); pf::NavRoute rb(q.lat, q.lon);
  double prev = -1; bool mono = true;
  for (double x = 0; x <= 300; x += 10) { double al = rb.update(kLat0, kLon0 + x / kMx).along_m; if (al < prev - 1) mono = false; prev = al; }
  for (double x = 290; x >= 0; x -= 10) { double al = rb.update(kLat0, kLon0 + x / kMx).along_m; if (al < prev - 1) mono = false; prev = al; }
  CHECK(mono && std::fabs(prev - 600) < 12);
  std::printf("[Nav] out-and-back progress is monotonic, ends at %.0f of 600 m\n", prev);
}

static void test_c_api_nav() {
  CHECK(pf_route_handle_step_count(nullptr) == 0 && pf_route_handle_step_types(nullptr) == nullptr);
  double out[4]; int32_t ns;
  CHECK(pf_route_handle_progress(nullptr, 10, 10, out, &ns) == PF_ERR_BAD_ARGS);
}

int main() {
  test_bearing();
  test_steps();
  test_progress();
  test_c_api_nav();
  std::printf("%d checks, %d failed\n", g_checks, g_fail);
  return g_fail ? 1 : 0;
}
