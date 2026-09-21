package com.airtek.station.config;

import com.airtek.station.websocket.ControlSocket;
import com.airtek.station.websocket.WebrtcSocket;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@org.springframework.boot.context.properties.EnableConfigurationProperties(StationProperties.class)
public class StationConfig implements WebMvcConfigurer, WebSocketConfigurer {

    private final ControlSocket control;
    private final WebrtcSocket webrtc;

    public StationConfig(ControlSocket control, WebrtcSocket webrtc) {
        this.control = control;
        this.webrtc = webrtc;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(control, "/ws/control").setAllowedOriginPatterns("*");
        registry.addHandler(webrtc, "/ws/webrtc").setAllowedOriginPatterns("*");
    }
}
