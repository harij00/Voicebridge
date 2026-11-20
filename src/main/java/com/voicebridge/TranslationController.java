
package com.voicebridge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@CrossOrigin
public class TranslationController {

    private final RestClient client = RestClient.create();

    @Value("${MYMEMORY_EMAIL:}")
    private String email;

    @GetMapping("/api/translate")
    public Map<String, Object> translate(
            @RequestParam String text,
            @RequestParam String target) {

        try {
            if (text == null || text.isBlank()) {
                return Map.of(
                        "responseStatus", 400,
                        "responseDetails", "Please enter some text."
                );
            }

            if (target == null || target.isBlank()) {
                return Map.of(
                        "responseStatus", 400,
                        "responseDetails", "Please select a target language."
                );
            }

            String encodedText = UriUtils.encodeQueryParam(
                    text, StandardCharsets.UTF_8);

            String encodedTarget = UriUtils.encodeQueryParam(
                    target, StandardCharsets.UTF_8);

            String url = "https://api.mymemory.translated.net/get?q="
                    + encodedText
                    + "&langpair=en|" + encodedTarget;

            if (email != null && !email.isBlank()) {
                String encodedEmail = UriUtils.encodeQueryParam(
                        email, StandardCharsets.UTF_8);

                url += "&de=" + encodedEmail;
            }

            System.out.println("Translation target: " + target);

            Map<String, Object> response = client.get()
                    .uri(url)
                    .retrieve()
                    .body(Map.class);

            return response;

        } catch (HttpClientErrorException.TooManyRequests e) {

            return Map.of(
                    "responseStatus", 429,
                    "responseDetails",
                    "Daily translation limit reached. Please try again later."
            );

        } catch (HttpClientErrorException e) {

            return Map.of(
                    "responseStatus", e.getStatusCode().value(),
                    "responseDetails",
                    "Translation provider rejected the request."
            );

        } catch (Exception e) {

            return Map.of(
                    "responseStatus", 500,
                    "responseDetails",
                    "Translation service failed: " + e.getMessage()
            );
        }
    }
}
