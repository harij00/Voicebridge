
package com.voicebridge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.util.UriComponentsBuilder;

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
            String url = UriComponentsBuilder
                    .fromUriString("https://api.mymemory.translated.net/get")
                    .queryParam("q", text)
                    .queryParam("langpair", "en|" + target)
                    .queryParam("de", email.isBlank() ? null : email)
                    .build()
                    .encode()
                    .toUriString();

            Map response = client.get()
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

        } catch (Exception e) {

            return Map.of(
                    "responseStatus", 500,
                    "responseDetails",
                    "Translation service failed: " + e.getMessage()
            );
        }
    }
}
