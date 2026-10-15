package com.pathfinder.app.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.pathfinder.app.core.Controller;
import com.pathfinder.app.core.Guidance;
import com.pathfinder.app.core.RouteResult;
import com.pathfinder.app.core.RouteStep;
import com.pathfinder.app.core.Strings;

/** Builders for the bottom card, the turn banner, the navigation bar and the status chips. */
final class Panels {
    private Panels() {}

    static LinearLayout.LayoutParams wide(Context c, int topMarginDp) {
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, topMarginDp);
        return lp;
    }

    /** Bottom card for every state except navigation. Returns null when there is nothing to show. */
    static View status(final Context ctx, final Controller c, final Runnable onManageData, final Runnable onClear, final Runnable onStart) {
        final Strings t = c.t();
        LinearLayout card = Ui.card(ctx);
        switch (c.phase) {
            case LOADING:
                busy(ctx, card, t.get("loadingData"));
                return card;
            case CALCULATING:
                busy(ctx, card, t.get("calculating"));
                return card;
            case NEEDS_DATA:
                card.addView(Ui.text(ctx, t.get("mapDataNotFound"), 17, Ui.TEXT, true));
                card.addView(Ui.text(ctx, t.get("copyFilesInto"), 14, Ui.TEXT, false), wide(ctx, 6));
                TextView path = Ui.text(ctx, c.data == null ? "" : c.data.dir.getPath(), 12, Ui.MUTED, false);
                path.setTextIsSelectable(true);
                card.addView(path, wide(ctx, 6));
                if (onManageData != null) card.addView(Ui.button(ctx, t.get("mapData"), false, v -> onManageData.run()), wide(ctx, 12));
                return card;
            case FATAL:
                card.addView(Ui.text(ctx, c.message() == null ? t.get("errUnknown") : c.message(), 15, Ui.ERROR, false));
                if (onManageData != null) card.addView(Ui.button(ctx, t.get("mapData"), false, v -> onManageData.run()), wide(ctx, 12));
                return card;
            case ROUTE_FAILED: {
                LinearLayout row = Ui.row(ctx);
                TextView msg = Ui.text(ctx, c.message() == null ? t.get("errNoRoute") : c.message(), 15, Ui.ERROR, false);
                row.addView(msg, Ui.weight(1));
                row.addView(Ui.button(ctx, t.get("dismiss"), false, v -> onClear.run()));
                card.addView(row);
                return card;
            }
            case ROUTE_READY: {
                RouteResult r = c.route;
                card.addView(Ui.text(ctx, t.get("pathFound", "time", Guidance.formatDuration(t, r.timeS)), 20, Ui.TEXT, true));
                String where = c.destination == null ? "" : c.destination.name;
                String line = t.get("distanceTo", "dist", Guidance.formatDistance(r.distanceM), "place", where);
                if (c.calcMillis >= 0) line += "  (" + t.get("calculatedIn", "ms", Long.toString(c.calcMillis)) + ")";
                card.addView(Ui.text(ctx, line, 14, Ui.MUTED, false));
                LinearLayout row = Ui.row(ctx);
                row.addView(Ui.button(ctx, t.get("start"), true, v -> onStart.run()), Ui.weight(1));
                row.addView(Ui.spacer(ctx, 8));
                row.addView(Ui.button(ctx, t.get("cancel"), false, v -> onClear.run()));
                card.addView(row, wide(ctx, 12));
                return card;
            }
            case ARRIVED: {
                LinearLayout row = Ui.row(ctx);
                row.addView(Icons.view(ctx, Icons.of(Icons.Kind.FLAG, Ui.PRIMARY), 24));
                TextView msg = Ui.text(ctx, t.get("arrived"), 18, Ui.TEXT, true);
                msg.setPadding(Ui.dp(ctx, 10), 0, 0, 0);
                row.addView(msg, Ui.weight(1));
                row.addView(Ui.button(ctx, t.get("done"), true, v -> onClear.run()));
                card.addView(row);
                return card;
            }
            case IDLE:
            case NAVIGATING:
            default:
                return null;
        }
    }

    private static void busy(Context ctx, LinearLayout card, String text) {
        card.addView(Ui.text(ctx, text, 16, Ui.TEXT, true));
        ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        card.addView(bar, wide(ctx, 10));
    }

    /** Top banner while navigating: next maneuver and distance to it. */
    static View navBanner(Context ctx, Controller c) {
        RouteResult r = c.route;
        if (r == null || r.steps.length == 0) return new View(ctx);
        Strings t = c.t();
        int idx = Math.min(r.steps.length - 1, Math.max(0, c.progress != null ? c.progress.nextStep : (r.steps.length > 1 ? 1 : 0)));
        RouteStep step = r.steps[idx];
        double dist = c.progress != null ? c.progress.toNextM : r.steps[0].distanceM;
        LinearLayout card = Ui.row(ctx);
        card.setBackground(Ui.rounded(ctx, Ui.PRIMARY, 12));
        card.setElevation(Ui.dp(ctx, 4));
        card.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 14), Ui.dp(ctx, 16), Ui.dp(ctx, 14));
        card.addView(Icons.view(ctx, Icons.forTurn(step.type, Ui.ON_PRIMARY), 48));
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(ctx, 14), 0, 0, 0);
        texts.addView(Ui.text(ctx, Guidance.formatDistance(dist), 26, Ui.ON_PRIMARY, true));
        texts.addView(Ui.text(ctx, Guidance.turnText(t, step.type), 17, Ui.ON_PRIMARY, false));
        card.addView(texts, Ui.weight(1));
        return card;
    }

    /** Bottom bar while navigating: progress, remaining time/distance, end button. */
    static View navBottom(Context ctx, Controller c, Runnable onEnd) {
        RouteResult r = c.route;
        if (r == null) return new View(ctx);
        Strings t = c.t();
        double total = r.distanceM <= 0 ? 1.0 : r.distanceM;
        double remaining = c.progress != null ? c.progress.remainingM : r.distanceM;
        double fraction = c.progress == null ? 0.0 : Math.min(1.0, Math.max(0.0, c.progress.alongM / total));
        LinearLayout card = Ui.card(ctx);
        ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress((int) Math.round(fraction * 1000));
        card.addView(bar, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = Ui.row(ctx);
        TextView etaText = Ui.text(ctx, etaLine(c), 17, Ui.TEXT, true);
        etaText.setTag("eta"); // MainActivity refreshes this text every second
        etaText.setOnClickListener(v -> c.speakEta()); // tap the time to hear it
        row.addView(etaText, Ui.weight(1));
        row.addView(Ui.button(ctx, t.get("end"), false, v -> onEnd.run()));
        card.addView(row, wide(ctx, 10));
        return card;
    }

    /** "12 min 34 s | 5.2 km | Arrive 14:32": time left from the user's own speed. */
    static String etaLine(Controller c) {
        Strings t = c.t();
        if (c.route == null) return "";
        if (c.rerouting) return t.get("rerouting");
        double remaining = c.progress != null ? c.progress.remainingM : c.route.distanceM;
        double eta = c.etaSeconds();
        String clock = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                .format(new java.util.Date(System.currentTimeMillis() + Math.round(eta * 1000.0)));
        return Guidance.formatCountdown(t, eta) + "  |  " + Guidance.formatDistance(remaining) + "  |  " + t.get("arriveAt", "time", clock);
    }

    /** Small status chips: offline mode, GPS problems, map-file problems. */
    static HorizontalScrollView chips(Context ctx, Controller c, String tilesProblemKey, boolean voiceMissing, Runnable onData, Runnable onGrant) {
        Strings t = c.t();
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row);
        row.addView(chip(ctx, c.usesCh ? t.get("offlineMode") + " | " + t.get("fastRouting") : t.get("offlineMode"), onData));
        switch (c.locationState) {
            case DENIED: row.addView(chip(ctx, t.get("locationNeeded"), onGrant)); break;
            case SERVICE_OFF: row.addView(chip(ctx, t.get("gpsOff"), null)); break;
            case READY: if (c.user == null) row.addView(chip(ctx, t.get("waitingGps"), null)); break;
            default: break;
        }
        if (tilesProblemKey != null) row.addView(chip(ctx, t.get(tilesProblemKey), null));
        else if (c.data != null && !c.data.hasTiles && !c.onlineTiles) row.addView(chip(ctx, t.get("noTiles"), null));
        if (voiceMissing) row.addView(chip(ctx, t.get("voiceMissingShort", "lang", c.prefs().lang().displayName), null));
        return scroll;
    }

    private static View chip(Context ctx, String text, final Runnable onTap) {
        TextView v = Ui.text(ctx, text + (onTap != null ? "  \u203A" : ""), 12, Ui.TEXT, false);
        v.setBackground(Ui.rounded(ctx, Ui.SURFACE, 16));
        v.setElevation(Ui.dp(ctx, 2));
        v.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 12), Ui.dp(ctx, 6));
        v.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(ctx, 6);
        lp.bottomMargin = Ui.dp(ctx, 4);
        v.setLayoutParams(lp);
        if (onTap != null) {
            v.setClickable(true);
            v.setOnClickListener(x -> onTap.run());
        }
        return v;
    }
}
