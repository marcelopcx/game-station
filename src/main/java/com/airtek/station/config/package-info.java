/**
 * Configuración de arranque.
 *
 * <p>{@link com.airtek.station.config.StationProperties} liga el prefijo
 * {@code station.*} de {@code application.yml} (id, display, tamaño, fps,
 * raíces de library y cache, timeout de idle, manifiesto, ICE y CORS).
 * {@link com.airtek.station.config.StationConfig} abre CORS con
 * {@code allowedOriginPatterns("*")} y registra los dos handlers:
 * {@code /ws/control} y {@code /ws/webrtc}.
 */
package com.airtek.station.config;
