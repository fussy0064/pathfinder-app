#include "nav.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>

#include "engine.hpp"

namespace pf {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr double kMetersPerDeg = 111320.0;
constexpr double kWindowM = 30.0;       // look 30 m before/after a point to measure a turn
constexpr double kMinTurnDeg = 30.0;    // smaller bends are ignored
constexpr double kTieM = 1.0;           // two route parts this close are a tie (same road, both ways)
constexpr double kBackM = 5.0;          // allowed backward jump in progress (GPS noise)

int32_t classify(double delta) {  // delta > 0 = clockwise = right
  double a = std::fabs(delta);
  if (a >= 170.0) return TURN_UTURN;
  bool right = delta > 0;
  if (a < 60.0) return right ? TURN_SLIGHT_RIGHT : TURN_SLIGHT_LEFT;
  if (a < 120.0) return right ? TURN_RIGHT : TURN_LEFT;
  return right ? TURN_SHARP_RIGHT : TURN_SHARP_LEFT;
}
}  // namespace

double bearing_deg(double lat1, double lon1, double lat2, double lon2) {
  const double d2r = kPi / 180.0;
  double dlon = (lon2 - lon1) * d2r;
  double y = std::sin(dlon) * std::cos(lat2 * d2r);
  double x = std::cos(lat1 * d2r) * std::sin(lat2 * d2r) -
             std::sin(lat1 * d2r) * std::cos(lat2 * d2r) * std::cos(dlon);
  double b = std::atan2(y, x) / d2r;
  return b < 0 ? b + 360.0 : b;
}

NavRoute::NavRoute(std::vector<double> lat, std::vector<double> lon)
    : lat_(std::move(lat)), lon_(std::move(lon)) {
  const size_t n = lat_.size();
  if (n == 0 || lon_.size() != n) throw std::invalid_argument("bad route");
  cum_.assign(n, 0.0);
  for (size_t i = 1; i < n; ++i)
    cum_[i] = cum_[i - 1] + haversine_m(lat_[i - 1], lon_[i - 1], lat_[i], lon_[i]);

  // ---- turn steps ----
  std::vector<uint32_t> turn_pts;
  std::vector<int32_t> turn_types;
  long cand = -1;
  double cand_abs = 0, cand_delta = 0;
  auto flush = [&]() {
    if (cand >= 0) { turn_pts.push_back(static_cast<uint32_t>(cand)); turn_types.push_back(classify(cand_delta)); }
    cand = -1;
  };
  for (size_t i = 1; i + 1 < n; ++i) {
    size_t j = i, k = i;
    while (j > 0 && cum_[i] - cum_[j] < kWindowM) --j;
    while (k + 1 < n && cum_[k] - cum_[i] < kWindowM) ++k;
    if (cum_[i] - cum_[j] < 1.0 || cum_[k] - cum_[i] < 1.0) continue;
    double in = bearing_deg(lat_[j], lon_[j], lat_[i], lon_[i]);
    double out = bearing_deg(lat_[i], lon_[i], lat_[k], lon_[k]);
    double delta = out - in;
    while (delta > 180.0) delta -= 360.0;
    while (delta <= -180.0) delta += 360.0;
    if (std::fabs(delta) < kMinTurnDeg) continue;
    if (cand >= 0 && cum_[i] - cum_[cand] >= kWindowM) flush();
    if (cand < 0 || std::fabs(delta) > cand_abs) { cand = static_cast<long>(i); cand_abs = std::fabs(delta); cand_delta = delta; }
  }
  flush();

  steps_.type.push_back(TURN_DEPART); steps_.point.push_back(0);
  for (size_t s = 0; s < turn_pts.size(); ++s) { steps_.type.push_back(turn_types[s]); steps_.point.push_back(turn_pts[s]); }
  steps_.type.push_back(TURN_ARRIVE); steps_.point.push_back(static_cast<uint32_t>(n - 1));
  for (size_t s = 0; s < steps_.point.size(); ++s)
    steps_.dist.push_back(s + 1 < steps_.point.size() ? cum_[steps_.point[s + 1]] - cum_[steps_.point[s]] : 0.0);
}

Progress NavRoute::update(double lat, double lon) {
  Progress p;
  const size_t n = lat_.size();
  const int32_t last_step = static_cast<int32_t>(steps_.type.size()) - 1;
  if (n == 1) {
    p.off_m = haversine_m(lat, lon, lat_[0], lon_[0]);
    p.next_step = last_step;
    return p;
  }
  const double kx = kMetersPerDeg * std::cos(lat * kPi / 180.0), ky = kMetersPerDeg;
  const size_t segs = n - 1;
  auto seg_dist = [&](size_t i, double& t) {
    double ax = (lon_[i] - lon) * kx, ay = (lat_[i] - lat) * ky;
    double bx = (lon_[i + 1] - lon) * kx, by = (lat_[i + 1] - lat) * ky;
    double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
    t = len2 < 1e-9 ? 0.0 : std::min(1.0, std::max(0.0, -(ax * dx + ay * dy) / len2));
    double px = ax + t * dx, py = ay + t * dy;
    return std::sqrt(px * px + py * py);
  };
  double best = 1e300;
  for (size_t i = 0; i < segs; ++i) {
    double tt, d = seg_dist(i, tt);
    best = std::min(best, d);
  }
  // Among near-ties (e.g. the same road driven twice) take the first one that keeps progress
  // moving forward; otherwise take the closest.
  double dist = best;
  size_t chosen = segs;
  double chosen_along = 0;
  for (size_t i = 0; i < segs; ++i) {
    double tt, d = seg_dist(i, tt);
    if (d > best + kTieM) continue;
    double al = cum_[i] + tt * (cum_[i + 1] - cum_[i]);
    if (al >= last_along_ - kBackM) { chosen = i; dist = d; chosen_along = al; break; }
  }
  if (chosen == segs) {  // no forward candidate: closest segment wins
    for (size_t i = 0; i < segs; ++i) {
      double tt, d = seg_dist(i, tt);
      if (d <= best) { chosen = i; dist = d; chosen_along = cum_[i] + tt * (cum_[i + 1] - cum_[i]); break; }
    }
  }
  last_along_ = chosen_along;

  p.off_m = dist;
  p.along_m = chosen_along;
  p.remaining_m = std::max(0.0, total_m() - p.along_m);
  p.next_step = last_step;
  for (int32_t s = 1; s <= last_step; ++s)
    if (cum_[steps_.point[s]] > p.along_m + 0.5) { p.next_step = s; break; }
  p.to_next_m = std::max(0.0, cum_[steps_.point[p.next_step]] - p.along_m);
  return p;
}

}  // namespace pf
