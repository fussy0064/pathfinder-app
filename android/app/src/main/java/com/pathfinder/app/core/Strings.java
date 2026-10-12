package com.pathfinder.app.core;

import java.util.HashMap;
import java.util.Map;

/**
 * English and Swahili text. Use: strings.get("start")  or  strings.get("pathFound", "time", "5 min").
 * tests/java checks that both tables have the same keys and the same {placeholders}.
 * The Swahili was written by the developer assistant, not a native speaker: please have it reviewed.
 */
public final class Strings {
    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, String> SW = new HashMap<>();

    static {
        EN.put("whereTo", "Where to?");
        EN.put("offlineMode", "Offline mode");
        EN.put("fastRouting", "fast routing");
        EN.put("locationNeeded", "Location permission needed");
        EN.put("gpsOff", "GPS is off");
        EN.put("waitingGps", "Waiting for GPS");
        EN.put("noTiles", "No map tiles");
        EN.put("loadingData", "Loading offline data...");
        EN.put("calculating", "CALCULATING PATH...");
        EN.put("pathFound", "PATH FOUND: {time}");
        EN.put("distanceTo", "{dist} to {place}");
        EN.put("calculatedIn", "calculated in {ms} ms");
        EN.put("start", "Start");
        EN.put("cancel", "Cancel");
        EN.put("end", "End");
        EN.put("done", "Done");
        EN.put("dismiss", "Dismiss");
        EN.put("arrived", "You have arrived");
        EN.put("rerouting", "Rerouting...");
        EN.put("mapData", "Map data");
        EN.put("mapDataNotFound", "Map data not found");
        EN.put("copyFilesInto", "Copy map_graph.bin (and optionally tiles.mbtiles and places.db) into:");
        EN.put("myLocation", "My location");
        EN.put("droppedPin", "Dropped pin");
        EN.put("errWaitingGpsPosition", "Waiting for your GPS position...");
        EN.put("errNoStart", "No road near your position");
        EN.put("errNoTarget", "No road near the destination");
        EN.put("errNoRoute", "No route found");
        EN.put("errBadArgs", "Bad request");
        EN.put("errFile", "Cannot open the map file");
        EN.put("errInvalidGraph", "The map file is invalid");
        EN.put("errInternal", "Internal error");
        EN.put("errLibrary", "The routing engine could not be loaded");
        EN.put("errUnknown", "Something went wrong");
        EN.put("errTilesVector", "Vector map tiles are not supported. Use raster (png/jpg) tiles.");
        EN.put("errTilesOpen", "Map tiles file could not be opened");
        EN.put("errPackUrl", "PACK_BASE_URL must be an https address");
        EN.put("typeMore", "Type at least 2 letters");
        EN.put("noMatch", "No places match \"{q}\"");
        EN.put("searchNoData", "Search data (places.db) is not installed.\nYou can still long-press the map to drop a pin.");
        EN.put("clear", "Clear");
        EN.put("askAi", "Ask AI");
        EN.put("aiWorking", "Asking the AI...");
        EN.put("aiNoResults", "The AI found no matching places nearby.");
        EN.put("aiNeedsSetup", "Turn on the AI assistant and add your key in Settings.");
        EN.put("aiErrKey", "The DeepSeek key was rejected. Check it in Settings.");
        EN.put("aiErrBalance", "The DeepSeek account has no balance left.");
        EN.put("aiErrRate", "Too many requests. Try again in a moment.");
        EN.put("aiErrNetwork", "No internet connection.");
        EN.put("aiErrServer", "The AI service had a problem. Try again later.");
        EN.put("aiErrUnderstand", "The AI answer could not be used. Try other words.");
        EN.put("aiErrNoKey", "No DeepSeek key saved.");
        EN.put("installed", "Installed");
        EN.put("roadGraph", "Road graph (routing)");
        EN.put("fastIndex", "Fast routing index");
        EN.put("active", "active");
        EN.put("notInstalledAstar", "not installed, using A*");
        EN.put("placeSearch", "Place search");
        EN.put("mapTiles", "Map tiles");
        EN.put("download", "Download");
        EN.put("noServer", "No download server is set up in this build.\nCopy the files into the folder above, or build the online app with -PpackBaseUrl=https://your-server/packs/");
        EN.put("retry", "Retry");
        EN.put("noPacks", "The server has no packs yet.");
        EN.put("installedOk", "{name} installed");
        EN.put("deleteAll", "Delete all map data");
        EN.put("deleteTitle", "Delete all map data?");
        EN.put("deleteBody", "Roads, search and map tiles will be removed from this phone. You can download them again.");
        EN.put("delete", "Delete");
        EN.put("startingDownload", "Starting...");
        EN.put("versionSize", "Version {v} | {size}");
        EN.put("downloadProgress", "{file}  {done} of {total}");
        EN.put("settings", "Settings");
        EN.put("language", "Language");
        EN.put("editPin", "Edit pin");
        EN.put("pickup", "Pickup");
        EN.put("dropPinTitle", "Destination pin");
        EN.put("pickupTitle", "Pickup point");
        EN.put("confirm", "Confirm");
        EN.put("searchingStreet", "Finding street...");
        EN.put("streetUnknown", "Street name not available");
        EN.put("theme", "Appearance");
        EN.put("themeSystem", "System");
        EN.put("themeLight", "Light");
        EN.put("themeDark", "Dark");
        EN.put("voiceGuidance", "Voice guidance");
        EN.put("voiceMissing", "No {lang} voice found on this phone. Install it in the phone text-to-speech settings. Text instructions still work.");
        EN.put("voiceMissingShort", "No {lang} voice");
        EN.put("headingUp", "Rotate the map with your direction");
        EN.put("testVoice", "Test voice");
        EN.put("testPhrase", "In 200 meters, turn left.");
        EN.put("aiAssistant", "AI assistant (DeepSeek)");
        EN.put("aiPrivacy", "When on, the words you type in AI search are sent over the internet to DeepSeek (api.deepseek.com). Your location, routes and map data are never sent. This needs the INTERNET permission in the app.");
        EN.put("save", "Save");
        EN.put("saved", "Saved");
        EN.put("removeKey", "Remove key");
        EN.put("testConnection", "Test connection");
        EN.put("connectionOk", "Connection works");
        EN.put("voiceOnTip", "Mute voice");
        EN.put("voiceOffTip", "Turn voice on");
        EN.put("northUpTip", "Map: north up (tap to rotate with direction)");
        EN.put("headingUpTip", "Map: direction up (tap for north up)");
        EN.put("vNavStart", "Navigation started.");
        EN.put("vInDist", "In {dist}, {action}.");
        EN.put("vArriveIn", "In {dist}, you will arrive at your destination.");
        EN.put("vArrived", "You have arrived at your destination.");
        EN.put("vRerouting", "Rerouting.");
        EN.put("vMeters", "{n} meters");
        EN.put("vKilometers", "{n} kilometers");
        EN.put("turnDepart", "Head out");
        EN.put("turnStraight", "Continue straight");
        EN.put("turnSlightLeft", "Bear left");
        EN.put("turnLeft", "Turn left");
        EN.put("turnSharpLeft", "Turn sharp left");
        EN.put("turnSlightRight", "Bear right");
        EN.put("turnRight", "Turn right");
        EN.put("turnSharpRight", "Turn sharp right");
        EN.put("turnUturn", "Make a U-turn");
        EN.put("turnArrive", "Arrive at destination");
        EN.put("lessThanMin", "<1 min");
        EN.put("minutes", "{n} min");
        EN.put("hoursMinutes", "{h} h {m} min");
        EN.put("back", "Back");
        EN.put("offlineAiNote", "AI search needs the online build of the app.");
        EN.put("onlineOnlyDownload", "Downloads need the online build of the app.");
        EN.put("keySavedMask", "Key saved");
        EN.put("apiKeyEntryHint", "Paste key here");
    }

