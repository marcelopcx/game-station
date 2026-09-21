package com.airtek.station.service;

/**
 * Puerto que {@link StationService} usa para no depender de webrtc-java.
 *
 * <p>{@link #startSource(String)} arranca display, juego, pads y la bomba de
 * frames. {@link #attach} registra el socket de signaling; puede cerrarlo
 * con {@code PEER_BUSY}. {@link #onSignal} recibe el texto JSON
 * ({@code OFFER} / {@code ICE}). {@link #detach} corresponde al close del
 * WebSocket. {@link #stop} es el teardown de la partida.
 *
 * <p>{@link #encoderName()} es {@code null} fuera de {@code PLAYING}.
 * {@link #lastInputMillis()} es epoch millis del último datagrama válido,
 * o {@code null} si no hubo ninguno.
 */

public interface MediaSession {

    /**
     * Arranca el runtime del {@code gameId} y la fuente que va a alimentar el peer.
     * Puede bloquear el hilo de la petición (Xvfb, factory nativo).
     *
     * @param gameId id ya validado contra el manifiesto
     */
    void startSource(String gameId);

    /** Cierra peer, bomba de frames, pads y procesos del juego. */
    void stop();

    /**
     * Registra el socket de signaling. Si ya hay un peer abierto, manda
     * {@code PEER_BUSY} y cierra {@code socket} sin tocar la partida.
     *
     * @param socket sesión ya aceptada por Spring
     * @throws java.io.IOException si el close de rechazo no se puede escribir
     */
    void attach(org.springframework.web.socket.WebSocketSession socket) throws java.io.IOException;

    /**
     * Un texto JSON del browser: {@code OFFER} con SDP o {@code ICE} con
     * candidate, {@code sdpMid} y {@code sdpMLineIndex}.
     *
     * @param socket tiene que ser el peer registrado; si no, se ignora
     * @param json   payload de texto, no binario
     */
    void onSignal(org.springframework.web.socket.WebSocketSession socket, String json);

    /**
     * El browser cerró el socket. Se suelta el {@code RTCPeerConnection}
     * y la fuente de frames sigue, para poder renegociar sin relanzar.
     *
     * @param socket sesión que se cerró; otras sesiones no desconectan el peer
     */
    void detach(org.springframework.web.socket.WebSocketSession socket);

    /**
     * Nombre que va en {@code /health} mientras hay fuente.
     *
     * @return {@code smpte}, {@code h264}, o {@code null} si no hay partida
     */
    String encoderName();

    /**
     * Instante del último datagrama de input aceptado.
     *
     * @return epoch millis, o {@code null} si todavía no llegó ninguno
     */
    Long lastInputMillis();
}
