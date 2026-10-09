#pragma once
#include <cstdint>
#include <vector>

namespace pf {

enum Turn : int32_t {
  TURN_DEPART = 0, TURN_STRAIGHT, TURN_SLIGHT_LEFT, TURN_LEFT, TURN_SHARP_LEFT,
  TURN_SLIGHT_RIGHT, TURN_RIGHT, TURN_SHARP_RIGHT, TURN_UTURN, TURN_ARRIVE
};

struct Steps {
  std::vector<int32_t> type;    // Turn
  std::vector<uint32_t> point;  // index into the route points
  std::vector<double> dist;     // meters from this step to the next one (0 for ARRIVE)
};

struct Progress {
  double along_m = 0;      // distance travelled along the route
  double off_m = 0;        // distance from the route line
  double remaining_m = 0;  // distance still to go
  double to_next_m = 0;    // distance to the next maneuver
  int32_t next_step = 0;   // index into Steps
};

double bearing_deg(double lat1, double lon1, double lat2, double lon2);

// A route polyline with turn steps and a position tracker. Not thread-safe (one per navigation).
class NavRoute {
 public:
  NavRoute(std::vector<double> lat, std::vector<double> lon);  // needs >= 1 point, equal sizes
  const std::vector<double>& lat() const { return lat_; }
  const std::vector<double>& lon() const { return lon_; }
  const std::vector<double>& cum() const { return cum_; }
  const Steps& steps() const { return steps_; }
  double total_m() const { return cum_.back(); }
  Progress update(double lat, double lon);

 private:
  std::vector<double> lat_, lon_, cum_;
  Steps steps_;
  double last_along_ = 0;
};

}  // namespace pf
