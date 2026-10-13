package com.pathfinder.app.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.pathfinder.app.core.Controller;
import com.pathfinder.app.core.Lang;
import com.pathfinder.app.core.Strings;

/** Language, voice guidance, map rotation and the optional DeepSeek AI assistant. Full screen. */
final class SettingsPanel extends FrameLayout {
    private final Controller c;
    private final TextView title, languageLabel, themeLabel, voiceWarning, privacy, offlineNote;
    private final Button english, swahili, themeSystem, themeLight, themeDark, testVoice, save, test, remove;
    private Runnable onThemeChanged;
    private final Switch voice, headingUp, ai;
    private final EditText key;
    private final LinearLayout aiBox;
    private final boolean online;

    SettingsPanel(Context ctx, Controller controller) {
        super(ctx);
        this.c = controller;
        this.online = ctx.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED;
        setBackgroundColor(Ui.SURFACE);
        setClickable(true);
        setVisibility(GONE);
        setTag("insets");

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
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), Ui.dp(ctx, 32));
        scroll.addView(body);

        languageLabel = Ui.text(ctx, "", 16, Ui.TEXT, true);
        body.addView(languageLabel);
        LinearLayout langRow = Ui.row(ctx);
        english = Ui.button(ctx, Lang.EN.displayName, false, v -> c.setLanguage(Lang.EN));
        swahili = Ui.button(ctx, Lang.SW.displayName, false, v -> c.setLanguage(Lang.SW));
        langRow.addView(english, Ui.weight(1));
        langRow.addView(Ui.spacer(ctx, 8));
        langRow.addView(swahili, Ui.weight(1));
        body.addView(langRow, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(divider(ctx));

        themeLabel = Ui.text(ctx, "", 16, Ui.TEXT, true);
        body.addView(themeLabel);
        LinearLayout themeRow = Ui.row(ctx);
        themeSystem = Ui.button(ctx, "", false, v -> chooseTheme(Ui.THEME_SYSTEM));
        themeLight = Ui.button(ctx, "", false, v -> chooseTheme(Ui.THEME_LIGHT));
        themeDark = Ui.button(ctx, "", false, v -> chooseTheme(Ui.THEME_DARK));
        themeRow.addView(themeSystem, Ui.weight(1));
        themeRow.addView(Ui.spacer(ctx, 8));
        themeRow.addView(themeLight, Ui.weight(1));
        themeRow.addView(Ui.spacer(ctx, 8));
        themeRow.addView(themeDark, Ui.weight(1));
        body.addView(themeRow, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(divider(ctx));

        voice = new Switch(ctx);
        voice.setTextSize(16);
        voice.setOnCheckedChangeListener((b, on) -> {
            if (on != c.prefs().voiceOn()) c.setVoiceOn(on);
        });
        body.addView(voice, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        voiceWarning = Ui.text(ctx, "", 14, Ui.ERROR, false);
        body.addView(voiceWarning);
        testVoice = Ui.button(ctx, "", false, v -> c.testVoice());
        body.addView(testVoice, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        headingUp = new Switch(ctx);
        headingUp.setTextSize(16);
        headingUp.setPadding(0, Ui.dp(ctx, 12), 0, 0);
        headingUp.setOnCheckedChangeListener((b, on) -> {
            if (on != c.prefs().headingUp()) c.setHeadingUp(on);
        });
        body.addView(headingUp, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(divider(ctx));

        ai = new Switch(ctx);
        ai.setTextSize(16);
        ai.setOnCheckedChangeListener((b, on) -> {
            if (on != c.prefs().aiOn()) c.setAiOn(on);
        });
        body.addView(ai, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        privacy = Ui.text(ctx, "", 13, Ui.MUTED, false);
        body.addView(privacy);
        offlineNote = Ui.text(ctx, "", 14, Ui.ERROR, false);
        body.addView(offlineNote);

        aiBox = new LinearLayout(ctx);
        aiBox.setOrientation(LinearLayout.VERTICAL);
        aiBox.setPadding(0, Ui.dp(ctx, 12), 0, 0);
        key = new EditText(ctx);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        key.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(200)});
        aiBox.addView(key, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout buttons = Ui.row(ctx);
        save = Ui.button(ctx, "", true, v -> {
            boolean ok = c.saveApiKey(key.getText().toString());
            if (ok) key.setText("");
            toast(c.t().get(ok ? "saved" : "keyNotSaved"));
        });
        test = Ui.button(ctx, "", false, v -> runTest());
        remove = Ui.button(ctx, "", false, v -> c.removeApiKey());
        buttons.addView(save);
        buttons.addView(Ui.spacer(ctx, 8));
        buttons.addView(test);
        buttons.addView(Ui.spacer(ctx, 8));
        buttons.addView(remove);
        aiBox.addView(buttons);
        body.addView(aiBox);
    }

    private View divider(Context ctx) {
        View d = new View(ctx);
        d.setBackgroundColor(Ui.OUTLINE);
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = Ui.dp(ctx, 16);
        lp.bottomMargin = Ui.dp(ctx, 16);
        d.setLayoutParams(lp);
        return d;
    }

    void setOnThemeChanged(Runnable r) {
        onThemeChanged = r;
    }

    private void chooseTheme(int mode) {
        Ui.setThemeMode(getContext(), mode);
        refresh();
        if (onThemeChanged != null) onThemeChanged.run();
    }

    private void toast(String text) {
        Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
    }

    private void runTest() {
        final Strings t = c.t();
        c.testAi(new Controller.Result<Void>() {
            @Override public void ok(Void v) { toast(t.get("connectionOk")); }
            @Override public void fail(String k) { toast(t.get(k)); }
        });
    }

    void setInsets(int top, int bottom) {
        setPadding(0, top, 0, bottom);
    }

    void open() {
        refresh();
        setVisibility(VISIBLE);
    }

    void refresh() {
        Strings t = c.t();
        boolean sw = c.prefs().lang() == Lang.SW;
        title.setText(t.get("settings"));
        languageLabel.setText(t.get("language"));
        restyle(english, !sw);
        restyle(swahili, sw);
        int mode = Ui.themeMode(getContext());
        themeLabel.setText(t.get("theme"));
        themeSystem.setText(t.get("themeSystem"));
        themeLight.setText(t.get("themeLight"));
        themeDark.setText(t.get("themeDark"));
        restyle(themeSystem, mode == Ui.THEME_SYSTEM);
        restyle(themeLight, mode == Ui.THEME_LIGHT);
        restyle(themeDark, mode == Ui.THEME_DARK);
        voice.setText(t.get("voiceGuidance"));
        voice.setChecked(c.prefs().voiceOn());
        boolean missing = c.prefs().voiceOn() && !c.voiceAvailable;
        voiceWarning.setVisibility(missing ? VISIBLE : GONE);
        voiceWarning.setText(t.get("voiceMissing", "lang", c.prefs().lang().displayName));
        testVoice.setText(t.get("testVoice"));
        testVoice.setEnabled(c.prefs().voiceOn() && c.voiceAvailable);
        testVoice.setAlpha(testVoice.isEnabled() ? 1f : 0.4f);
        headingUp.setText(t.get("headingUp"));
        headingUp.setChecked(c.prefs().headingUp());
        ai.setText(t.get("aiAssistant"));
        ai.setChecked(c.prefs().aiOn() && online);
        ai.setEnabled(online);
        privacy.setText(t.get("aiPrivacy"));
        offlineNote.setText(t.get("offlineAiNote"));
        offlineNote.setVisibility(online ? GONE : VISIBLE);
        aiBox.setVisibility(online && c.prefs().aiOn() ? VISIBLE : GONE);
        key.setHint(c.prefs().hasApiKey() ? t.get("keySavedMask") + " *****" : t.get("apiKeyEntryHint"));
        save.setText(t.get("save"));
        test.setText(t.get("testConnection"));
        test.setEnabled(c.prefs().hasApiKey());
        test.setAlpha(test.isEnabled() ? 1f : 0.4f);
        remove.setText(t.get("removeKey"));
        remove.setEnabled(c.prefs().hasApiKey());
        remove.setAlpha(remove.isEnabled() ? 1f : 0.4f);
        gravityFix();
    }

    private void gravityFix() {
        voiceWarning.setGravity(Gravity.START);
    }

    private void restyle(Button b, boolean selected) {
        Context ctx = getContext();
        b.setTextColor(selected ? Ui.ON_PRIMARY : Ui.PRIMARY);
        b.setBackground(selected ? Ui.rounded(ctx, Ui.PRIMARY, 20) : Ui.outlined(ctx, Ui.SURFACE, Ui.PRIMARY, 20));
    }
}
