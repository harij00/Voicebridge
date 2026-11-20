
package com.voicebridge;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@CrossOrigin
public class TranslationController {

    private static final String API_URL =
            "https://apertium.org/apy/translate";

    private final RestClient client = RestClient.create();

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
        source = source.trim().toLowerCase();
        target = target.trim().toLowerCase();

        if (text.length() > 2000) {
            return error(400, "Text must not exceed 2000 characters.");
        }

        if (!source.matches("[a-z]{2,3}(_[a-zA-Z0-9]+)?")
                || !target.matches("[a-z]{2,3}(_[a-zA-Z0-9]+)?")) {
            return error(400, "Invalid language code.");
        }

        try {
            URI uri = UriComponentsBuilder
                    .fromUriString(API_URL)
                    .queryParam("q", text)
                    .queryParam("langpair", source + "|" + target)
                    .queryParam("markUnknown", "no")
                    .build()
                    .encode()
                    .toUri();

            Map<?, ?> response = client.get()
                    .uri(uri)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return error(502, "Empty response from translation service.");
            }

            Object status = response.get("responseStatus");

            if (!(status instanceof Number)
                    || ((Number) status).intValue() != 200) {
                return error(502,
                        "This language pair is not supported or translation failed.");
            }

            Object data = response.get("responseData");

            if (!(data instanceof Map)) {
                return error(502, "Invalid translation response.");
            }

            Object translated =
                    ((Map<?, ?>) data).get("translatedText");

            if (translated == null
                    || translated.toString().isBlank()) {
                return error(502, "No translation was returned.");
            }

            Map<String, Object> result = new LinkedHashMap<>();

            Map<String, Object> translationData =
                    new LinkedHashMap<>();

            translationData.put(
                    "translatedText",
                    translated.toString()
            );

            result.put("responseStatus", 200);
            result.put("responseData", translationData);
            result.put("provider", "Apertium");

            return result;

        } catch (Exception e) {
            System.err.println(
                    "Apertium translation error: " + e.getMessage());

            return error(
                    502,
                    "Translation service is temporarily unavailable."
            );
        }
    }

    private Map<String, Object> error(
            int status, String message) {

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("responseStatus", status);
        result.put("responseDetails", message);

        return result;
    }
}
