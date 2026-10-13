package com.pathfinder.app.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pathfinder.app.core.Controller;
import com.pathfinder.app.core.DeepSeekClient;
import com.pathfinder.app.core.GeoPoint;
import com.pathfinder.app.core.Place;
import com.pathfinder.app.core.Strings;
import com.pathfinder.app.platform.AndroidPrefs;
import com.pathfinder.app.platform.AndroidVoice;
import com.pathfinder.app.platform.LocationSource;
import com.pathfinder.app.platform.MainThreadHandler;
import com.pathfinder.app.platform.MbtilesStore;
import com.pathfinder.app.platform.OnlineTileStore;
import com.pathfinder.app.platform.TileSource;
import com.pathfinder.app.platform.PlacesDb;

import com.pathfinder.app.platform.StreetLookup;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The only screen: map + panels. All logic lives in Controller. */
public final class MainActivity extends Activity implements Controller.Listener {
    private static final int REQUEST_LOCATION = 1;
    private static final int REQUEST_VOICE = 2;

    private Controller ctl;
    private AndroidVoice voice;
    private LocationSource location;
    private MapView map;
    private SearchPanel search;
    private SettingsPanel settings;

    // ---- pin editing (destination pin / pickup point) ----
    private static final int PICK_NONE = 0, PICK_DEST = 1, PICK_PICKUP = 2;
    private int pickMode = PICK_NONE;
    private PinView pin;
    private FrameLayout pickHost;
    private TextView pickTitle, pickStreet, pickCoords;
    private Button pickMyLocation;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService streetIo = Executors.newSingleThreadExecutor();
    private double lastLat, lastLon, lookedLat = Double.NaN, lookedLon = Double.NaN;
    private long movedAt;
    private String street, pickupName;
    private int streetReq;
    private final Runnable pickTick = new Runnable() {
        @Override public void run() {
            if (pickMode == PICK_NONE) return;
            double[] c = map.center();
            long now = SystemClock.uptimeMillis();
            if (Math.abs(c[0] - lastLat) > 1e-6 || Math.abs(c[1] - lastLon) > 1e-6) {
                lastLat = c[0];
                lastLon = c[1];
                movedAt = now;
                street = null;
                streetReq++;
                updatePickText(true);
            } else if (now - movedAt >= 700 && !(c[0] == lookedLat && c[1] == lookedLon)) {
                lookupStreet(c[0], c[1]);
            }
            ui.postDelayed(this, 300);
        }
    };
    private DataPanel dataPanel;
    private FrameLayout topBar;
    private FrameLayout chipHost;
    private FrameLayout bottomHost;
    private LinearLayout fabColumn;
    private LinearLayout topColumn;
    private TileSource tiles;
    private String tilesProblemKey;
    private int tilesGeneration = -1;
    private int insetTop, insetBottom;
    private boolean centeredOnUser, centeredOnData, wasNavigating;
    private long fittedRoute = Long.MIN_VALUE;
    private boolean askedBefore;
    private boolean nightAtCreate, themeChanged;

