package com.voicebridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@CrossOrigin
public class TranslationController {

    private static final String APERTIUM_URL = "https://apertium.org/apy/translate";
    private static final String PAIRS_URL = "https://apertium.org/apy/listPairs";
    private static final String MYMEMORY_URL = "https://api.mymemory.translated.net/get";
    private static final String GOOGLE_URL = "https://translate.googleapis.com/translate_a/single";
    private static final long CACHE_MS = 3_600_000; // 1 hour

    private final RestClient client = RestClient.create();
    private final ObjectMapper mapper = new ObjectMapper();

    // Optional: set mymemory.email in application.properties for a bigger free quota
    @Value("${mymemory.email:}")
    private String myMemoryEmail;

    private volatile Set<String> pairsCache = Set.of();
    private volatile long pairsLoadedAt = 0;

    // ---------- Endpoints ----------

    @GetMapping("/api/pairs")
    public Object pairs() {
        try {
            return new TreeSet<>(installedPairs());
        } catch (Exception e) {
            return error(502, "Could not load Apertium language pairs.");
        }
    }

    @GetMapping("/api/translate")
    public Map<String, Object> translate(
            @RequestParam String text,
            @RequestParam String target,
            @RequestParam(defaultValue = "en") String source) {

        if (text == null || text.isBlank()) {
            return error(400, "Please enter some text.");
        }
        if (target == null || target.isBlank()
                || source == null || source.isBlank()) {
            return error(400, "Please select valid languages.");
        }

        text = text.trim();
        source = normalizeLang(source);
        target = normalizeLang(target);

        if (text.length() > 2000) {
            return error(400, "Text must not exceed 2000 characters.");
        }
        if (source == null || target == null) {
            return error(400, "Invalid language code.");
        }
        if (source.equals(target)) {
            return success(text, "None");
        }

        List<String> problems = new ArrayList<>();

        // 1) Apertium, only if the pair is installed
        if (isApertiumSupported(source, target)) {
            try {
                String r = viaApertium(text, source, target);
                if (r != null && !r.isBlank()) {
                    System.out.println("[translate] Apertium OK " + source + "->" + target);
                    return success(r, "Apertium");
                }
                problems.add("Apertium: empty result");
            } catch (Exception e) {
                problems.add("Apertium: " + e.getMessage());
            }
        }

        // 2) MyMemory
        try {
            String r = viaMyMemory(text, source, target);
            if (r != null && !r.isBlank() && !r.trim().equalsIgnoreCase(text)) {
                System.out.println("[translate] MyMemory OK " + source + "->" + target);
                return success(r, "MyMemory");
            }
            problems.add("MyMemory: returned no usable translation");
        } catch (Exception e) {
            problems.add("MyMemory: " + e.getMessage());
        }

        // 3) Google web endpoint (unofficial, no key needed)
        try {
            String r = viaGoogle(text, source, target);
            if (r != null && !r.isBlank()) {
                System.out.println("[translate] Google OK " + source + "->" + target);
                return success(r, "Google");
            }
            problems.add("Google: empty result");
        } catch (Exception e) {
            problems.add("Google: " + e.getMessage());
        }

        String details = String.join(" | ", problems);
        System.err.println("[translate] ALL FAILED " + source + "->" + target + " :: " + details);
        return error(502, "Translation failed. " + details);
    }

    // ---------- Language code handling ----------

    /** Accepts en, hi, zh-CN, zh_cn, pt-BR, etc. Returns null if invalid. */
    private String normalizeLang(String code) {
        String c = code.trim().replace('_', '-');
        if (!c.matches("(?i)[a-z]{2,3}(-[a-z0-9]{2,4})?")) return null;
        String[] p = c.split("-");
        return p.length == 1
                ? p[0].toLowerCase()
                : p[0].toLowerCase() + "-" + p[1].toUpperCase();
    }

    // ---------- Apertium ----------

    private boolean isApertiumSupported(String source, String target) {
        try {
            Set<String> pairs = installedPairs();
            return pairs.contains(source + "|" + target)
                    || pairs.contains(iso3(source) + "|" + iso3(target));
        } catch (Exception e) {
            return false; // can't check, so use the fallbacks
        }
    }

    private String viaApertium(String text, String source, String target) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", text);
        params.put("langpair", source + "|" + target);
        params.put("markUnknown", "no");

        Map<?, ?> response = client.get().uri(buildUri(APERTIUM_URL, params))
                .retrieve().body(Map.class);
        if (response == null) return null;

        Object status = response.get("responseStatus");
        if (!(status instanceof Number) || ((Number) status).intValue() != 200) return null;

        Object data = response.get("responseData");
        if (!(data instanceof Map)) return null;

