package com.airtek.station.controller;

import com.airtek.station.dto.LaunchRequest;
import com.airtek.station.dto.PrepareRequest;
import com.airtek.station.dto.StopRequest;
import com.airtek.station.service.StationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Fachada HTTP de la estación. No decide transiciones: delega en
 * {@link com.airtek.station.service.StationService}.
 *
 * <p>Contrato, idéntico al cliente React:
 * <ul>
 *   <li>{@code GET /health} → 200, snapshot sin {@code progress}</li>
 *   <li>{@code POST /prepare} → 202, la copia corre en background</li>
 *   <li>{@code POST /launch} → 200 y {@code wsUrl=/ws/webrtc}</li>
 *   <li>{@code POST /stop} → 204, idempotente</li>
 * </ul>
 * Los cuerpos van en camelCase. Un fallo de dominio sale como
 * {@code { "error": { "code", "message" } }} vía
 * {@link com.airtek.station.exception.StationExceptionHandler}.
 */

@RestController
public class StationController {

    private final StationService station;

    public StationController(StationService station) {
        this.station = station;
    }

    /**
     * Snapshot de la estación. No incluye {@code progress}: eso sale solo por
     * {@code /ws/control}. {@code encoder} es {@code null} fuera de {@code PLAYING}.
     *
     * @return cuerpo de {@code GET /health}
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return station.health();
    }

    /**
     * Arranca la copia a cache y responde enseguida. El 202 no significa que
     * el juego esté listo: el cliente espera {@code STATE READY} por el socket.
     *
     * @param body sesión, juego, versión y origen local
     * @return 202 con {@code sessionId} y {@code state=PREPARING}
     * @throws com.airtek.station.exception.StationException {@code UNKNOWN_GAME} o {@code PREPARE_IN_PROGRESS}
     */
    @PostMapping("/prepare")
    public ResponseEntity<Map<String, Object>> prepare(@Valid @RequestBody PrepareRequest body) {
        return ResponseEntity.accepted().body(
                station.prepare(body.sessionId(), body.gameId(), body.version(), body.source())
        );
    }

    /**
     * Pasa a {@code PLAYING} y arranca display, juego y fuente de video.
     * A partir de aquí el browser puede abrir {@code /ws/webrtc}.
     *
     * @param body sesión ya preparada y {@code gameId} del manifiesto
     * @return {@code state}, {@code wsUrl} y {@code needs}
     * @throws com.airtek.station.exception.StationException {@code STATION_BUSY}, {@code GAME_NOT_READY} o {@code UNKNOWN_GAME}
     */
    @PostMapping("/launch")
    public Map<String, Object> launch(@Valid @RequestBody LaunchRequest body) {
        return station.launch(body.sessionId(), body.gameId());
    }

    /**
     * Cierra la partida y vuelve a {@code IDLE}. Siempre 204, haya o no sesión.
     *
     * @param body se valida que traiga {@code sessionId}; no se usa para filtrar
     * @return respuesta vacía
     */
    @PostMapping("/stop")
    public ResponseEntity<Void> stop(@Valid @RequestBody StopRequest body) {
        station.stop();
        return ResponseEntity.noContent().build();
    }
}