    @Override
    @SuppressWarnings("deprecation") // setSystemUiVisibility / system window insets keep older phones working
    protected void onCreate(Bundle state) {
        Ui.applyTheme(this);
        nightAtCreate = Ui.dark;
        setTheme(Ui.dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(Ui.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        voice = new AndroidVoice(this, () -> {
            if (ctl != null) ctl.refreshVoice();
        });
        ctl = new Controller(new MainThreadHandler(), new AndroidPrefs(this), voice, PlacesDb::open, new DeepSeekClient(),
                new File(base, "pathfinder"), packBaseUrl());
        location = new LocationSource(this, ctl);

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        map = new MapView(this);
        root.addView(map, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        map.setListener((lat, lon) -> {
            if (pickMode != PICK_NONE) return;
            if (ctl.phase == Controller.Phase.IDLE || ctl.phase == Controller.Phase.ROUTE_FAILED || ctl.phase == Controller.Phase.ROUTE_READY) {
                startPick(PICK_DEST, lat, lon); // long press: move the map to fine-tune the pin, then Confirm
            }
        });

        topColumn = new LinearLayout(this);
        topColumn.setOrientation(LinearLayout.VERTICAL);
        topBar = new FrameLayout(this);
        chipHost = new FrameLayout(this);
        topColumn.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = Ui.dp(this, 8);
        topColumn.addView(chipHost, chipLp);
        root.addView(topColumn, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        bottomHost = new FrameLayout(this);
        root.addView(bottomHost, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        fabColumn = new LinearLayout(this);
        fabColumn.setOrientation(LinearLayout.VERTICAL);
        fabColumn.setGravity(Gravity.END);
        root.addView(fabColumn, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END));

        search = new SearchPanel(this, ctl, place -> ctl.chooseDestination(place));
        search.setOnVoice(this::startVoiceSearch);
        settings = new SettingsPanel(this, ctl);
        settings.setOnThemeChanged(() -> {
            themeChanged = Ui.isNight(this) != nightAtCreate;
            applyThemeChange();
        });
        dataPanel = new DataPanel(this, ctl);
        for (View panel : new View[] {search, settings, dataPanel}) {
            root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        pin = new PinView(this);
        pin.setVisibility(View.GONE);
        pin.setTranslationY(-Ui.dp(this, 26));
        root.addView(pin, new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 52), Gravity.CENTER));
        pickHost = new FrameLayout(this);
        pickHost.setVisibility(View.GONE);
        LinearLayout pickCard = Ui.card(this);
        pickTitle = Ui.text(this, "", 14, Ui.MUTED, false);
        pickStreet = Ui.text(this, "", 18, Ui.TEXT, true);
        pickCoords = Ui.text(this, "", 13, Ui.MUTED, false);
        pickCard.addView(pickTitle);
        pickCard.addView(pickStreet);
        pickCard.addView(pickCoords);
        LinearLayout pickButtons = Ui.row(this);
        pickButtons.setPadding(0, Ui.dp(this, 12), 0, 0);
        Button pickCancel = Ui.button(this, ctl.t().get("cancel"), false, v -> endPick());
        pickMyLocation = Ui.button(this, ctl.t().get("myLocation"), false, v -> {
            ctl.setPickup(null);
            pickupName = null;
            endPick();
        });
        Button pickConfirm = Ui.button(this, ctl.t().get("confirm"), true, v -> confirmPick());
        pickButtons.addView(pickCancel, Ui.weight(1));
        pickButtons.addView(Ui.spacer(this, 8));
        pickButtons.addView(pickMyLocation, Ui.weight(1));
        pickButtons.addView(Ui.spacer(this, 8));
        pickButtons.addView(pickConfirm, Ui.weight(1));
        pickCard.addView(pickButtons);
        pickHost.addView(pickCard, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(pickHost, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            insetTop = insets.getSystemWindowInsetTop();
            insetBottom = insets.getSystemWindowInsetBottom();
            applyInsets();
            return insets;
        });
        setContentView(root);

        ctl.addListener(this);
        // start the position search first: loading the map data below takes a moment on the main thread
        if (location.hasPermission()) location.start();
        else {
            askedBefore = true;
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
        }
        ctl.init();
        render();
    }

    /** Optional MapTiler key, set at build time with -PmapTilerKey=... */
    private String tileKey() {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            String key = info.metaData == null ? null : info.metaData.getString("map_tile_key");
            return key == null ? "" : key;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** Optional download server, set at build time with -PpackBaseUrl=https://... (see docs/PHONE_TEST.md). */
    private String packBaseUrl() {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            String url = info.metaData == null ? null : info.metaData.getString("pack_base_url");
            return url == null ? "" : url;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void applyInsets() {
        int m = Ui.dp(this, 12);
        topColumn.setPadding(m, insetTop + m, m, 0);
        bottomHost.setPadding(m, 0, m, insetBottom + m);
        pickHost.setPadding(m, 0, m, insetBottom + m);
        fabColumn.setPadding(0, 0, m, insetBottom + m);
        search.setInsets(insetTop, insetBottom);
        settings.setInsets(insetTop, insetBottom);
        dataPanel.setInsets(insetTop, insetBottom);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION) return;
        if (location.hasPermission()) location.start();
        else ctl.setLocationState(Controller.LocationState.DENIED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (location.hasPermission()) location.start();
    }

    /** Phone switched between light and dark: rebuild the screen (after navigation ends, so a trip is never reset). */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        themeChanged = Ui.isNight(this) != nightAtCreate;
        applyThemeChange();
    }

    private void applyThemeChange() {
        if (themeChanged && ctl.phase != Controller.Phase.NAVIGATING) recreate();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(pickTick);
        streetIo.shutdownNow();
        location.stop();
        ctl.removeListener(this);
        ctl.dispose();
        voice.shutdown();
        map.shutdown();
        if (tiles != null) tiles.close();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (pickMode != PICK_NONE) endPick();
        else if (search.getVisibility() == View.VISIBLE) search.close();
        else if (settings.getVisibility() == View.VISIBLE) settings.setVisibility(View.GONE);
        else if (dataPanel.getVisibility() == View.VISIBLE) dataPanel.setVisibility(View.GONE);
        else super.onBackPressed();
    }

    @Override
    public void onChanged() {
        render();
    }

    // ---- drawing the state ----

    private boolean canPlan() {
        return ctl.phase == Controller.Phase.IDLE || ctl.phase == Controller.Phase.ROUTE_FAILED || ctl.phase == Controller.Phase.ROUTE_READY;
    }

    private boolean canManageData() {
        return ctl.phase == Controller.Phase.IDLE || ctl.phase == Controller.Phase.NEEDS_DATA
                || ctl.phase == Controller.Phase.ROUTE_FAILED || ctl.phase == Controller.Phase.FATAL;
    }

    private void reopenTiles() {
        if (tiles != null) tiles.close();
        tiles = null;
        tilesProblemKey = null;
        if (ctl.data != null && ctl.data.hasTiles) {
            try {
                tiles = MbtilesStore.open(ctl.data.tiles().getPath());
            } catch (MbtilesStore.TilesException e) {
                tilesProblemKey = e.vector ? "errTilesVector" : "errTilesOpen";
            }
        } else if (getPackageName().endsWith(".online")) {
            String tileKey = tileKey();
            // online build only: no local tiles, so download them (MapTiler if a key was set at build time, else OpenStreetMap)
            tiles = new OnlineTileStore(new java.io.File(getCacheDir(), tileKey.isEmpty() ? "tiles" : "tiles_mt"), tileKey);
        }
        map.setTiles(tiles);
        centeredOnData = false;
    }

    private void render() {
        final Controller c = ctl;
        if (themeChanged) applyThemeChange();
        final Strings t = c.t();
        final boolean navigating = c.phase == Controller.Phase.NAVIGATING;
        if (tilesGeneration != c.dataGeneration) {
            tilesGeneration = c.dataGeneration;
            reopenTiles();
        }

        map.setRoute(c.route == null ? null : c.route.lats, c.route == null ? null : c.route.lons);
        map.setDestination(c.destination != null && pickMode != PICK_DEST, c.destination == null ? 0 : c.destination.lat, c.destination == null ? 0 : c.destination.lon);
        map.setPickup(c.pickup != null && pickMode != PICK_PICKUP && !navigating, c.pickup == null ? 0 : c.pickup.lat, c.pickup == null ? 0 : c.pickup.lon);
        boolean up = c.prefs().headingUp() && c.heading != null;
        map.setUser(c.user != null, c.user == null ? 0 : c.user.lat, c.user == null ? 0 : c.user.lon,
                c.heading == null ? 0f : c.heading.floatValue(), navigating);
        map.setInteractive(!navigating);
        if (pickMode == PICK_NONE) updateCamera(up);
        if (navigating) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // top
        topBar.removeAllViews();
        if (navigating) {
            topBar.addView(Panels.navBanner(this, c));
        } else {
            LinearLayout row = Ui.row(this);
            LinearLayout bar = Ui.row(this);
            bar.setBackground(Ui.rounded(this, Ui.SURFACE, 12));
            bar.setElevation(Ui.dp(this, 4));
            bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
            bar.addView(Icons.view(this, Icons.of(Icons.Kind.SEARCH, Ui.MUTED), 22));
            TextView hint = Ui.text(this, t.get("whereTo"), 16, Ui.MUTED, false);
            hint.setPadding(Ui.dp(this, 10), 0, 0, 0);
            bar.addView(hint);
            bar.setClickable(true);
            bar.setOnClickListener(v -> {
                if (canPlan()) {
                    search.open();
                }
            });
            row.addView(bar, Ui.weight(1));
            row.addView(Ui.spacer(this, 8));
            row.addView(Icons.roundButton(this, Icons.Kind.SETTINGS, Ui.TEXT, Ui.SURFACE, t.get("settings"), v -> settings.open()));
            topBar.addView(row);
        }
        chipHost.removeAllViews();
        chipHost.addView(Panels.chips(this, c, tilesProblemKey, c.prefs().voiceOn() && !c.voiceAvailable, canManageData() ? this::openData : null, this::askLocationPermission));

        // bottom
        bottomHost.removeAllViews();
        View bottom = navigating ? Panels.navBottom(this, c, c::clearRoute)
                : Panels.status(this, c, canManageData() ? this::openData : null, c::clearRoute, c::startNavigation);
        if (bottom != null) bottomHost.addView(bottom);

        // buttons on the right
        fabColumn.removeAllViews();
        fabColumn.setTranslationY(-Ui.dp(this, navigating ? 120 : (c.phase == Controller.Phase.IDLE ? 0 : 150)));
        if (navigating) {
            fabColumn.addView(Icons.roundButton(this, c.prefs().voiceOn() ? Icons.Kind.VOICE_ON : Icons.Kind.VOICE_OFF, Ui.TEXT, Ui.SURFACE,
                    c.prefs().voiceOn() ? t.get("voiceOnTip") : t.get("voiceOffTip"), v -> c.setVoiceOn(!c.prefs().voiceOn())));
            fabColumn.addView(Ui.spacer(this, 8));
            fabColumn.addView(Icons.roundButton(this, c.prefs().headingUp() ? Icons.Kind.DIRECTION_UP : Icons.Kind.NORTH_UP, Ui.TEXT, Ui.SURFACE,
                    c.prefs().headingUp() ? t.get("headingUpTip") : t.get("northUpTip"), v -> c.setHeadingUp(!c.prefs().headingUp())));
        } else if (c.user != null) {
            fabColumn.addView(Icons.roundButton(this, Icons.Kind.LOCATE, Ui.TEXT, Ui.SURFACE, t.get("myLocation"),
                    v -> map.setCamera(c.user.lat, c.user.lon, 17, 0)));
        }
        if (!navigating && c.destination != null) {
            fabColumn.addView(Ui.spacer(this, 8));
            fabColumn.addView(Ui.button(this, t.get("editPin"), false, v -> startPick(PICK_DEST, c.destination.lat, c.destination.lon)));
            fabColumn.addView(Ui.spacer(this, 8));
            String label = t.get("pickup") + (c.pickup == null ? "" : ": " + (pickupName == null ? "*" : shorten(pickupName)));
            fabColumn.addView(Ui.button(this, label, false, v -> {
                double[] mc = map.center();
                GeoPoint from = c.pickup != null ? c.pickup : c.user;
                startPick(PICK_PICKUP, from == null ? mc[0] : from.lat, from == null ? mc[1] : from.lon);
            }));
        }

        // while editing a pin, only the pin and its card are shown
        boolean picking = pickMode != PICK_NONE;
        topColumn.setVisibility(picking ? View.GONE : View.VISIBLE);
        bottomHost.setVisibility(picking ? View.GONE : View.VISIBLE);
        fabColumn.setVisibility(picking ? View.GONE : View.VISIBLE);
        pin.setVisibility(picking ? View.VISIBLE : View.GONE);
        pickHost.setVisibility(picking ? View.VISIBLE : View.GONE);

        // panels that are open follow the language / state
        search.refresh();
        settings.refresh();
        dataPanel.refresh();
    }

    // ---- voice input (the phone's own speech recognizer, no extra library) ----

    private void startVoiceSearch() {
        Strings t = ctl.t();
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, ctl.prefs().lang().voiceTags[0]);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, t.get("voicePrompt"));
        i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            startActivityForResult(i, REQUEST_VOICE);
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, t.get("voiceInputUnavailable"), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_VOICE || resultCode != RESULT_OK || data == null) return;
        java.util.ArrayList<String> heard = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (heard != null && !heard.isEmpty()) search.setQueryFromVoice(heard.get(0));
    }

    // ---- pin editing ----

    private static String shorten(String s) {
        return s.length() <= 18 ? s : s.substring(0, 17) + "...";
    }

    private void startPick(int mode, double lat, double lon) {
        pickMode = mode;
        street = null;
        streetReq++;
        lookedLat = Double.NaN;
        lookedLon = Double.NaN;
        map.setCamera(lat, lon, 17, 0);
        double[] c = map.center();
        lastLat = c[0];
        lastLon = c[1];
        movedAt = SystemClock.uptimeMillis();
        Strings t = ctl.t();
        pin.setColor(mode == PICK_DEST ? 0xFFC62828 : 0xFF2E7D32);
        pickTitle.setText(t.get(mode == PICK_DEST ? "dropPinTitle" : "pickupTitle"));
        pickMyLocation.setVisibility(mode == PICK_PICKUP && ctl.user != null ? View.VISIBLE : View.GONE);
        updatePickText(true);
        ui.removeCallbacks(pickTick);
        ui.postDelayed(pickTick, 300);
        render();
    }

    private void updatePickText(boolean searching) {
        Strings t = ctl.t();
        pickStreet.setText(street != null ? street : t.get(searching ? "searchingStreet" : "streetUnknown"));
        pickCoords.setText(String.format(Locale.US, "%.5f, %.5f", lastLat, lastLon));
    }

    private void lookupStreet(final double lat, final double lon) {
        lookedLat = lat;
        lookedLon = lon;
        final int req = ++streetReq;
        final String lang = ctl.prefs().lang().tag;
        try {
            streetIo.execute(() -> {
                final String found = StreetLookup.lookup(lat, lon, lang);
                runOnUiThread(() -> {
                    if (req != streetReq || pickMode == PICK_NONE) return;
                    street = found;
                    updatePickText(false);
                });
            });
        } catch (RuntimeException rejected) {
            // activity is closing
        }
    }

    private void confirmPick() {
        double[] c = map.center();
        if (pickMode == PICK_DEST) {
            ctl.chooseDestination(Place.dropped(c[0], c[1], street != null ? street : ctl.t().get("droppedPin")));
        } else if (pickMode == PICK_PICKUP) {
            pickupName = street;
            ctl.setPickup(new GeoPoint(c[0], c[1]));
        }
        endPick();
    }

    private void endPick() {
        pickMode = PICK_NONE;
        streetReq++;
        ui.removeCallbacks(pickTick);
        render();
    }

    /** Tapping the "location permission needed" chip: ask again, or open the app settings if Android will not ask. */
    private void askLocationPermission() {
        if (location.hasPermission()) {
            location.start();
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) || !askedBefore) {
            askedBefore = true;
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
        } else {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
        }
    }

