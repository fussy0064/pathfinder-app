package com.pathfinder.app.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.pathfinder.app.core.Controller;
import com.pathfinder.app.core.Place;
import com.pathfinder.app.core.Strings;

import java.util.List;

/** Destination input: offline search as you type, plus optional "Ask AI". Full screen. */
final class SearchPanel extends FrameLayout {
    interface Host {
        void onPick(Place place);
    }

    private final Controller c;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EditText edit;
    private final LinearLayout list;
    private final LinearLayout topBar;
    private final View aiButton;
    private final View micButton;
    private Runnable pending;
    private Runnable onVoice;
    private int token;

    SearchPanel(Context ctx, Controller controller, Host host) {
        super(ctx);
        this.c = controller;
        this.host = host;
        setBackgroundColor(Ui.SURFACE);
        setClickable(true);
        setVisibility(GONE);

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = Ui.row(ctx);
        topBar.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8));
        topBar.addView(Icons.roundButton(ctx, Icons.Kind.BACK, Ui.TEXT, Ui.SURFACE, controller.t().get("back"), v -> close()));
        edit = new EditText(ctx);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        edit.setTextColor(Ui.TEXT);
        edit.setHintTextColor(Ui.MUTED);
        edit.setBackground(null);
        edit.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(200)});
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) { scheduleSearch(s.toString()); }
        });
        edit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                if (aiReady()) askAi();
                return true;
            }
            return false;
        });
        topBar.addView(edit, Ui.weight(1));
        micButton = Icons.roundButton(ctx, Icons.Kind.MIC, Ui.PRIMARY, Ui.SURFACE, controller.t().get("voiceSearch"), v -> {
            if (onVoice != null) onVoice.run();
        });
        micButton.setVisibility(GONE); // shown when the activity can start voice input
        topBar.addView(micButton);
        aiButton = Icons.roundButton(ctx, Icons.Kind.SPARKLE, Ui.PRIMARY, Ui.SURFACE, controller.t().get("askAi"), v -> askAi());
        topBar.addView(aiButton);
        topBar.addView(Icons.roundButton(ctx, Icons.Kind.CLOSE, Ui.TEXT, Ui.SURFACE, controller.t().get("clear"), v -> edit.setText("")));
        column.addView(topBar);

        ScrollView scroll = new ScrollView(ctx);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** The activity starts the phone's speech recognizer when the mic button is tapped. */
    void setOnVoice(Runnable r) {
        onVoice = r;
        micButton.setVisibility(r == null ? GONE : VISIBLE);
    }

    /** Words heard by the speech recognizer: put them in the box and search (AI search when it is on). */
    void setQueryFromVoice(String spoken) {
        if (spoken == null) return;
        String text = spoken.trim();
        if (text.isEmpty()) return;
        edit.setText(text.length() > 200 ? text.substring(0, 200) : text);
        edit.setSelection(edit.getText().length());
        if (aiReady()) askAi();
    }

    void setInsets(int top, int bottom) {
        setPadding(0, top, 0, bottom);
    }

    private boolean aiReady() {
        return c.prefs().aiOn() && c.prefs().hasApiKey();
    }

    void refresh() {
        Strings t = c.t();
        edit.setHint(t.get("whereTo"));
        aiButton.setVisibility(aiReady() ? VISIBLE : GONE);
    }

    void open() {
        edit.setText("");
        refresh();
        showMessage(c.t().get("typeMore"), false);
        setVisibility(VISIBLE);
        edit.requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
    }

    void close() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(edit.getWindowToken(), 0);
        handler.removeCallbacksAndMessages(null);
        token++;
        setVisibility(GONE);
    }

    private void scheduleSearch(final String text) {
        if (pending != null) handler.removeCallbacks(pending);
        pending = () -> runSearch(text);
        handler.postDelayed(pending, 200);
    }

    private void showMessage(String text, boolean error) {
        list.removeAllViews();
        TextView t = Ui.text(getContext(), text, 15, error ? Ui.ERROR : Ui.MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(getContext(), 24), Ui.dp(getContext(), 32), Ui.dp(getContext(), 24), Ui.dp(getContext(), 24));
        list.addView(t, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void runSearch(final String text) {
        final Strings t = c.t();
        final String q = text.trim();
        final int mine = ++token;
        if (q.length() < 2) {
            showMessage(t.get("typeMore"), false);
            return;
        }
        c.search(q, new Controller.Result<List<Place>>() {
            @Override public void ok(List<Place> found) {
                if (mine != token || getVisibility() != VISIBLE) return;
                if (found.isEmpty()) showMessage(t.get("noMatch", "q", q), false);
                else showResults(found);
            }

            @Override public void fail(String key) {
                if (mine != token || getVisibility() != VISIBLE) return;
                showMessage(t.get(key), true);
            }
        });
    }

    private void askAi() {
        final Strings t = c.t();
        final String q = edit.getText().toString().trim();
        if (q.length() < 3 || !aiReady()) return;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(edit.getWindowToken(), 0);
        final int mine = ++token;
        showMessage(t.get("aiWorking"), false);
        c.aiSearch(q, new Controller.Result<List<Place>>() {
            @Override public void ok(List<Place> found) {
                if (mine != token || getVisibility() != VISIBLE) return;
                showResults(found);
            }

            @Override public void fail(String key) {
                if (mine != token || getVisibility() != VISIBLE) return;
                showMessage(t.get(key), true);
            }
        });
    }

    private void showResults(List<Place> found) {
        Context ctx = getContext();
        list.removeAllViews();
        for (final Place p : found) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 12), Ui.dp(ctx, 20), Ui.dp(ctx, 12));
            TextView name = Ui.text(ctx, p.name, 16, Ui.TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name);
            row.addView(Ui.text(ctx, p.kindLabel(), 13, Ui.MUTED, false));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                close();
                host.onPick(p);
            });
            list.addView(row, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View divider = new View(ctx);
            divider.setBackgroundColor(Ui.OUTLINE);
            list.addView(divider, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }
}
