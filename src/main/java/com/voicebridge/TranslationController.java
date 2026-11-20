
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

    @Value("${mymemory.email:}")
    private String email;

    @GetMapping("/api/translate")
    public Map<String, Object> translate(
            @RequestParam String text,
            @RequestParam String target) {

        try {
            var uriBuilder = UriComponentsBuilder
                    .fromHttpUrl("https://api.mymemory.translated.net/get")
                    .queryParam("q", text)
                    .queryParam("langpair", "autodetect|" + target);

            if (!email.isBlank()) {
                uriBuilder.queryParam("de", email);
            }

            String url = uriBuilder.build()
                    .encode()
                    .toUriString();

            return client.get()
                    .uri(url)
                    .retrieve()
                    .body(Map.class);

        } catch (HttpClientErrorException.TooManyRequests e) {
            return Map.of(
                    "responseStatus", 429,
                    "responseDetails",
                    "Translation limit reached. Please try again later."
            );
        }
    }
}
