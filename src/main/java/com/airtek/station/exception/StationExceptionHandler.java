package com.airtek.station.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Traduce excepciones de dominio al JSON de error del contrato.
 *
 * <p>FastAPI envolvía el detalle en {@code detail}; este advice no. El cuerpo
 * es siempre {@code error.code} + {@code error.message}, que es lo que lee
 * {@code station.ts} del cliente ({@code STATION_BUSY}, {@code GAME_NOT_READY},
 * {@code UNKNOWN_GAME}, {@code BAD_REQUEST}, {@code LAUNCH_FAILED}).
 *
 * <p>Bean Validation ({@code @NotBlank}, {@code @Valid}) cae en 400
 * {@code BAD_REQUEST}. Un {@link IllegalStateException} que escape de
 * {@code startSource} (Xvfb ausente, nativo WebRTC que no carga) es 500
 * {@code LAUNCH_FAILED}. El resto de excepciones de Spring (404, método)
 * no se capturan aquí.
 */

@RestControllerAdvice
public class StationExceptionHandler {

    @ExceptionHandler(StationException.class)
    public ResponseEntity<Map<String, Object>> station(StationException ex) {
        return body(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse("Solicitud inválida");
        return body(400, "BAD_REQUEST", message);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> unexpected(IllegalStateException ex) {
        return body(500, "LAUNCH_FAILED", ex.getMessage() == null ? "error interno" : ex.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> body(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", Map.of("code", code, "message", message)
        ));
    }
}
