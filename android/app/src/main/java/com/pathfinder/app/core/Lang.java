package com.pathfinder.app.core;

public enum Lang {
    EN("en", new String[] {"en-US", "en-GB", "en"}, "English"),
    SW("sw", new String[] {"sw-TZ", "sw-KE", "sw"}, "Kiswahili");

    public final String tag;
    /** Locale tags to try for the text-to-speech voice, best first. */
    public final String[] voiceTags;
    public final String displayName;

    Lang(String tag, String[] voiceTags, String displayName) {
        this.tag = tag;
        this.voiceTags = voiceTags;
        this.displayName = displayName;
    }

    public static Lang fromTag(String tag) {
        return "sw".equals(tag) ? SW : EN;
    }
}
