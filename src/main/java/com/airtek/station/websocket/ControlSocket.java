package com.airtek.station.websocket;

import com.airtek.station.service.StationService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket {@code /ws/control}. El servidor empuja {@code STATE} y
 * {@code ERROR}. El texto que mande el cliente se ignora: no hay comandos
 * en este canal. La suscripción entra al
 * {@link com.airtek.station.service.ControlHub} al abrir y sale al cerrar.
 */

@Component
public class ControlSocket extends TextWebSocketHandler {

    private final StationService station;

    public ControlSocket(StationService station) {
        this.station = station;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        station.connectControl(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        /* el cliente no manda comandos por este socket */
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        station.disconnectControl(session);
    }
}
