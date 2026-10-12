package com.pathfinder.app.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.pathfinder.app.core.Controller;
import com.pathfinder.app.core.PackInfo;
import com.pathfinder.app.core.PackManager;
import com.pathfinder.app.core.Strings;

import java.util.List;
import java.util.Locale;

/** What map data is installed, downloading map packs, and deleting the data. Full screen. */
final class DataPanel extends FrameLayout {
    private final Controller c;
    private final boolean online;
    private final LinearLayout body;
    private final TextView title;
    private List<PackInfo> catalog;
    private String catalogError;
    private boolean loadingCatalog;
    private String installingId;
    private PackManager.CancelToken cancel;
    private long doneBytes, totalBytes;
    private String currentFile = "";
    private String installError;

    DataPanel(Context ctx, Controller controller) {
        super(ctx);
        this.c = controller;
        this.online = ctx.checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED;
        setBackgroundColor(Ui.SURFACE);
        setClickable(true);
        setVisibility(GONE);

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout bar = Ui.row(ctx);
        bar.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8));
        bar.addView(Icons.roundButton(ctx, Icons.Kind.BACK, Ui.TEXT, Ui.SURFACE, controller.t().get("back"), v -> setVisibility(GONE)));
        title = Ui.text(ctx, "", 20, Ui.TEXT, true);
        title.setPadding(Ui.dp(ctx, 8), 0, 0, 0);
        bar.addView(title);
        column.addView(bar);
        ScrollView scroll = new ScrollView(ctx);
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), Ui.dp(ctx, 32));
        scroll.addView(body);
    }

    void setInsets(int top, int bottom) {
        setPadding(0, top, 0, bottom);
    }

    void open() {
        catalog = null;
        catalogError = null;
        installError = null;
        setVisibility(VISIBLE);
        refresh();
        loadCatalog();
    }

    private void loadCatalog() {
        if (!online || c.packs == null || loadingCatalog) return;
        loadingCatalog = true;
        c.fetchCatalog(new Controller.Result<List<PackInfo>>() {
            @Override public void ok(List<PackInfo> list) {
                loadingCatalog = false;
                catalog = list;
                catalogError = null;
                refresh();
            }

            @Override public void fail(String key) {
                loadingCatalog = false;
                catalogError = key;
                refresh();
            }
        });
    }

    private String text(String key) {
        return key.startsWith("pack:") ? key.substring(5) : c.t().get(key);
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, bytes < 10L * 1048576 ? "%.1f MB" : "%.0f MB", bytes / 1048576.0);
    }

    void refresh() {
        if (getVisibility() != VISIBLE) return;
        final Context ctx = getContext();
        final Strings t = c.t();
        title.setText(t.get("mapData"));
        body.removeAllViews();

        body.addView(Ui.text(ctx, t.get("installed"), 16, Ui.TEXT, true));
        body.addView(statusRow(ctx, t.get("roadGraph"), c.data != null && c.data.hasGraph, null));
        body.addView(statusRow(ctx, t.get("fastIndex"), c.usesCh, c.usesCh ? t.get("active") : t.get("notInstalledAstar")));
        body.addView(statusRow(ctx, t.get("placeSearch"), c.data != null && c.data.hasPlaces, null));
        body.addView(statusRow(ctx, t.get("mapTiles"), c.data != null && c.data.hasTiles, null));
        if (c.data != null) {
            TextView path = Ui.text(ctx, c.data.dir.getPath(), 12, Ui.MUTED, false);
            path.setTextIsSelectable(true);
            path.setPadding(0, Ui.dp(ctx, 8), 0, 0);
            body.addView(path);
        }
        body.addView(Ui.spacer(ctx, 16));
        body.addView(Ui.text(ctx, t.get("download"), 16, Ui.TEXT, true));

        if (!online) {
            body.addView(Ui.text(ctx, t.get("onlineOnlyDownload"), 14, Ui.MUTED, false));
        } else if (c.packs == null) {
            body.addView(Ui.text(ctx, c.packsProblemKey != null ? t.get(c.packsProblemKey) : t.get("noServer"), 14, Ui.MUTED, false));
        } else {
            if (installError != null) body.addView(Ui.text(ctx, installError, 14, Ui.ERROR, false));
            if (loadingCatalog) {
                body.addView(new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal) {{ setIndeterminate(true); }});
            } else if (catalogError != null) {
                body.addView(Ui.text(ctx, text(catalogError), 14, Ui.ERROR, false));
                body.addView(Ui.button(ctx, t.get("retry"), false, v -> {
                    catalogError = null;
                    refresh();
                    loadCatalog();
                }));
            } else if (catalog != null) {
                if (catalog.isEmpty()) body.addView(Ui.text(ctx, t.get("noPacks"), 14, Ui.MUTED, false));
                for (PackInfo p : catalog) body.addView(packRow(ctx, t, p));
            }
        }

        body.addView(Ui.spacer(ctx, 24));
        Button delete = Ui.button(ctx, t.get("deleteAll"), false, v -> confirmDelete(t));
        delete.setTextColor(Ui.ERROR);
        delete.setEnabled(installingId == null);
        body.addView(delete);
    }

    private LinearLayout statusRow(Context ctx, String label, boolean ok, String note) {
        LinearLayout r = Ui.row(ctx);
        r.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6));
        TextView dot = Ui.text(ctx, ok ? "\u25CF" : "\u25CB", 16, ok ? Ui.PRIMARY : Ui.MUTED, false);
        dot.setPadding(0, 0, Ui.dp(ctx, 10), 0);
        r.addView(dot);
        r.addView(Ui.text(ctx, label, 15, Ui.TEXT, false), Ui.weight(1));
        if (note != null) r.addView(Ui.text(ctx, note, 13, Ui.MUTED, false));
        return r;
    }

    private LinearLayout packRow(Context ctx, Strings t, final PackInfo pack) {
        LinearLayout card = Ui.card(ctx);
        LinearLayout.LayoutParams clp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(ctx, 8);
        card.setLayoutParams(clp);
        LinearLayout top = Ui.row(ctx);
        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(ctx, pack.name, 16, Ui.TEXT, true));
        names.addView(Ui.text(ctx, t.get("versionSize", "v", pack.version, "size", mb(pack.totalBytes())), 13, Ui.MUTED, false));
        top.addView(names, Ui.weight(1));
        boolean busy = pack.id.equals(installingId);
        if (busy) {
            top.addView(Ui.button(ctx, t.get("cancel"), false, v -> {
                if (cancel != null) cancel.cancel();
            }));
        } else {
            Button b = Ui.button(ctx, t.get("download"), true, v -> install(pack));
            b.setEnabled(installingId == null);
            b.setAlpha(b.isEnabled() ? 1f : 0.4f);
            top.addView(b);
        }
        card.addView(top);
        if (busy) {
            ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress(totalBytes <= 0 ? 0 : (int) (1000L * doneBytes / totalBytes));
            card.addView(bar, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(Ui.text(ctx, doneBytes == 0 ? t.get("startingDownload")
                    : t.get("downloadProgress", "file", currentFile, "done", mb(doneBytes), "total", mb(totalBytes)), 12, Ui.MUTED, false));
        }
        return card;
    }

    private void install(final PackInfo pack) {
        installingId = pack.id;
        cancel = new PackManager.CancelToken();
        doneBytes = 0;
        totalBytes = pack.totalBytes();
        currentFile = "";
        installError = null;
        refresh();
        final Strings t = c.t();
        c.installPack(pack, (done, total, file) -> {
            doneBytes = done;
            totalBytes = total;
            currentFile = file;
            refresh();
        }, cancel, new Controller.Result<Void>() {
            @Override public void ok(Void v) {
                installingId = null;
                cancel = null;
                Toast.makeText(getContext(), t.get("installedOk", "name", pack.name), Toast.LENGTH_SHORT).show();
                refresh();
            }

            @Override public void fail(String key) {
                installingId = null;
                cancel = null;
                installError = text(key);
                refresh();
            }
        });
    }

    private void confirmDelete(final Strings t) {
        new AlertDialog.Builder(getContext())
                .setTitle(t.get("deleteTitle"))
                .setMessage(t.get("deleteBody"))
                .setNegativeButton(t.get("cancel"), null)
                .setPositiveButton(t.get("delete"), (d, w) -> c.deleteAllData())
                .show();
    }
}
