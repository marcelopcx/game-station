package com.airtek.station.service;

import com.airtek.station.model.GameManifest;
import com.airtek.station.config.StationProperties;
import com.airtek.station.infrastructure.SdlMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.airtek.station.infrastructure.ProcessGroup;

/**
 * Orquesta display virtual y proceso del juego según el manifiesto.
 *
 * <p>{@link #start(String)} con {@code test-pattern} no hace exec. Con un
 * juego nativo levanta Xvfb ({@code -screen 0 ${SIZE}x24}, GLX, XTEST) en
 * {@code station.display}, espera 400 ms y hace spawn del argv interpolado.
 * El entorno hereda el del JVM y fuerza {@code DISPLAY}, vsync apagado y,
 * si {@code needs.gamepad > 0}, {@code SDL_GAMECONTROLLERCONFIG} con el
 * mapping del pad virtual.
 *
 * <p>{@link #stop()} mata el árbol de procesos (Xvfb y el juego) con
 * {@link com.airtek.station.infrastructure.ProcessGroup}. No mata la JVM.
 * Pulse no se supervisa en este corte: {@code needs.audio} no añade un
 * daemon; si el juego lo necesita, el manifiesto trae {@code fallbackArgs}.
 */

@Component
public class GameRuntime {

    private static final Logger log = LoggerFactory.getLogger(GameRuntime.class);

    private final StationProperties settings;
    private final GameCatalog catalog;
    private final ProcessGroup processes = new ProcessGroup();
    private GameManifest active;

    public GameRuntime(StationProperties settings, GameCatalog catalog) {
        this.settings = settings;
        this.catalog = catalog;
    }

    public StationProperties settings() {
        return settings;
    }

    public GameManifest active() {
        return active;
    }

    public boolean capturesDisplay() {
        return active != null && active.getNeeds().isDisplay() && !active.testPattern();
    }

    /**
     * Arranca el juego del catálogo. Un argv vacío (test-pattern) no crea
     * display. El camino nativo hace spawn de Xvfb, espera 400 ms y lanza
     * el binario con {@code DISPLAY} y, si hay pads, {@code SDL_GAMECONTROLLERCONFIG}.
     *
     * @param gameId id ya aceptado por {@link GameCatalog#require}
     * @throws IllegalStateException si Xvfb o el juego no arrancan; el árbol queda parado
     */
    public void start(String gameId) {
        GameManifest manifest = catalog.require(gameId);
        active = manifest;
        List<String> argv = catalog.argv(settings);
        if (argv.isEmpty()) {
            log.info("runtime skip display game={} kind={}", gameId, manifest.getKind());
            return;
        }
        try {
            startDisplay();
            boolean pulse = true;
            if (manifest.getNeeds().isAudio()) {
                pulse = startPulse();
                if (!pulse && manifest.getAudio().getFallbackArgs() != null) {
                    List<String> extra = new ArrayList<>(argv);
                    for (String arg : manifest.getAudio().getFallbackArgs()) {
                        extra.add(settings.interpolate(arg));
                    }
                    argv = extra;
                    log.warn("pulse failed; fallback args={}", manifest.getAudio().getFallbackArgs());
                }
            }
            spawnGame(argv, manifest);
        } catch (IOException ex) {
            processes.stop();
            active = null;
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    /**
     * Mata Xvfb y el juego (SIGTERM, 3 s, luego SIGKILL) y olvida el manifiesto activo.
     */
    public void stop() {
        processes.stop();
        active = null;
    }

    private void startDisplay() throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        processes.spawn(List.of(
                "Xvfb",
                settings.getDisplay(),
                "-screen", "0",
                settings.getSize() + "x24",
                "-ac",
                "+extension", "GLX",
                "+extension", "XTEST"
        ), env, "xvfb", null);
        try {
            Thread.sleep(400);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        log.info("display ready {} {}", settings.getDisplay(), settings.getSize());
    }

    private boolean startPulse() {
        return true;
    }

    private void spawnGame(List<String> argv, GameManifest manifest) throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("DISPLAY", settings.getDisplay());
        env.putIfAbsent("NVIDIA_DRIVER_CAPABILITIES", "all");
        env.putIfAbsent("vblank_mode", "0");
        env.putIfAbsent("__GL_SYNC_TO_VBLANK", "0");
        env.putIfAbsent("SDL_HINT_RENDER_VSYNC", "0");
        env.putIfAbsent("SDL_RENDER_VSYNC", "0");
        if (manifest.getEnv() != null) {
            manifest.getEnv().forEach((key, value) -> env.put(key, settings.interpolate(value)));
        }
        if (manifest.getNeeds().getGamepad() > 0) {
            env.putIfAbsent("SDL_GAMECONTROLLERCONFIG", SdlMapping.config(manifest.getNeeds().getGamepad()));
        }
        processes.spawn(argv, env, manifest.getId(), manifest.getWorkdir());
    }
}
