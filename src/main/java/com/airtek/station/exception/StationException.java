package com.airtek.station.exception;

/**
 * Error de dominio con código de wire y status HTTP fijos.
 *
 * <p>No es una {@code ResponseStatusException}: el código ({@code STATION_BUSY},
 * {@code GAME_NOT_READY}, …) viaja en el JSON, no solo en el status. Las
 * fábricas estáticas fijan el par código/mensaje que el cliente ya muestra.
 *
 * <p>Códigos:
 * <ul>
 *   <li>{@code STATION_BUSY} 409 — {@code launch} con estado {@code PLAYING}</li>
 *   <li>{@code PREPARE_IN_PROGRESS} 409 — {@code prepare} durante PREPARING o PLAYING</li>
 *   <li>{@code GAME_NOT_READY} 424 — {@code launch} sin un prepare exitoso</li>
 *   <li>{@code UNKNOWN_GAME} 400 — {@code gameId} distinto del manifiesto de esta imagen</li>
 * </ul>
 */

public class StationException extends RuntimeException {

    private final String code;
    private final int status;

    public StationException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public static StationException busy() {
        return new StationException("STATION_BUSY", "Ya hay una partida en esta estación", 409);
    }

    public static StationException prepareInProgress() {
        return new StationException("PREPARE_IN_PROGRESS", "La estación está preparando o en partida", 409);
    }

    public static StationException gameNotReady() {
        return new StationException("GAME_NOT_READY", "Hay que preparar el juego antes de lanzar", 424);
    }

    public static StationException unknownGame(String gameId, String supported) {
        String message = supported == null
                ? "gameId desconocido: " + gameId
                : "esta imagen sirve " + supported + ", no " + gameId;
        return new StationException("UNKNOWN_GAME", message, 400);
    }

    public static StationException badRequest(String message) {
        return new StationException("BAD_REQUEST", message, 400);
    }
}