    static {
        SW.put("whereTo", "Unaenda wapi?");
        SW.put("offlineMode", "Bila mtandao");
        SW.put("fastRouting", "njia za haraka");
        SW.put("locationNeeded", "Ruhusa ya eneo inahitajika");
        SW.put("gpsOff", "GPS imezimwa");
        SW.put("waitingGps", "Inasubiri GPS");
        SW.put("noTiles", "Hakuna vipande vya ramani");
        SW.put("loadingData", "Inapakia data ya ramani...");
        SW.put("calculating", "INAKOKOTOA NJIA...");
        SW.put("pathFound", "NJIA IMEPATIKANA: {time}");
        SW.put("distanceTo", "Umbali wa {dist} hadi {place}");
        SW.put("calculatedIn", "imekokotolewa kwa ms {ms}");
        SW.put("start", "Anza");
        SW.put("cancel", "Ghairi");
        SW.put("end", "Maliza");
        SW.put("done", "Sawa");
        SW.put("dismiss", "Funga");
        SW.put("arrived", "Umefika");
        SW.put("rerouting", "Inatafuta njia mpya...");
        SW.put("mapData", "Data ya ramani");
        SW.put("mapDataNotFound", "Data ya ramani haijapatikana");
        SW.put("copyFilesInto", "Nakili map_graph.bin (na hiari tiles.mbtiles na places.db) kwenye:");
        SW.put("myLocation", "Eneo langu");
        SW.put("droppedPin", "Alama niliyoweka");
        SW.put("errWaitingGpsPosition", "Inasubiri eneo lako la GPS...");
        SW.put("errNoStart", "Hakuna barabara karibu na ulipo");
        SW.put("errNoTarget", "Hakuna barabara karibu na unakoenda");
        SW.put("errNoRoute", "Njia haijapatikana");
        SW.put("errBadArgs", "Ombi si sahihi");
        SW.put("errFile", "Faili ya ramani haifunguki");
        SW.put("errInvalidGraph", "Faili ya ramani si sahihi");
        SW.put("errInternal", "Hitilafu ya ndani");
        SW.put("errLibrary", "Injini ya njia haikuweza kupakiwa");
        SW.put("errUnknown", "Kuna tatizo limetokea");
        SW.put("errTilesVector", "Vipande vya ramani vya vector havitumiki. Tumia vya raster (png/jpg).");
        SW.put("errTilesOpen", "Faili ya vipande vya ramani haifunguki");
        SW.put("errPackUrl", "PACK_BASE_URL lazima iwe anwani ya https");
        SW.put("typeMore", "Andika herufi angalau 2");
        SW.put("noMatch", "Hakuna mahali panapolingana na \"{q}\"");
        SW.put("searchNoData", "Data ya utafutaji (places.db) haijasakinishwa.\nBado unaweza kubonyeza ramani kwa muda mrefu kuweka alama.");
        SW.put("clear", "Futa");
        SW.put("askAi", "Uliza AI");
        SW.put("aiWorking", "Inauliza AI...");
        SW.put("aiNoResults", "AI haikupata mahali panapolingana karibu nawe.");
        SW.put("aiNeedsSetup", "Washa msaidizi wa AI na uweke ufunguo wako kwenye Mipangilio.");
        SW.put("aiErrKey", "Ufunguo wa DeepSeek umekataliwa. Uangalie kwenye Mipangilio.");
        SW.put("aiErrBalance", "Akaunti ya DeepSeek haina salio.");
        SW.put("aiErrRate", "Maombi ni mengi mno. Jaribu tena baada ya muda mfupi.");
        SW.put("aiErrNetwork", "Hakuna muunganisho wa intaneti.");
        SW.put("aiErrServer", "Huduma ya AI ina tatizo. Jaribu tena baadaye.");
        SW.put("aiErrUnderstand", "Jibu la AI halikuweza kutumika. Jaribu maneno mengine.");
        SW.put("aiErrNoKey", "Hakuna ufunguo wa DeepSeek uliohifadhiwa.");
        SW.put("installed", "Vilivyosakinishwa");
        SW.put("roadGraph", "Mtandao wa barabara (njia)");
        SW.put("fastIndex", "Faharasa ya njia za haraka");
        SW.put("active", "inatumika");
        SW.put("notInstalledAstar", "haijasakinishwa, inatumia A*");
        SW.put("placeSearch", "Utafutaji wa mahali");
        SW.put("mapTiles", "Vipande vya ramani");
        SW.put("download", "Pakua");
        SW.put("noServer", "Hakuna seva ya kupakua iliyowekwa kwenye toleo hili.\nNakili faili kwenye folda iliyo juu, au jenga programu ya mtandaoni kwa -PpackBaseUrl=https://seva-yako/packs/");
        SW.put("retry", "Jaribu tena");
        SW.put("noPacks", "Seva haina vifurushi bado.");
        SW.put("installedOk", "{name} imesakinishwa");
        SW.put("deleteAll", "Futa data yote ya ramani");
        SW.put("deleteTitle", "Kufuta data yote ya ramani?");
        SW.put("deleteBody", "Barabara, utafutaji na vipande vya ramani vitaondolewa kwenye simu hii. Unaweza kuvipakua tena.");
        SW.put("delete", "Futa");
        SW.put("startingDownload", "Inaanza...");
        SW.put("versionSize", "Toleo {v} | {size}");
        SW.put("downloadProgress", "{file}  {done} kati ya {total}");
        SW.put("settings", "Mipangilio");
        SW.put("language", "Lugha");
        SW.put("editPin", "Hariri alama");
        SW.put("pickup", "Kuchukua");
        SW.put("dropPinTitle", "Alama ya mwisho");
        SW.put("pickupTitle", "Mahali pa kuchukua");
        SW.put("confirm", "Thibitisha");
        SW.put("searchingStreet", "Inatafuta barabara...");
        SW.put("streetUnknown", "Jina la barabara halipatikani");
        SW.put("theme", "Mwonekano");
        SW.put("themeSystem", "Mfumo");
        SW.put("themeLight", "Mwanga");
        SW.put("themeDark", "Giza");
        SW.put("voiceGuidance", "Mwongozo wa sauti");
        SW.put("voiceMissing", "Hakuna sauti ya {lang} kwenye simu hii. Isakinishe kwenye mipangilio ya kusoma maandishi ya simu. Maelekezo ya maandishi bado yanafanya kazi.");
        SW.put("voiceMissingShort", "Hakuna sauti ya {lang}");
        SW.put("headingUp", "Zungusha ramani kufuata uelekeo wako");
        SW.put("testVoice", "Jaribu sauti");
        SW.put("testPhrase", "Baada ya mita 200, geuka kushoto.");
        SW.put("aiAssistant", "Msaidizi wa AI (DeepSeek)");
        SW.put("aiPrivacy", "Ikiwashwa, maneno unayoandika kwenye utafutaji wa AI hutumwa kupitia intaneti kwa DeepSeek (api.deepseek.com). Eneo lako, njia na data ya ramani havitumwi kamwe. Hii inahitaji ruhusa ya INTERNET kwenye programu.");
        SW.put("save", "Hifadhi");
        SW.put("saved", "Imehifadhiwa");
        SW.put("removeKey", "Ondoa ufunguo");
        SW.put("testConnection", "Jaribu muunganisho");
        SW.put("connectionOk", "Muunganisho unafanya kazi");
        SW.put("voiceOnTip", "Nyamazisha sauti");
        SW.put("voiceOffTip", "Washa sauti");
        SW.put("northUpTip", "Ramani: kaskazini juu (bonyeza kuzungusha kufuata uelekeo)");
        SW.put("headingUpTip", "Ramani: uelekeo juu (bonyeza kwa kaskazini juu)");
        SW.put("vNavStart", "Safari imeanza.");
        SW.put("vInDist", "Baada ya {dist}, {action}.");
        SW.put("vArriveIn", "Baada ya {dist}, utafika unakoenda.");
        SW.put("vArrived", "Umefika unakoenda.");
        SW.put("vRerouting", "Inatafuta njia mpya.");
        SW.put("vMeters", "mita {n}");
        SW.put("vKilometers", "kilomita {n}");
        SW.put("turnDepart", "Anza safari");
        SW.put("turnStraight", "Endelea mbele");
        SW.put("turnSlightLeft", "Elekea kushoto kidogo");
        SW.put("turnLeft", "Geuka kushoto");
        SW.put("turnSharpLeft", "Geuka kushoto kabisa");
        SW.put("turnSlightRight", "Elekea kulia kidogo");
        SW.put("turnRight", "Geuka kulia");
        SW.put("turnSharpRight", "Geuka kulia kabisa");
        SW.put("turnUturn", "Geuka urudi ulikotoka");
        SW.put("turnArrive", "Fika unakoenda");
        SW.put("lessThanMin", "chini ya dk 1");
        SW.put("minutes", "dk {n}");
        SW.put("hoursMinutes", "saa {h} dk {m}");
        SW.put("back", "Rudi");
        SW.put("offlineAiNote", "Utafutaji wa AI unahitaji toleo la mtandaoni la programu.");
        SW.put("onlineOnlyDownload", "Kupakua kunahitaji toleo la mtandaoni la programu.");
        SW.put("keySavedMask", "Ufunguo umehifadhiwa");
        SW.put("apiKeyEntryHint", "Bandika ufunguo hapa");
    }

    private final Lang lang;

    public Strings(Lang lang) {
        this.lang = lang == null ? Lang.EN : lang;
    }

    public Lang lang() {
        return lang;
    }

    /** Text for key; extra arguments are name/value pairs that replace {name}. Unknown key: the key itself. */
    public String get(String key, String... nameValuePairs) {
        String text = (lang == Lang.SW ? SW.get(key) : EN.get(key));
        if (text == null) text = EN.get(key);
        if (text == null) return key;
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            text = text.replace("{" + nameValuePairs[i] + "}", nameValuePairs[i + 1]);
        }
        return text;
    }

    /** Used by tests. */
    public static Map<String, String> table(Lang lang) {
        return lang == Lang.SW ? SW : EN;
    }
}
