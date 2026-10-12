package com.pathfinder.app.core;

/** Where the user is on a route. */
public final class RouteProgress {
    public final double alongM;
    public final double offRouteM;
    public final double remainingM;
    public final double toNextM;
    /** Direction of the road you are on (0 = north, 90 = east). */
    public final double bearingDeg;
    public final int nextStep;
    /** Voice cue for the next maneuver (delivered once): 0 none, 1 about 500 m, 2 about 200 m, 3 now. */
    public final int announce;

    public RouteProgress(double alongM, double offRouteM, double remainingM, double toNextM, double bearingDeg, int nextStep, int announce) {
        this.alongM = alongM;
        this.offRouteM = offRouteM;
        this.remainingM = remainingM;
        this.toNextM = toNextM;
        this.bearingDeg = bearingDeg;
        this.nextStep = nextStep;
        this.announce = announce;
    }
}
