package com.airtek.station.service;

import com.airtek.station.model.GameManifest;
import com.airtek.station.config.StationProperties;
import com.airtek.station.infrastructure.LookFeed;
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
 * <p>Con un juego nativo levanta Xvfb ({@code -screen 0 ${SIZE}x24}, GLX, XTEST),
 * espera 400 ms y hace spawn con {@code LD_PRELOAD=libgameinput.so} para look SDL.
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
        env.putIfAbsent("SDL_MOUSE_RELATIVE_MODE_WARP", "1");
        env.putIfAbsent("SDL_VIDEO_X11_DGAMOUSE", "0");
        String preload = "/usr/local/lib/libgameinput.so";
        String existing = env.get("LD_PRELOAD");
        if (existing == null || existing.isBlank()) {
            env.put("LD_PRELOAD", preload);
        } else if (!existing.contains("libgameinput.so")) {
            env.put("LD_PRELOAD", preload + ":" + existing);
        }
        LookFeed.prepare();
        processes.spawn(argv, env, manifest.getId(), manifest.getWorkdir());
        if (manifest.getNeeds().isRelativeMouse()) {
            Map<String, String> focusEnv = new HashMap<>(env);
            focusEnv.put("DISPLAY", settings.getDisplay());
            processes.spawn(
                    List.of("sh", "-c", "sleep 2; exec xfocus-game " + settings.getDisplay()),
                    focusEnv,
                    "xfocus",
                    null
            );
        }
    }
}
