package com.airtek.station.service;

import com.airtek.station.model.GameManifest;
import com.airtek.station.config.StationProperties;
import com.airtek.station.dto.SourceBody;
import com.airtek.station.exception.StationException;
import com.airtek.station.model.StationState;
import com.airtek.station.exception.PrepareException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Máquina de estados de la estación. Una instancia por proceso. Las
 * transiciones de {@code prepare} y {@code launch} toman un
 * {@link java.util.concurrent.locks.ReentrantLock}; la copia y el watch de
 * idle corren en un executor de un solo hilo ({@code station-fsm}) para no
 * bloquear el hilo de Tomcat.
 *
 * <p>No construye SDP. El video y el DataChannel viven detrás de
 * {@link MediaSession}. {@code stop} cierra peer, fuente, pads y procesos;
 * el proceso Spring sigue arriba.
 *
 * <p>Idle: en {@code PLAYING}, si no llega input durante
 * {@code station.idle-timeout-s} (default 300) se hace stop. El reloj usa
 * {@link MediaSession#lastInputMillis()}, o el instante del launch si el
 * jugador todavía no tocó nada.
 */

@Service
public class StationService {

    private static final Logger log = LoggerFactory.getLogger(StationService.class);

    private final ControlHub hub;
    private final MediaSession media;
    private final GameCatalog catalog;
    private final FilePreparer preparer;
    private final StationProperties settings;
    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "station-fsm");
        thread.setDaemon(true);
        return thread;
    });

    private StationState state = StationState.IDLE;
    private String gameId;
    private String sessionId;
    private int progress;
    private boolean cacheHit;
    private long playingSince;
    private ScheduledFuture<?> idleTask;
    private ScheduledFuture<?> failedTask;

    public StationService(
            ControlHub hub,
            MediaSession media,
            GameCatalog catalog,
            FilePreparer preparer,
            StationProperties settings
    ) {
        this.hub = hub;
        this.media = media;
        this.catalog = catalog;
        this.preparer = preparer;
        this.settings = settings;
    }

    /**
     * Cuerpo de {@code GET /health}. {@code progress} no va aquí.
     * {@code cache} lista {@code gameId}/{@code version} que ya tienen
     * {@code payload.bin}. {@code needs} sale del manifiesto, no del estado.
     *
     * @return mapa camelCase listo para Jackson
     */
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("stationId", settings.getId());
        body.put("state", state.name());
        body.put("gameId", gameId);
        body.put("sessionId", sessionId);
        body.put("encoder", state == StationState.PLAYING ? media.encoderName() : null);
        body.put("cache", preparer.listCache());
        body.put("supportedGameId", catalog.gameId());
        body.put("needs", catalog.manifest().getNeeds().toWire());
        body.put("httpPort", settingsPort());
        body.put("display", settings.getDisplay());
        return body;
    }

    /**
     * Entra en {@code PREPARING} bajo el lock y agenda la copia. El retorno
     * es el 202; el paso a {@code READY} o {@code FAILED} es asíncrono.
     * {@code test-pattern} no toca disco: el executor publica {@code READY}
     * en el acto.
     *
     * @param sessionId identificador del browser
     * @param gameId    tiene que ser el {@code id} del manifiesto
     * @param version   segmento de path en el cache
     * @param source    origen local; {@code http} y {@code azure-sas} fallan en el worker
     * @return {@code sessionId} y {@code state=PREPARING}
     */
    public Map<String, Object> prepare(String sessionId, String gameId, String version, SourceBody source) {
        GameManifest manifest = catalog.require(gameId);
        lock.lock();
        try {
            if (state == StationState.PREPARING || state == StationState.PLAYING) {
                throw StationException.prepareInProgress();
            }
            cancel(failedTask);
            state = StationState.PREPARING;
            this.sessionId = sessionId;
            this.gameId = gameId;
            progress = 0;
            cacheHit = false;
        } finally {
            lock.unlock();
        }
        log.info("state=PREPARING sessionId={} game={} version={}", sessionId, gameId, version);
        hub.broadcast(event(StationState.PREPARING, 0, "Copiando assets", false));
        scheduler.execute(() -> runPrepare(sessionId, gameId, version, source, manifest.testPattern()));
        return Map.of("sessionId", sessionId, "state", StationState.PREPARING.name());
    }

    /**
     * Exige {@code READY}, publica {@code PLAYING} y llama a
     * {@link MediaSession#startSource}. Si el arranque tira, hace
     * {@link #stop()} y relanza: el cliente ve 500 y la estación queda
     * {@code IDLE}. El watch de idle arranca solo si {@code startSource} volvió.
     *
     * @param sessionId sesión que preparó
     * @param gameId    mismo id que el prepare
     * @return {@code state}, {@code wsUrl=/ws/webrtc} y {@code needs}
     */
    public Map<String, Object> launch(String sessionId, String gameId) {
        catalog.require(gameId);
        lock.lock();
        try {
            if (state == StationState.PLAYING) {
                throw StationException.busy();
            }
            if (state != StationState.READY) {
                throw StationException.gameNotReady();
            }
            state = StationState.PLAYING;
            this.sessionId = sessionId;
            this.gameId = gameId;
            playingSince = System.currentTimeMillis();
        } finally {
            lock.unlock();
        }
        hub.broadcast(event(StationState.PLAYING, 100, "En partida", cacheHit));
        try {
            media.startSource(gameId);
        } catch (RuntimeException ex) {
            log.error("start_source failed sessionId={} game={}", sessionId, gameId, ex);
            stop();
            throw ex;
        }
        scheduleIdle();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", StationState.PLAYING.name());
        body.put("wsUrl", "/ws/webrtc");
        body.put("needs", catalog.manifest().getNeeds().toWire());
        return body;
    }

    /**
     * Teardown de la partida. Cancela idle y el retorno desde {@code FAILED},
     * cierra media y procesos, y borra sesión, juego y progreso. Idempotente.
     * El broadcast {@code STATE IDLE} sale después de soltar el lock.
     */
    public void stop() {
        cancel(idleTask);
        cancel(failedTask);
        try {
            media.stop();
        } catch (RuntimeException ex) {
            log.error("media stop failed", ex);
        }
        lock.lock();
        try {
            state = StationState.IDLE;
            gameId = null;
            sessionId = null;
            progress = 0;
            cacheHit = false;
            playingSince = 0;
        } finally {
            lock.unlock();
        }
        log.info("state=IDLE sessionId=none encoder=none");
        hub.broadcast(event(StationState.IDLE, 0, "Libre", false));
    }

    public void connectControl(WebSocketSession session) {
        hub.connect(session);
    }

    public void disconnectControl(WebSocketSession session) {
        hub.disconnect(session);
    }

    public void attachWebrtc(WebSocketSession session) throws IOException {
        if (state != StationState.PLAYING) {
            send(session, "{\"type\":\"ERROR\",\"code\":\"NOT_PLAYING\"}");
            session.close();
            return;
        }
        media.attach(session);
    }

    public void onWebrtc(WebSocketSession session, String json) {
        media.onSignal(session, json);
    }

    public void detachWebrtc(WebSocketSession session) {
        media.detach(session);
    }

    private void runPrepare(String session, String game, String version, SourceBody source, boolean testPattern) {
        try {
            boolean hit = false;
            if (!testPattern) {
                hit = preparer.run(
                        game,
                        version,
                        source.typeOrLocal(),
                        source.path(),
                        source.checksum(),
                        (pct, message) -> {
                            lock.lock();
                            try {
                                if (state != StationState.PREPARING) {
                                    return;
                                }
                                progress = pct;
                            } finally {
                                lock.unlock();
                            }
                            hub.broadcast(event(StationState.PREPARING, pct, message, false));
                        }
                );
            }
            lock.lock();
            try {
                if (state != StationState.PREPARING || !session.equals(this.sessionId)) {
                    return;
                }
                state = StationState.READY;
                progress = 100;
                cacheHit = hit;
            } finally {
                lock.unlock();
            }
            log.info("state=READY sessionId={} cacheHit={}", session, hit);
            hub.broadcast(event(StationState.READY, 100, "Listo", hit));
        } catch (PrepareException ex) {
            enterFailed(ex.code(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("prepare crashed sessionId={}", session, ex);
            enterFailed("PREPARE_FAILED", "prepare falló");
        }
    }

    private void enterFailed(String code, String message) {
        lock.lock();
        try {
            state = StationState.FAILED;
            progress = 0;
        } finally {
            lock.unlock();
        }
        hub.broadcast(Map.of("type", "ERROR", "code", code, "message", message));
        hub.broadcast(event(StationState.FAILED, 0, message, false));
        failedTask = scheduler.schedule(this::stop, 2, TimeUnit.SECONDS);
    }

    private void scheduleIdle() {
        double timeout = settings.getIdleTimeoutS();
        if (timeout <= 0) {
            return;
        }
        idleTask = scheduler.scheduleWithFixedDelay(() -> {
            if (state != StationState.PLAYING) {
                return;
            }
            long last = media.lastInputMillis();
            long ref = last > 0 ? last : playingSince;
            if (System.currentTimeMillis() - ref >= (long) (timeout * 1000)) {
                log.info("state=IDLE sessionId={} reason=idle_timeout", sessionId);
                stop();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private Map<String, Object> event(StationState next, int pct, String message, boolean hit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "STATE");
        body.put("state", next.name());
        body.put("progress", pct);
        body.put("message", message);
        body.put("cacheHit", hit);
        return body;
    }

    private static void send(WebSocketSession session, String json) throws IOException {
        synchronized (session) {
            session.sendMessage(new org.springframework.web.socket.TextMessage(json));
        }
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private int settingsPort() {
        String raw = System.getenv().getOrDefault("STATION_HTTP_PORT", "8090");
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return 8090;
        }
    }
}
