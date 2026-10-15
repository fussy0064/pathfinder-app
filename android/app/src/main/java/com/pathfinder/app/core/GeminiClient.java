package com.pathfinder.app.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Optional AI search with Google Gemini (https://generativelanguage.googleapis.com, generateContent API).
 *
 * Privacy and safety rules:
 *  - Only the words the user typed are sent. Never location, routes or map data.
 *  - The answer is NOT trusted: the model may only pick "kinds" from a fixed list; "names" are stripped
 *    to plain letters/digits. The offline database does the real search (parameterized SQL).
 *  - https only, host fixed to generativelanguage.googleapis.com, no redirects, short timeouts, small size limits.
 *  - The API key is only put in the x-goog-api-key header (never in the URL). It is never logged or put in error messages.
 */
public final class GeminiClient implements AiService {
    public static final int MAX_INPUT = 200;

    /** Place kinds the model may choose from (same codes as places.db "kind"). */
    public static final Map<String, String> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("amenity:pharmacy", "pharmacy, chemist, duka la dawa");
        KINDS.put("amenity:hospital", "hospital, hospitali");
        KINDS.put("amenity:clinic", "clinic, zahanati, kituo cha afya");
        KINDS.put("amenity:doctors", "doctor, daktari");
        KINDS.put("amenity:dentist", "dentist, daktari wa meno");
        KINDS.put("amenity:fuel", "fuel station, petrol, kituo cha mafuta");
        KINDS.put("amenity:restaurant", "restaurant, mgahawa");
        KINDS.put("amenity:cafe", "cafe, coffee, kahawa");
        KINDS.put("amenity:fast_food", "fast food, chakula cha haraka");
        KINDS.put("amenity:bank", "bank, benki");
        KINDS.put("amenity:atm", "ATM, cash machine");
        KINDS.put("amenity:school", "school, shule");
        KINDS.put("amenity:university", "university, chuo kikuu");
        KINDS.put("amenity:police", "police, polisi");
        KINDS.put("amenity:place_of_worship", "church, mosque, temple, kanisa, msikiti");
        KINDS.put("amenity:marketplace", "market, soko");
        KINDS.put("amenity:bus_station", "bus station, stendi ya basi");
        KINDS.put("amenity:toilets", "toilet, choo");
        KINDS.put("amenity:post_office", "post office, posta");
        KINDS.put("shop:supermarket", "supermarket, duka kubwa");
        KINDS.put("shop:convenience", "small shop, kiosk, duka");
        KINDS.put("shop:bakery", "bakery, mkate");
        KINDS.put("tourism:hotel", "hotel");
        KINDS.put("tourism:guest_house", "guest house, nyumba ya wageni");
        KINDS.put("tourism:attraction", "tourist attraction, kivutio");
        KINDS.put("tourism:museum", "museum, jumba la makumbusho");
        KINDS.put("leisure:park", "park, bustani");
        KINDS.put("railway:station", "train station, stesheni ya treni");
        KINDS.put("aeroway:aerodrome", "airport, uwanja wa ndege");
    }

    private static final Pattern NOT_ALLOWED = Pattern.compile("[^\\p{L}\\p{N} '.\\-]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final int TIMEOUT_MS = 20000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private static final String HOST = "generativelanguage.googleapis.com";
    /** The first name is tried first. If Google says "model not found" (404), the next one is tried. */
    private static final String[] MODELS = {"gemini-3.8-flash", "gemini-flash-latest"};

    private final String baseUrl; // ends with "/models/"
    private final String[] models;

    public GeminiClient() {
        this("https://" + HOST + "/v1beta/models/", MODELS, false);
    }

    /** allowInsecureForTests lets unit tests talk to a local server. Never use it in the app. */
    public GeminiClient(String baseUrl, String[] models, boolean allowInsecureForTests) {
        URL u;
        try {
            u = java.net.URI.create(baseUrl).toURL();
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("bad endpoint");
        }
        boolean official = u.getHost().equals(HOST) && u.getProtocol().equals("https");
        if (!official && !allowInsecureForTests) throw new IllegalArgumentException("Only https://" + HOST + " is allowed");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.models = models.clone();
    }

    /** Keeps letters, digits, space and - ' . only; lower-case; 2..40 chars. Returns "" if nothing usable. */
    public static String sanitizeTerm(String raw) {
        String s = NOT_ALLOWED.matcher(raw).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
        if (s.length() < 2) return "";
        return s.length() > 40 ? s.substring(0, 40).trim() : s;
    }

    /** Parses the model answer. Throws AiException("aiErrUnderstand") when it is not usable. */
    public static AiIntent parseIntent(String content) throws AiException {
        if (content == null || content.isEmpty() || content.length() > 4000) throw new AiException("aiErrUnderstand");
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) throw new AiException("aiErrUnderstand");
        Object doc;
        try {
            doc = MiniJson.parse(content.substring(start, end + 1));
        } catch (MiniJson.JsonException e) {
            throw new AiException("aiErrUnderstand");
        }
        if (!(doc instanceof Map)) throw new AiException("aiErrUnderstand");
        Map<?, ?> map = (Map<?, ?>) doc;
        List<String> kinds = new ArrayList<>();
        Object rawKinds = map.get("kinds");
        if (rawKinds instanceof List) {
            for (Object k : (List<?>) rawKinds) {
                if (k instanceof String && KINDS.containsKey(k) && !kinds.contains(k) && kinds.size() < 3) kinds.add((String) k);
            }
        }
        List<String> names = new ArrayList<>();
        Object rawNames = map.get("names");
        if (rawNames instanceof List) {
            for (Object n : (List<?>) rawNames) {
                if (!(n instanceof String)) continue;
                String clean = sanitizeTerm((String) n);
                if (!clean.isEmpty() && !names.contains(clean) && names.size() < 3) names.add(clean);
            }
        }
        AiIntent intent = new AiIntent(kinds, names);
        if (intent.isEmpty()) throw new AiException("aiErrUnderstand");
        return intent;
    }

    public static String buildSystemPrompt() {
        StringBuilder list = new StringBuilder();
        for (Map.Entry<String, String> e : KINDS.entrySet()) {
            list.append(e.getKey()).append(" (").append(e.getValue()).append(")\n");
        }
        return "You are a search helper for an offline map app. The user writes in English or Swahili.\n"
                + "Reply with ONLY a JSON object and nothing else: {\"kinds\": [...], \"names\": [...]}\n"
                + "\"kinds\": up to 3 codes chosen ONLY from this list, spelled exactly:\n" + list
                + "\"names\": up to 3 short place-name keywords the user mentioned (proper names as written on a map), or [].\n"
                + "If the request is not about finding a place, reply {\"kinds\": [], \"names\": []}.";
    }

    /** Blocking network call: run it on a background thread. */
    @Override
    public AiIntent interpret(String apiKey, String userText) throws AiException {
        String text = userText == null ? "" : userText.trim();
        if (text.isEmpty()) throw new AiException("aiErrUnderstand");
        if (text.length() > MAX_INPUT) text = text.substring(0, MAX_INPUT);
        String body = "{\"systemInstruction\":{\"parts\":[{\"text\":" + MiniJson.quote(buildSystemPrompt()) + "}]}"
                + ",\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":" + MiniJson.quote(text) + "}]}]"
                // room for the model's own thinking, so the short JSON answer is never cut off
                + ",\"generationConfig\":{\"temperature\":0,\"maxOutputTokens\":1024,\"responseMimeType\":\"application/json\"}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        AiException last = new AiException("aiErrModel");
        for (String model : models) {
            try {
                return call(apiKey, model, bytes);
            } catch (AiException e) {
                if (!"aiErrModel".equals(e.getMessage())) throw e; // only "model not found" tries the next name
                last = e;
            }
        }
        throw last;
    }

    private AiIntent call(String apiKey, String model, byte[] bytes) throws AiException {
        HttpURLConnection c = null;
        try {
            URL endpoint = java.net.URI.create(baseUrl + model + ":generateContent").toURL();
            c = (HttpURLConnection) endpoint.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(bytes.length);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", apiKey);
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                String err = "";
                try {
                    err = readLimited(c.getErrorStream() == null ? c.getInputStream() : c.getErrorStream());
                } catch (IOException | AiException ignored) {
                    // the status code alone is enough
                }
                throw new AiException(statusKey(code, err));
            }
            Object doc = MiniJson.parse(readLimited(c.getInputStream()));
            String content = firstText(doc);
            if (content == null) throw new AiException("aiErrUnderstand");
            return parseIntent(content);
        } catch (AiException e) {
            throw e;
        } catch (MiniJson.JsonException e) {
            throw new AiException("aiErrUnderstand");
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            throw new AiException("aiErrNetwork"); // SecurityException = the app has no INTERNET permission
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The answer text: candidates[0].content.parts[*].text, skipping the model's "thought" parts. */
    static String firstText(Object doc) {
        if (!(doc instanceof Map)) return null;
        Object candidates = ((Map<?, ?>) doc).get("candidates");
        if (!(candidates instanceof List) || ((List<?>) candidates).isEmpty()) return null;
        Object first = ((List<?>) candidates).get(0);
        if (!(first instanceof Map)) return null;
        Object content = ((Map<?, ?>) first).get("content");
        if (!(content instanceof Map)) return null;
        Object parts = ((Map<?, ?>) content).get("parts");
        if (!(parts instanceof List)) return null;
        StringBuilder out = new StringBuilder();
        for (Object part : (List<?>) parts) {
            if (!(part instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) part;
            if (Boolean.TRUE.equals(m.get("thought"))) continue;
            if (m.get("text") instanceof String) out.append((String) m.get("text"));
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static String readLimited(InputStream in) throws IOException, AiException {
        try (InputStream is = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > MAX_RESPONSE_BYTES) throw new AiException("aiErrServer");
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String statusKey(int code, String errorBody) {
        String e = errorBody == null ? "" : errorBody.toLowerCase(Locale.ROOT);
        if (code == 401 || code == 403) return "aiErrKey";
        if (code == 400 && (e.contains("api_key_invalid") || e.contains("api key not valid"))) return "aiErrKey";
        if (code == 404) return "aiErrModel";
        if (code == 429) return "aiErrRate";
        return "aiErrServer";
    }
}
