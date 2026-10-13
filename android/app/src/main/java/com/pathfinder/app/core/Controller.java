package com.pathfinder.app.core;

import com.pathfinder.app.nativeapi.EngineClient;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * All app state and logic, with no Android classes (so it is tested on a desktop JVM).
 * Call every public method on the main thread; results from background work are posted back to it.
 */
public final class Controller {
    public enum Phase { LOADING, NEEDS_DATA, IDLE, CALCULATING, ROUTE_READY, ROUTE_FAILED, NAVIGATING, ARRIVED, FATAL }

    public enum LocationState { UNKNOWN, READY, DENIED, SERVICE_OFF }

    public interface Listener {
        void onChanged();
    }

    public interface Result<T> {
        void ok(T value);

        /** key is a text key (see Strings). */
        void fail(String key);
    }

    public static final double OFF_ROUTE_M = 50;      // farther than this from the route = off route
    public static final int OFF_ROUTE_UPDATES = 3;    // ... for this many GPS fixes in a row = reroute
    public static final double ARRIVE_M = 20;
    public static final double MAX_ACCURACY_M = 50;   // ignore worse GPS fixes while navigating

    private final Ports.MainThread main;
    private final Ports.Prefs prefs;
    private final Ports.Voice voice;
    private final Ports.PlacesOpener placesOpener;
    private final AiService ai;
    private final File dataDir;
    private final String packBaseUrl;
    private final List<Listener> listeners = new ArrayList<>();
    private final ExecutorService io = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pathfinder-io");
        t.setDaemon(true);
        return t;
    });

    // ---- state (read by the UI) ----
    public Phase phase = Phase.LOADING;
    public String messageKey;
    private String[] messageArgs = new String[0];
    public DataStatus data;
    public Ports.PlacesIndex places;
    public LocationState locationState = LocationState.UNKNOWN;
    public GeoPoint user;
    /** Start of the route when the user picked another pickup point; null = my location. */
    public GeoPoint pickup;
    public Place destination;
    public RouteResult route;
    public RouteProgress progress;
    public long calcMillis = -1;
    public boolean rerouting;
    public boolean usesCh;
    public boolean voiceAvailable;
    public Double heading; // smoothed travel direction (0 = north), used while navigating
    public PackManager packs; // null when no download server is configured
    public String packsProblemKey;
    /** Bumped on every data (re)load so the map can reopen the tiles file. */
    public int dataGeneration;

    private EngineClient engine;
    private Lang voiceLang;
    private Double gpsHeading;
    private int offCount;
    private boolean progressBusy;
    private boolean disposed;

    public Controller(Ports.MainThread main, Ports.Prefs prefs, Ports.Voice voice, Ports.PlacesOpener placesOpener,
                      AiService ai, File dataDir, String packBaseUrl) {
        this.main = main;
        this.prefs = prefs;
        this.voice = voice;
        this.placesOpener = placesOpener;
        this.ai = ai;
        this.dataDir = dataDir;
        this.packBaseUrl = packBaseUrl == null ? "" : packBaseUrl;
        this.voiceLang = prefs.lang();
    }

    // ---- small helpers ----

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        if (disposed) return;
        for (Listener l : new ArrayList<>(listeners)) l.onChanged();
    }

    public Strings t() { return new Strings(prefs.lang()); }
    public Ports.Prefs prefs() { return prefs; }

    public String message() {
        return messageKey == null ? null : t().get(messageKey, messageArgs);
    }

    private void setMessage(String key, String... args) {
        messageKey = key;
        messageArgs = args;
    }

    private void speak(String text) {
        if (prefs.voiceOn() && text != null) voice.speak(text);
    }

    static String keyForStatus(int status) {
        switch (status) {
            case 1: return "errBadArgs";
            case 2: return "errNoStart";
            case 3: return "errNoTarget";
            case 4: return "errNoRoute";
            case 5: return "errFile";
            case 6: return "errInvalidGraph";
            case 7: return "errInternal";
            case 8: return "errLibrary";
            default: return "errUnknown";
        }
    }

    // ---- settings (go through here so the UI and voice stay in sync) ----

    public void setLanguage(Lang lang) {
        prefs.setLang(lang);
        if (lang != voiceLang) {
            voiceLang = lang;
            configureVoice();
        }
        changed();
    }

    public void setVoiceOn(boolean on) {
        prefs.setVoiceOn(on);
        if (!on) voice.stop();
        changed();
    }

    public void setHeadingUp(boolean on) { prefs.setHeadingUp(on); changed(); }
    public void setAiOn(boolean on) { prefs.setAiOn(on); changed(); }
    /** Returns true when the key was accepted and saved. */
    public boolean saveApiKey(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty() || k.length() > 200 || k.matches(".*\\s.*")) return false; // keys never contain spaces
        prefs.saveApiKey(k);
        changed();
        return prefs.hasApiKey();
    }
    public void removeApiKey() { prefs.removeApiKey(); changed(); }

    private void configureVoice() {
        voiceAvailable = voice.configure(prefs.lang().voiceTags);
    }

    /** The phone's text-to-speech engine finished starting: look for the voice again. */
    public void refreshVoice() {
        configureVoice();
        changed();
    }

    /** Settings > Test voice. */
    public void testVoice() {
        if (voiceAvailable) voice.speak(t().get("testPhrase"));
    }

    // ---- start / data ----

    public void init() {
        phase = Phase.LOADING;
        configureVoice();
        changed();
        loadData();
    }

    /** Re-reads the data files (after installing or deleting map data). */
    public void reloadData() {
        if (phase == Phase.NAVIGATING || phase == Phase.CALCULATING || phase == Phase.ROUTE_READY) return;
        releaseRoute();
        destination = null;
        closeData(() -> {
            phase = Phase.LOADING;
            changed();
            loadData();
        });
    }

    /** Deletes all installed map data and reloads (the app goes back to "needs data"). */
    public void deleteAllData() {
        closeData(() -> {
            DataStatus.deleteAll(dataDir);
            reloadData();
        });
    }

    private void closeData(Runnable then) {
        if (places != null) {
            places.close();
            places = null;
        }
        usesCh = false;
        EngineClient e = engine;
        engine = null;
        if (e != null) e.close(then);
        else main.post(then);
    }

    private void loadData() {
        data = DataStatus.check(dataDir);
        dataGeneration++;
        if (!packBaseUrl.isEmpty() && packs == null) {
            try {
                packs = new PackManager(packBaseUrl, dataDir, false);
            } catch (PackException e) {
                packsProblemKey = "errPackUrl";
            }
        }
        if (data.hasPlaces) {
            try {
                places = placesOpener.open(data.places().getPath());
            } catch (Exception e) {
                places = null; // search disabled, routing still works
            }
        }
        if (!data.hasGraph) {
            phase = Phase.NEEDS_DATA;
            changed();
            return;
        }
        EngineClient c = new EngineClient(main);
        engine = c;
        c.open(data.graph().getPath(), new EngineClient.Callback<EngineClient>() {
            @Override public void onResult(EngineClient e) {
                if (engine != e) return; // reloaded meanwhile
                usesCh = e.usesCh();
                phase = Phase.IDLE;
                setMessage(null);
                changed();
            }

            @Override public void onError(int status) {
                phase = Phase.FATAL;
                setMessage(keyForStatus(status));
                changed();
            }
        });
    }

    // ---- GPS ----

    public void setLocationState(LocationState s) {
        locationState = s;
        changed();
    }

    public void onFix(Fix f) {
        if (!GeoPoint.valid(f.lat, f.lon)) return;
        user = new GeoPoint(f.lat, f.lon);
        if (f.speedMs > 1.0f && f.hasBearing && !Float.isNaN(f.bearingDeg)) gpsHeading = (double) f.bearingDeg; // only meaningful while moving
        if (phase == Phase.NAVIGATING) {
            if (progress == null && gpsHeading != null) heading = Heading.smooth(heading, gpsHeading, 0.3);
            if (f.accuracyM <= MAX_ACCURACY_M) updateProgress(user);
        }
        changed();
    }

    // ---- search ----

    /** Offline place search by name (runs in the background). */
    public void search(String query, Result<List<Place>> result) {
        final Ports.PlacesIndex db = places;
        if (db == null) {
            result.fail("searchNoData");
            return;
        }
        io.execute(() -> {
            try {
                List<Place> found = db.search(query, 20);
                main.post(() -> result.ok(found));
            } catch (Exception e) {
                main.post(() -> result.fail("errUnknown"));
            }
        });
    }

    /**
     * AI search: DeepSeek turns the typed words into place kinds / names; the offline database finds the places.
     * Only the typed words leave the phone.
     */
    public void aiSearch(String text, Result<List<Place>> result) {
        if (!prefs.aiOn() || !prefs.hasApiKey()) {
            result.fail("aiNeedsSetup");
            return;
        }
        final Ports.PlacesIndex db = places;
        if (db == null) {
            result.fail("searchNoData");
            return;
        }
        final GeoPoint u = user;
        io.execute(() -> {
            try {
                String key = prefs.readApiKey();
                if (key == null || key.isEmpty()) throw new AiException("aiErrNoKey");
                AiIntent intent = ai.interpret(key, text);
                List<Place> out = new ArrayList<>();
                Set<Long> seen = new HashSet<>();
                if (!intent.kinds.isEmpty()) {
                    addAll(out, seen, db.byKinds(intent.kinds, u != null, u == null ? 0 : u.lat, u == null ? 0 : u.lon, 10));
                }
                for (String n : intent.names) addAll(out, seen, db.search(n, 8));
                if (out.isEmpty()) throw new AiException("aiNoResults");
                main.post(() -> result.ok(out));
            } catch (AiException e) {
                main.post(() -> result.fail(e.key));
            } catch (Exception e) {
                main.post(() -> result.fail("errUnknown"));
            }
        });
    }

    private static void addAll(List<Place> out, Set<Long> seen, List<Place> found) {
        for (Place p : found) {
            if (out.size() < 15 && seen.add(p.id)) out.add(p);
        }
    }

    /** Settings > Test connection: sends the single word "pharmacy". */
    public void testAi(Result<Void> result) {
        io.execute(() -> {
            try {
                String key = prefs.readApiKey();
                if (key == null || key.isEmpty()) throw new AiException("aiErrNoKey");
                ai.interpret(key, "pharmacy");
                main.post(() -> result.ok(null));
            } catch (AiException e) {
                main.post(() -> result.fail(e.key));
            } catch (Exception e) {
                main.post(() -> result.fail("errUnknown"));
            }
        });
    }

    // ---- map data download ----

    public void fetchCatalog(Result<List<PackInfo>> result) {
        final PackManager pm = packs;
        if (pm == null) {
            result.fail(packsProblemKey != null ? packsProblemKey : "noServer");
            return;
        }
        io.execute(() -> {
            try {
                List<PackInfo> list = pm.fetchCatalog();
                main.post(() -> result.ok(list));
            } catch (PackException e) {
                main.post(() -> result.fail("pack:" + e.getMessage()));
            }
        });
    }

    public void installPack(PackInfo pack, PackManager.Progress progressCb, PackManager.CancelToken cancel, Result<Void> result) {
        final PackManager pm = packs;
        if (pm == null) {
            result.fail("noServer");
            return;
        }
        io.execute(() -> {
            try {
                pm.install(pack, (done, total, file) -> main.post(() -> progressCb.onProgress(done, total, file)), cancel);
                main.post(() -> {
                    reloadData();
                    result.ok(null);
                });
            } catch (PackException e) {
                main.post(() -> result.fail("pack:" + e.getMessage()));
            }
        });
    }

    // ---- route planning ----

    public void chooseDestination(Place place) {
        destination = place;
        calculate();
    }

    private void calculate() {
        final EngineClient e = engine;
        final GeoPoint u = pickup != null ? pickup : user;
        final Place d = destination;
        if (e == null || d == null) return;
        if (u == null) {
            fail("errWaitingGpsPosition");
            return;
        }
        phase = Phase.CALCULATING;
        setMessage(null);
        changed();
        final long t0 = System.nanoTime();
        e.route(u.lat, u.lon, d.lat, d.lon, new EngineClient.Callback<RouteResult>() {
            @Override public void onResult(RouteResult r) {
                if (engine != e || phase != Phase.CALCULATING) { e.release(r); return; }
                setRoute(r);
                calcMillis = (System.nanoTime() - t0) / 1_000_000;
                phase = Phase.ROUTE_READY;
                changed();
            }

            @Override public void onError(int status) {
                if (phase == Phase.CALCULATING) fail(keyForStatus(status));
            }
        });
    }

    /** Change where the route starts (null = my location). Recalculates when a destination is set. */
    public void setPickup(GeoPoint p) {
        pickup = p;
        if (destination != null && phase != Phase.NAVIGATING) calculate();
        else changed();
    }

    private void setRoute(RouteResult r) {
        RouteResult old = route;
        route = r;
        progress = null;
        if (old != null && engine != null) engine.release(old);
    }

    private void releaseRoute() {
        if (route != null && engine != null) engine.release(route);
        route = null;
        progress = null;
    }

    private void fail(String key) {
        setMessage(key);
        phase = Phase.ROUTE_FAILED;
        changed();
    }

    public void clearRoute() {
        releaseRoute();
        destination = null;
        pickup = null;
        heading = null;
        setMessage(null);
        phase = Phase.IDLE;
        voice.stop();
        changed();
    }

    // ---- navigation ----

    public void startNavigation() {
        if (route == null) return;
        offCount = 0;
        progress = null;
        heading = gpsHeading;
        phase = Phase.NAVIGATING;
        speak(t().get("vNavStart"));
        changed();
    }

    private void updateProgress(GeoPoint pos) {
        final RouteResult r = route;
        final EngineClient e = engine;
        if (r == null || e == null || progressBusy || rerouting) return;
        progressBusy = true;
        e.progress(r, pos.lat, pos.lon, new EngineClient.Callback<RouteProgress>() {
            @Override public void onResult(RouteProgress pr) {
                progressBusy = false;
                if (phase != Phase.NAVIGATING || route != r) return;
                progress = pr;
                // heading: the road direction while on the route, otherwise the GPS direction
                Double target = pr.offRouteM < 30 ? Double.valueOf(pr.bearingDeg) : gpsHeading;
                if (target != null) heading = Heading.smooth(heading, target, 0.3);
                if (pr.announce != 0 && r.steps.length > 0) {
                    RouteStep step = r.steps[Math.min(Math.max(pr.nextStep, 0), r.steps.length - 1)];
                    speak(Guidance.phrase(t(), step.type, pr.announce, pr.toNextM));
                }
                if (pr.remainingM <= ARRIVE_M) {
                    phase = Phase.ARRIVED;
                    speak(t().get("vArrived"));
                } else if (pr.offRouteM > OFF_ROUTE_M) {
                    if (++offCount >= OFF_ROUTE_UPDATES && user != null) reroute(user);
                } else {
                    offCount = 0;
                }
                changed();
            }

            @Override public void onError(int status) {
                progressBusy = false; // route released or engine closed: ignore this update
            }
        });
    }

    private void reroute(GeoPoint from) {
        final EngineClient e = engine;
        final Place d = destination;
        if (e == null || d == null) return;
        rerouting = true;
        speak(t().get("vRerouting"));
        changed();
        e.route(from.lat, from.lon, d.lat, d.lon, new EngineClient.Callback<RouteResult>() {
            @Override public void onResult(RouteResult r) {
                offCount = 0;
                rerouting = false;
                if (phase == Phase.NAVIGATING && engine == e) setRoute(r);
                else e.release(r);
                changed();
            }

            @Override public void onError(int status) {
                offCount = 0;
                rerouting = false;
                setMessage(keyForStatus(status)); // keep the old route and try again later
                changed();
            }
        });
    }

    public void dispose() {
        disposed = true;
        voice.stop();
        if (places != null) places.close();
        if (engine != null) engine.close(null);
        io.shutdown();
    }
}
