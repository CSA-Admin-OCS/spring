package com.open.spring.mvc.javarunner;

import org.springframework.web.client.RestClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/run")
public class JavaRunnerApiController {

    private final RestClient restClient;

    private final String runnerUrl;

    public JavaRunnerApiController(RestClient.Builder builder) {

        this.restClient = builder.build();

        this.runnerUrl = System.getenv()
                .getOrDefault(
                        "JAVA_RUNNER_URL",
                        "http://code_runner:8592"
                );
    }

    @PostMapping("/java")
    public ResponseEntity<Map<String, String>> runJava(
            @RequestBody Map<String, String> body) {

        try {

            Map<String, String> response =
                    restClient.post()
                            .uri(runnerUrl + "/java")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});

            return ResponseEntity.ok(response);

        } catch (Exception e) {

            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "output",
                            "Could not connect to Java runner: "
                                    + e.getMessage()
                    ));
        }
    }
}