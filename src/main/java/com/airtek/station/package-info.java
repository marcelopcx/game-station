/**
 * Estación de juego en un solo proceso Spring Boot.
 *
 * <p>El browser habla REST ({@code /health}, {@code /prepare}, {@code /launch},
 * {@code /stop}) y dos WebSocket ({@code /ws/control}, {@code /ws/webrtc}).
 * El vídeo RTP no entra por este proceso: lo saca libwebrtc. El input entra
 * por el DataChannel {@code input} como datagrama de snapshots (magic
 * {@code 0xA7}, versión 3).
 *
 * <p>Capas: {@code config} (propiedades y beans de infraestructura HTTP),
 * {@code controller} (REST), {@code dto} (cuerpos), {@code service} (FSM,
 * prepare, runtime, media), {@code websocket} (handlers), {@code model}
 * (estado y manifiesto), {@code exception} (errores de contrato) e
 * {@code infrastructure} (uinput, XTEST, parser, procesos).
 */
package com.airtek.station;
