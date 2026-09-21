package com.airtek.station.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Receta bakeada en la imagen ({@code manifest.yaml}). Un contenedor sirve un
 * solo {@code id}. El runtime no compara {@code gameId} con un string de
 * título: ejecuta {@code command} + {@code args} y aplica {@code env} y
 * {@code needs}.
 *
 * <p>{@code kind=test-pattern} (o {@code command} vacío) no arranca Xvfb ni
 * binario: la fuente de video es sintética. {@code needs.gamepad} acepta
 * un entero {@code 0..4} o un booleano ({@code true} equivale a 1).
 * {@code audio.fallbackArgs}
 * se concatena al argv si Pulse no está.
 *
 * <p>{@link Needs#toWire()} es el objeto {@code needs} que ven {@code /health}
 * y la respuesta de {@code /launch}. El cliente habilita pad, teclado y mouse
 * con esos flags.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public class GameManifest {

    private String id;
    private String version = "1.0.0";
    private String kind = "native";
    private List<String> command = new ArrayList<>();
    private List<String> args = new ArrayList<>();
    private Map<String, String> env = new LinkedHashMap<>();
    private String workdir;
    private Needs needs = new Needs();
    private Audio audio = new Audio();

    public boolean testPattern() {
        return "test-pattern".equals(kind) || command == null || command.isEmpty();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public List<String> getCommand() {
        return command;
    }

    public void setCommand(List<String> command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    public String getWorkdir() {
        return workdir;
    }

    public void setWorkdir(String workdir) {
        this.workdir = workdir;
    }

    public Needs getNeeds() {
        return needs;
    }

    public void setNeeds(Needs needs) {
        this.needs = needs == null ? new Needs() : needs;
    }

    public Audio getAudio() {
        return audio;
    }

    public void setAudio(Audio audio) {
        this.audio = audio == null ? new Audio() : audio;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Needs {
        private boolean display = true;
        private boolean audio = true;
        private int gamepad;
        private boolean keyboard;
        private boolean mouse;

        public boolean isDisplay() {
            return display;
        }

        public void setDisplay(boolean display) {
            this.display = display;
        }

        public boolean isAudio() {
            return audio;
        }

        public void setAudio(boolean audio) {
            this.audio = audio;
        }

        public int getGamepad() {
            return gamepad;
        }

        public void setGamepad(JsonNode value) {
            if (value == null || value.isNull()) {
                gamepad = 0;
            } else if (value.isBoolean()) {
                gamepad = value.booleanValue() ? 1 : 0;
            } else {
                int n = value.asInt(0);
                gamepad = (n >= 0 && n <= 4) ? n : 0;
            }
        }

        public boolean isKeyboard() {
            return keyboard;
        }

        public void setKeyboard(boolean keyboard) {
            this.keyboard = keyboard;
        }

        public boolean isMouse() {
            return mouse;
        }

        public void setMouse(boolean mouse) {
            this.mouse = mouse;
        }

        public Map<String, Object> toWire() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("display", display);
            body.put("audio", audio);
            body.put("gamepad", gamepad);
            body.put("keyboard", keyboard);
            body.put("mouse", mouse);
            return body;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Audio {
        private List<String> fallbackArgs = new ArrayList<>();

        public List<String> getFallbackArgs() {
            return fallbackArgs;
        }

        public void setFallbackArgs(List<String> fallbackArgs) {
            this.fallbackArgs = fallbackArgs == null ? List.of() : fallbackArgs;
        }
    }
}
