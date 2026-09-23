package com.airtek.station.service;

import com.airtek.station.config.StationProperties;
import com.airtek.station.exception.StationException;
import com.airtek.station.model.GameManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Carga el único manifiesto de esta imagen.
 *
 * <p>Si {@code station.game-manifest} apunta a un archivo, ese YAML gana
 * (el juego real del contenedor, p. ej. {@code /opt/game/manifest.yaml}).
 * Si está vacío, se usa el {@code manifest.yaml} del classpath
 * ({@code test-pattern}) para poder preparar y lanzar sin library.
 *
 * <p>{@link #require(String)} es el guard de {@code prepare} y {@code launch}:
 * otro {@code gameId} es {@code UNKNOWN_GAME}. {@link #argv} interpola
 * placeholders y devuelve lista vacía cuando no hay binario.
 */

@Component
public class GameCatalog {

    private static final Logger log = LoggerFactory.getLogger(GameCatalog.class);

    private final GameManifest manifest;

    public GameCatalog(StationProperties properties) {
        this.manifest = load(properties.getGameManifest());
        var hostIps = properties.iceHostIpList();
        log.info(
                "ice policy={} hostIps={} stun={}",
                properties.getIceHostPolicy(),
                hostIps.isEmpty() ? "(auto)" : hostIps,
                properties.iceServerList()
        );
        if (hostIps.isEmpty() && "public".equalsIgnoreCase(properties.getIceHostPolicy())) {
            log.warn(
                    "ICE_HOST_POLICY=public sin ICE_HOST_IPS: en LAN suele fallar ICE; "
                            + "usá ICE_HOST_POLICY=all o ICE_HOST_IPS=<IP que ve el browser>"
            );
        }
    }

    public GameManifest manifest() {
        return manifest;
    }

    public String gameId() {
        return manifest.getId();
    }

    public GameManifest require(String gameId) {
        if (manifest.getId() == null || !manifest.getId().equals(gameId)) {
            throw StationException.unknownGame(gameId, manifest.getId());
        }
        return manifest;
    }

    public java.util.List<String> argv(StationProperties settings) {
        if (manifest.testPattern()) {
            return java.util.List.of();
        }
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        for (String part : manifest.getCommand()) {
            parts.add(settings.interpolate(part));
        }
        for (String part : manifest.getArgs()) {
            parts.add(settings.interpolate(part));
        }
        return parts;
    }

    private static GameManifest load(String configured) {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try {
            if (configured != null && !configured.isBlank()) {
                Path path = Path.of(configured);
                if (!Files.isRegularFile(path)) {
                    throw new IllegalStateException("no hay manifiesto en " + path);
                }
                GameManifest manifest = yaml.readValue(path.toFile(), GameManifest.class);
                log.info("catalog game={} kind={} path={}", manifest.getId(), manifest.getKind(), path);
                return manifest;
            }
            try (InputStream in = new ClassPathResource("manifest.yaml").getInputStream()) {
                GameManifest manifest = yaml.readValue(in, GameManifest.class);
                log.info("catalog game={} kind=test-pattern (embebido)", manifest.getId());
                return manifest;
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("manifest inválido", ex);
        }
    }
}