    private void openData() {
        dataPanel.open();
    }

    private void updateCamera(boolean headingUp) {
        final Controller c = ctl;
        if (c.phase == Controller.Phase.NAVIGATING && c.user != null) {
            // Heading-up: rotate the map by -heading so the way you travel points to the top of the screen.
            map.setCamera(c.user.lat, c.user.lon, 17.5, headingUp ? (float) -c.heading.doubleValue() : 0f);
            wasNavigating = true;
            return;
        }
        if (wasNavigating) {
            map.setRotation360(0);
            wasNavigating = false;
        }
        if (!centeredOnUser && c.user != null) {
            map.setCamera(c.user.lat, c.user.lon, 16, 0);
            centeredOnUser = true;
        } else if (!centeredOnUser && !centeredOnData && tiles instanceof MbtilesStore && ((MbtilesStore) tiles).center != null) {
            double[] ctr = ((MbtilesStore) tiles).center;
            map.setCamera(ctr[0], ctr[1], 12, 0);
            centeredOnData = true;
        }
        if (c.route != null && c.phase == Controller.Phase.ROUTE_READY && fittedRoute != c.route.handle && c.route.lats.length > 1) {
            double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
            for (int i = 0; i < c.route.lats.length; i++) {
                minLat = Math.min(minLat, c.route.lats[i]);
                maxLat = Math.max(maxLat, c.route.lats[i]);
                minLon = Math.min(minLon, c.route.lons[i]);
                maxLon = Math.max(maxLon, c.route.lons[i]);
            }
            if (map.fitBounds(minLat, minLon, maxLat, maxLon, Ui.dp(this, 40), Ui.dp(this, 110) + insetTop, Ui.dp(this, 40), Ui.dp(this, 230) + insetBottom)) {
                fittedRoute = c.route.handle;
            }
        }
    }
}
