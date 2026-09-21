/**
 * Estado de la partida y manifiesto del juego.
 *
 * <p>La FSM es {@code IDLE → PREPARING → READY → PLAYING}. {@code FAILED}
 * vuelve a {@code IDLE} a los ~2 s vía {@code stop}. Un contenedor hornea
 * un solo {@code id}. {@code kind: test-pattern} (o un {@code command}
 * vacío) no levanta Xvfb ni binario: la fuente de vídeo pinta SMPTE.
 *
 * <p>{@code needs.gamepad} acepta bool o entero 0..4. {@code needs.display}
 * decide si el runtime crea Xvfb. {@code needs.audio} queda en el manifiesto;
 * este corte no levanta un daemon de Pulse.
 */
package com.airtek.station.model;
