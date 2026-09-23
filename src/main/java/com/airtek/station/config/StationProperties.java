package com.airtek.station.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

@ConfigurationProperties(prefix = "station")
public class StationProperties {

    private String id = "spark-1";
    private String display = ":99";
    private String size = "1280x720";
    private String fps = "60";
    private String videoBitrate = "5000000";
    private String libraryRoot = "/opt/station-library";
    private String cacheRoot = "/tmp/airtek-cache";
    private double idleTimeoutS = 300;
    private String gameManifest = "";
    private String corsOrigins = "http://localhost:5173,http://127.0.0.1:5173";
    private String iceServers = "stun:stun.l.google.com:19302";
    private String iceHostIps = "";
    private String iceHostPolicy = "all";

    public int width() {
        String[] parts = size.split("x", 2);
        if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
            return Integer.parseInt(parts[0].trim());
        }
        return 1280;
    }

    public int height() {
        String[] parts = size.split("x", 2);
        if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
            return Integer.parseInt(parts[1].trim());
        }
        return 720;
    }

    public int fpsInt() {
        try {
            int n = Integer.parseInt(fps.trim());
            return n > 0 ? n : 60;
        } catch (NumberFormatException ex) {
            return 60;
        }
    }

    public int videoBitrate() {
        try {
            int n = Integer.parseInt(videoBitrate.trim());
            return n > 0 ? n : 5_000_000;
        } catch (NumberFormatException ex) {
            return 5_000_000;
        }
    }

    public List<String> corsOriginList() {
        return split(corsOrigins);
    }

    public List<String> iceServerList() {
        return split(iceServers);
    }

    public List<String> iceHostIpList() {
        return split(iceHostIps);
    }

    public String interpolate(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("${WIDTH}", Integer.toString(width()))
                .replace("${HEIGHT}", Integer.toString(height()))
                .replace("${SIZE}", size)
                .replace("${CACHE}", cacheRoot)
                .replace("${LIBRARY}", libraryRoot)
                .replace("${DISPLAY}", display)
                .replace("${FPS}", fps);
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplay() {
        return display;
    }

    public void setDisplay(String display) {
        this.display = display;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getFps() {
        return fps;
    }

    public void setFps(String fps) {
        this.fps = fps;
    }

    public String getVideoBitrate() {
        return videoBitrate;
    }

    public void setVideoBitrate(String videoBitrate) {
        this.videoBitrate = videoBitrate;
    }

    public String getLibraryRoot() {
        return libraryRoot;
    }

    public void setLibraryRoot(String libraryRoot) {
        this.libraryRoot = libraryRoot;
    }

    public String getCacheRoot() {
        return cacheRoot;
    }

    public void setCacheRoot(String cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public double getIdleTimeoutS() {
        return idleTimeoutS;
    }

    public void setIdleTimeoutS(double idleTimeoutS) {
        this.idleTimeoutS = idleTimeoutS;
    }

    public String getGameManifest() {
        return gameManifest;
    }

    public void setGameManifest(String gameManifest) {
        this.gameManifest = gameManifest;
    }

    public String getCorsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(String corsOrigins) {
        this.corsOrigins = corsOrigins;
    }

    public String getIceServers() {
        return iceServers;
    }

    public void setIceServers(String iceServers) {
        this.iceServers = iceServers;
    }

    public String getIceHostIps() {
        return iceHostIps;
    }

    public void setIceHostIps(String iceHostIps) {
        this.iceHostIps = iceHostIps;
    }

    public String getIceHostPolicy() {
        return iceHostPolicy;
    }

    public void setIceHostPolicy(String iceHostPolicy) {
        this.iceHostPolicy = iceHostPolicy;
    }
}
