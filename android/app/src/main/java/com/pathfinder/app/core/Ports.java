package com.pathfinder.app.core;

import java.util.List;

/** Small interfaces the Controller needs from the outside (Android provides them; tests use fakes). */
public final class Ports {
    private Ports() {}

    public interface MainThread {
        void post(Runnable r);
    }

    public interface Prefs {
        Lang lang();
        boolean voiceOn();
        boolean headingUp();
        boolean aiOn();
        boolean hasApiKey();
        String readApiKey();
        void setLang(Lang lang);
        void setVoiceOn(boolean on);
        void setHeadingUp(boolean on);
        void setAiOn(boolean on);
        void saveApiKey(String key);
        void removeApiKey();
    }

    public interface Voice {
        /** Picks the first locale tag the phone can speak. Returns true when one was found. */
        boolean configure(String[] localeTags);
        void speak(String text);
        void stop();
    }

    public interface PlacesIndex {
        List<Place> search(String query, int limit);
        List<Place> byKinds(List<String> kinds, boolean hasPosition, double lat, double lon, int limit);
        void close();
    }

    public interface PlacesOpener {
        PlacesIndex open(String path) throws Exception;
    }
}
