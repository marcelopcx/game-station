/**
 * Handlers de signaling.
 *
 * <p>{@code /ws/control} reparte {@code STATE} y {@code ERROR} a todos los
 * browsers conectados. {@code /ws/webrtc} es un solo peer: el segundo socket
 * recibe {@code PEER_BUSY} y se cierra. El browser manda {@code OFFER} e
 * {@code ICE}; la estación contesta {@code ANSWER} e ICE propio. Cerrar el
 * socket suelta el {@code RTCPeerConnection} y deja la fuente de frames
 * viva hasta {@code POST /stop}.
 */
package com.airtek.station.websocket;
