package com.airtek.station.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Recorre el contrato HTTP contra el Tomcat embebido: health en {@code IDLE}
 * con el manifiesto embebido, prepare 202 que llega a {@code READY}, launch
 * 200 con {@code wsUrl} y stop que vuelve a {@code IDLE}. El launch carga
 * webrtc-java y arranca la bomba SMPTE; el stop la tira.
 */

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StationHttpTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Test
    void prepareLaunchAndStopTheBundledPattern() throws Exception {
        ResponseEntity<Map> health = http.getForEntity("/health", Map.class);
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertEquals("IDLE", health.getBody().get("state"));
        assertEquals("test-pattern", health.getBody().get("supportedGameId"));

        ResponseEntity<Map> prepare = http.postForEntity("/prepare", Map.of(
                "sessionId", "ses-1",
                "gameId", "test-pattern",
                "version", "1.0.0",
                "source", Map.of("type", "local", "path", "/library/test-pattern/1.0.0", "checksum", "sha256:00")
        ), Map.class);
        assertEquals(HttpStatus.ACCEPTED, prepare.getStatusCode());

        String state = "PREPARING";
        for (int i = 0; i < 20 && !"READY".equals(state); i++) {
            Thread.sleep(50);
            state = String.valueOf(http.getForObject("/health", Map.class).get("state"));
        }
        assertEquals("READY", state);

        ResponseEntity<Map> launch = http.postForEntity("/launch", Map.of(
                "sessionId", "ses-1",
                "gameId", "test-pattern"
        ), Map.class);
        assertEquals(HttpStatus.OK, launch.getStatusCode());
        assertEquals("/ws/webrtc", launch.getBody().get("wsUrl"));

        http.postForEntity("/stop", Map.of("sessionId", "ses-1"), Void.class);
        assertEquals("IDLE", http.getForObject("/health", Map.class).get("state"));
    }
}