        Object translated = ((Map<?, ?>) data).get("translatedText");
        return translated == null ? null : translated.toString();
    }

    private Set<String> installedPairs() {
        if (!pairsCache.isEmpty()
                && System.currentTimeMillis() - pairsLoadedAt < CACHE_MS) {
            return pairsCache;
        }

        Map<?, ?> res = client.get().uri(PAIRS_URL).retrieve().body(Map.class);
        List<?> list = (List<?>) res.get("responseData");

        pairsCache = list.stream()
                .map(o -> (Map<?, ?>) o)
                .map(m -> m.get("sourceLanguage") + "|" + m.get("targetLanguage"))
                .collect(Collectors.toSet());
        pairsLoadedAt = System.currentTimeMillis();
        return pairsCache;
    }

    private String iso3(String code) {
        try {
            return Locale.forLanguageTag(code).getISO3Language();
        } catch (Exception e) {
            return code;
        }
    }

    // ---------- MyMemory ----------

    private String viaMyMemory(String text, String source, String target) {
        StringBuilder out = new StringBuilder();

        for (String chunk : splitIntoChunks(text, 450)) { // MyMemory limit is ~500 bytes
            Map<String, String> params = new LinkedHashMap<>();
            params.put("q", chunk);
            params.put("langpair", source + "|" + target);
            if (myMemoryEmail != null && !myMemoryEmail.isBlank()) {
                params.put("de", myMemoryEmail.trim());
            }

            Map<?, ?> response = client.get().uri(buildUri(MYMEMORY_URL, params))
                    .retrieve().body(Map.class);
            if (response == null) throw new IllegalStateException("empty response");

            Object status = response.get("responseStatus");
            int code = 0;
            try { code = Integer.parseInt(String.valueOf(status)); } catch (Exception ignored) {}
            if (code == 429) throw new IllegalStateException("daily quota reached");
            if (code != 200) {
                throw new IllegalStateException("status " + code + " " + response.get("responseDetails"));
            }

            Object data = response.get("responseData");
            if (!(data instanceof Map)) throw new IllegalStateException("bad response");

            Object translated = ((Map<?, ?>) data).get("translatedText");
            if (translated == null || translated.toString().isBlank()) {
                throw new IllegalStateException("no translation returned");
            }

            String t = HtmlUtils.htmlUnescape(translated.toString());
            if (t.toUpperCase().startsWith("MYMEMORY WARNING")) {
                throw new IllegalStateException("quota warning");
            }

            if (out.length() > 0) out.append(' ');
            out.append(t);
        }
        return out.toString();
    }

    // ---------- Google (unofficial web endpoint) ----------

    private String viaGoogle(String text, String source, String target) throws Exception {
        StringBuilder out = new StringBuilder();

        for (String chunk : splitIntoChunks(text, 1000)) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("client", "gtx");
            params.put("sl", source);
            params.put("tl", target);
            params.put("dt", "t");
            params.put("q", chunk);

            String body = client.get().uri(buildUri(GOOGLE_URL, params))
                    .header("User-Agent", "Mozilla/5.0")
                    .retrieve().body(String.class);

            JsonNode segments = mapper.readTree(body).get(0);
            if (segments == null || !segments.isArray()) {
                throw new IllegalStateException("unexpected response");
            }

            StringBuilder part = new StringBuilder();
            for (JsonNode seg : segments) {
                if (seg.isArray() && seg.size() > 0 && seg.get(0).isTextual()) {
                    part.append(seg.get(0).asText());
                }
            }
            if (out.length() > 0) out.append(' ');
            out.append(part);
        }
        return out.toString();
    }

    // ---------- URL + text helpers ----------

    /** Builds a URI with every parameter value strictly encoded (handles + & = | and Telugu text). */
    private URI buildUri(String base, Map<String, String> params) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(base);
        Map<String, String> vars = new HashMap<>();
        int i = 0;
        for (Map.Entry<String, String> e : params.entrySet()) {
            String key = "v" + (i++);
            b.queryParam(e.getKey(), "{" + key + "}");
            vars.put(key, e.getValue());
        }
        return b.encode().buildAndExpand(vars).toUri();
    }

    /** Splits text at sentence ends, packing sentences into chunks under maxBytes (UTF-8). */
    private List<String> splitIntoChunks(String text, int maxBytes) {
        String[] sentences = text.split("(?<=[.!?।])\\s+");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String s : sentences) {
            if (byteLen(s) > maxBytes) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                chunks.addAll(hardSplit(s, maxBytes));
                continue;
            }
            String candidate = current.length() == 0 ? s : current + " " + s;
            if (byteLen(candidate) > maxBytes) {
                chunks.add(current.toString());
                current = new StringBuilder(s);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
    }

    /** Last resort for a single huge sentence: split by words, then by characters. */
    private List<String> hardSplit(String s, int maxBytes) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            String candidate = cur.length() == 0 ? word : cur + " " + word;
            if (byteLen(candidate) <= maxBytes) {
                cur = new StringBuilder(candidate);
                continue;
            }
            if (cur.length() > 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            }
            if (byteLen(word) <= maxBytes) {
                cur.append(word);
            } else {
                StringBuilder piece = new StringBuilder();
                for (int i = 0; i < word.length(); ) {
                    int cp = word.codePointAt(i);
                    String ch = new String(Character.toChars(cp));
                    if (byteLen(piece + ch) > maxBytes) {
                        parts.add(piece.toString());
                        piece.setLength(0);
                    }
                    piece.append(ch);
                    i += Character.charCount(cp);
                }
                cur.append(piece);
            }
        }
        if (cur.length() > 0) parts.add(cur.toString());
        return parts;
    }

    private int byteLen(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ---------- Response helpers ----------

    private Map<String, Object> success(String translated, String provider) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("translatedText", translated);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("responseStatus", 200);
        result.put("responseData", data);
        result.put("provider", provider);
        return result;
    }

    private Map<String, Object> error(int status, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("responseStatus", status);
        result.put("responseDetails", message);
        return result;
    }
}