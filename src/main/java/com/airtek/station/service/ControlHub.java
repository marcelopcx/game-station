package com.airtek.station.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fan-out de {@code /ws/control}.
 *
 * <p>Serializa el mapa a JSON y lo manda como texto a cada sesión abierta.
 * El envío está sincronizado por sesión: Tomcat no garantiza que
 * {@code WebSocketSession#sendMessage} sea thread-safe y el executor de la
 * FSM publica en paralelo con el hilo que aceptó el socket. Un envío que
 * falla saca al cliente del set. Este hub no interpreta el payload y no
 * transporta RTP.
 */

@Component
public class ControlHub {

    private static final Logger log = LoggerFactory.getLogger(ControlHub.class);

    private final Set<WebSocketSession> clients = ConcurrentHashMap.newKeySet();
    private final ObjectMapper json;

    public ControlHub(ObjectMapper json) {
        this.json = json;
    }

    public void connect(WebSocketSession session) {
        clients.add(session);
        log.info("control clients={}", clients.size());
    }

    public void disconnect(WebSocketSession session) {
        clients.remove(session);
    }

    public void broadcast(Map<String, Object> payload) {
        String text;
        try {
            text = json.writeValueAsString(payload);
        } catch (Exception ex) {
            log.warn("control json {}", ex.toString());
            return;
        }
        TextMessage message = new TextMessage(text);
        for (WebSocketSession session : clients) {
            if (!session.isOpen()) {
                clients.remove(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (Exception ex) {
                clients.remove(session);
            }
        }
    }
}
