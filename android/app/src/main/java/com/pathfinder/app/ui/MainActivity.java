package com.pathfinder.app.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pathfinder.app.core.Controller;
import com.pathfinder.app.core.DeepSeekClient;
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

import java.io.File;

/** The only screen: map + panels. All logic lives in Controller. */
public final class MainActivity extends Activity implements Controller.Listener {
    private static final int REQUEST_LOCATION = 1;

    private Controller ctl;
    private AndroidVoice voice;
    private LocationSource location;
    private MapView map;
    private SearchPanel search;
    private SettingsPanel settings;
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
            if (ctl.phase == Controller.Phase.IDLE || ctl.phase == Controller.Phase.ROUTE_FAILED || ctl.phase == Controller.Phase.ROUTE_READY) {
                ctl.chooseDestination(Place.dropped(lat, lon, ctl.t().get("droppedPin")));
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
        settings = new SettingsPanel(this, ctl);
        dataPanel = new DataPanel(this, ctl);
        for (View panel : new View[] {search, settings, dataPanel}) {
            root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            insetTop = insets.getSystemWindowInsetTop();
            insetBottom = insets.getSystemWindowInsetBottom();
            applyInsets();
            return insets;
        });
        setContentView(root);

        ctl.addListener(this);
        ctl.init();
        if (location.hasPermission()) location.start();
        else {
            askedBefore = true;
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
        }
        render();
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
        if (search.getVisibility() == View.VISIBLE) search.close();
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
            tiles = new OnlineTileStore(new java.io.File(getCacheDir(), "tiles")); // online build only: no local tiles, so use OpenStreetMap
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
        map.setDestination(c.destination != null, c.destination == null ? 0 : c.destination.lat, c.destination == null ? 0 : c.destination.lon);
        boolean up = c.prefs().headingUp() && c.heading != null;
        map.setUser(c.user != null, c.user == null ? 0 : c.user.lat, c.user == null ? 0 : c.user.lon,
                c.heading == null ? 0f : c.heading.floatValue(), navigating);
        map.setInteractive(!navigating);
        updateCamera(up);
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
                    v -> map.setCamera(c.user.lat, c.user.lon, 16, 0)));
        }

        // panels that are open follow the language / state
        search.refresh();
        settings.refresh();
        dataPanel.refresh();
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
            map.setCamera(c.user.lat, c.user.lon, 15, 0);
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
