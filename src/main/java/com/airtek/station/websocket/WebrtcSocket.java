package com.airtek.station.websocket;

import com.airtek.station.service.StationService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

/**
 * WebSocket {@code /ws/webrtc}. Signaling de SDP e ICE, no media.
 *
 * <p>Al abrir, si la estación no está en {@code PLAYING} el servicio manda
 * {@code { type: ERROR, code: NOT_PLAYING }} y cierra. Cada texto se reenvía
 * a {@link com.airtek.station.service.MediaSession#onSignal}. El close libera
 * el peer para que otro browser pueda negociar sin relanzar el juego.
 */

@Component
public class WebrtcSocket extends TextWebSocketHandler {

    private final StationService station;

    public WebrtcSocket(StationService station) {
        this.station = station;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        station.attachWebrtc(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        station.onWebrtc(session, message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        station.detachWebrtc(session);
    }
}
