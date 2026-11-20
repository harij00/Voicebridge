
package com.voicebridge;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@CrossOrigin
public class TranslationController {

    private final RestClient client = RestClient.create();

        @GetMapping("/api/translate")
            public Map<String, Object> translate(
                        @RequestParam String text,
                                    @RequestParam String target) {

                                            String url = "https://api.mymemory.translated.net/get?q="
                                                            + UriUtils.encodeQueryParam(text, StandardCharsets.UTF_8)
                                                                            + "&langpair=autodetect|"
                                                                                            + UriUtils.encodeQueryParam(target, StandardCharsets.UTF_8);

                                                                                                    Map response = client.get()
                                                                                                                    .uri(url)
                                                                                                                                    .retrieve()
                                                                                                                                                    .body(Map.class);

                                                                                                                                                            return response;
                                                                                                                                                                }
                                                                                                                                                                }
                                                                                                                                                                