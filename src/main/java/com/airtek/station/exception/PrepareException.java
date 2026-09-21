package com.airtek.station.exception;

/**
 * Fallo de la copia {@code /library → /cache}. No aborta el hilo HTTP:
 * {@link com.airtek.station.service.StationService} la atrapa en el executor
 * y pasa la estación a {@code FAILED}, emitiendo {@code type=ERROR} por
 * {@code /ws/control}.
 *
 * <p>Códigos: {@code SOURCE_NOT_FOUND}, {@code CHECKSUM_MISMATCH},
 * {@code UNSUPPORTED_SOURCE} ({@code http} y {@code azure-sas} no están en
 * este corte), {@code PREPARE_FAILED}.
 */

public class PrepareException extends RuntimeException {

    private final String code;

    public PrepareException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
