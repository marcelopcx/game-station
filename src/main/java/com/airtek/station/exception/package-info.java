/**
 * Errores del contrato de la estación.
 *
 * <p>{@link com.airtek.station.exception.StationException} cubre los códigos
 * que el cliente ya distingue: {@code STATION_BUSY} y
 * {@code PREPARE_IN_PROGRESS} (409), {@code GAME_NOT_READY} (424),
 * {@code UNKNOWN_GAME} y {@code BAD_REQUEST} (400), {@code LAUNCH_FAILED}
 * (500). {@link com.airtek.station.exception.PrepareException} es el fallo
 * de la copia ({@code CHECKSUM_MISMATCH}, {@code SOURCE_NOT_FOUND},
 * {@code UNSUPPORTED_SOURCE}, {@code PREPARE_FAILED}) y no sale por HTTP:
 * el worker lo convierte en {@code STATE FAILED} por {@code /ws/control}.
 *
 * <p>El advice no captura {@code Exception}. Un 404 de Spring sigue siendo 404.
 */
package com.airtek.station.exception;
