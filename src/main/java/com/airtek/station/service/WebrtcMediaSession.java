package com.airtek.station.service;

import com.airtek.station.config.StationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionIceErrorEvent;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Peer WebRTC de la partida y fuente de video.
 *
 * <p>El browser es offerer. {@link #onSignal} aplica el SDP remoto, crea el
 * answer con el track de video ya agregado y lo devuelve como
 * {@code { type: ANSWER, sdp }}. Los ICE salientes se filtran: si
 * {@code ICE_HOST_IPS} está seteado solo se anuncian esos host candidates;
 * con {@code ICE_HOST_POLICY=public} se descartan loopback, link-local y
 * RFC1918. Los {@code srflx}/{@code relay} pasan. Los ICE remotos que llegan
 * antes del {@code setRemoteDescription} se encolan.
 *
 * <p>El video es I420 empujado a un {@code CustomVideoSource}. Si el
 * manifiesto pide display y {@code ffmpeg} está en el PATH, se captura
 * {@code x11grab} de {@code station.display}. Si no, un hilo pinta barras
 * SMPTE ({@code encoder=smpte}) para el camino {@code test-pattern}.
 * libwebrtc elige el codec del offer (H.264 o VP8); no hay NVENC en este
 * proceso.
 *
 * <p>El DataChannel lo crea el browser (label {@code input}, unordered,
 * {@code maxRetransmits=0}). {@code onDataChannel} registra el observer que
 * entrega los bytes a {@link InputSink}. Un segundo socket simultáneo recibe
 * {@code PEER_BUSY} y se cierra. {@link #detach} cierra el peer y deja la
 * fuente viva para otro browser; {@link #stop} tira fuente, peer, input y
 * procesos.
 *
 * <p>El factory nativo se crea en {@link #startSource}, no en el arranque,
 * para que {@code GET /health} no cargue la librería.
 */

@Component
public class WebrtcMediaSession implements MediaSession {

    private static final Logger log = LoggerFactory.getLogger(WebrtcMediaSession.class);

    private final StationProperties settings;
    private final GameRuntime runtime;
    private final InputSink input;
    private final ObjectMapper json;
    private final Object lock = new Object();

    private PeerConnectionFactory factory;
    private RTCPeerConnection peer;
    private CustomVideoSource videoSource;
    private VideoTrack videoTrack;
    private Thread pump;
    private Process ffmpeg;
    private final AtomicBoolean pumping = new AtomicBoolean();
    private WebSocketSession socket;
    private volatile boolean remoteReady;
    private final List<RTCIceCandidate> pendingRemote = new ArrayList<>();
    private volatile String encoder;
    private int tick;

    public WebrtcMediaSession(StationProperties settings, GameRuntime runtime, InputSink input, ObjectMapper json) {
        this.settings = settings;
        this.runtime = runtime;
        this.input = input;
        this.json = json;
    }

    @Override
    public void startSource(String gameId) {
        closePeer();
        stopMedia();
        runtime.start(gameId);
        input.open(runtime.active());
        factory = new PeerConnectionFactory();
        videoSource = new CustomVideoSource();
        pumping.set(true);
        if (runtime.capturesDisplay() && startFfmpeg()) {
            encoder = "h264";
            pump = Thread.ofPlatform().name("capture").start(this::readFfmpeg);
        } else {
            encoder = "smpte";
            pump = Thread.ofPlatform().name("smpte").start(this::paintSmpte);
        }
        log.info("start_source game={} encoder={}", gameId, encoder);
    }

    @Override
    public void stop() {
        closePeer();
        stopMedia();
        input.close();
        runtime.stop();
        encoder = null;
    }

    @Override
    public void attach(WebSocketSession session) throws IOException {
        synchronized (lock) {
            if (socket != null && socket.isOpen()) {
                send(session, Map.of("type", "ERROR", "code", "PEER_BUSY"));
                session.close();
                return;
            }
            socket = session;
            remoteReady = false;
            pendingRemote.clear();
        }
        log.info("webrtc peer attached");
    }

    @Override
    public void detach(WebSocketSession session) {
        synchronized (lock) {
            if (socket == session) {
                socket = null;
            }
        }
        closePeer();
    }

    @Override
    public void onSignal(WebSocketSession session, String text) {
        if (session != socket) {
            return;
        }
        try {
            JsonNode msg = json.readTree(text);
            String type = msg.path("type").asText("");
            if ("OFFER".equals(type)) {
                String sdp = msg.path("sdp").asText("");
                if (!sdp.isBlank()) {
                    acceptOffer(sdp);
                }
            } else if ("ICE".equals(type)) {
                String candidate = msg.path("candidate").asText("");
                if (!candidate.isBlank()) {
                    String mid = msg.path("sdpMid").isNull() ? "0" : msg.path("sdpMid").asText("0");
                    int index = msg.path("sdpMLineIndex").asInt(0);
                    if (!candidate.startsWith("candidate:")) {
                        candidate = "candidate:" + candidate;
                    }
                    addRemoteIce(new RTCIceCandidate(mid, index, candidate));
                }
            }
        } catch (Exception ex) {
            log.warn("signaling {}", ex.toString());
        }
    }

    @Override
    public String encoderName() {
        return encoder;
    }

    @Override
    public Long lastInputMillis() {
        long at = input.lastInputMillis();
        return at == 0 ? null : at;
    }

    private void acceptOffer(String sdp) throws Exception {
        synchronized (lock) {
            closePeerLocked();
            if (factory == null || videoSource == null) {
                throw new IllegalStateException("no hay fuente de video");
            }
            peer = factory.createPeerConnection(configuration(), observer());
            videoTrack = factory.createVideoTrack("game", videoSource);
            peer.addTrack(videoTrack, List.of("game"));
        }
        RTCSessionDescription offer = new RTCSessionDescription(RTCSdpType.OFFER, sdp);
        awaitSet(offer, true);
        synchronized (lock) {
            remoteReady = true;
            for (RTCIceCandidate candidate : pendingRemote) {
                peer.addIceCandidate(candidate);
            }
            pendingRemote.clear();
        }
        RTCSessionDescription answer = awaitAnswer();
        awaitSet(answer, false);
        send(socket, Map.of("type", "ANSWER", "sdp", answer.sdp));
        log.info("answer sent bytes={}", answer.sdp.length());
    }

    private void addRemoteIce(RTCIceCandidate candidate) {
        synchronized (lock) {
            if (peer != null && remoteReady) {
                peer.addIceCandidate(candidate);
            } else {
                pendingRemote.add(candidate);
            }
        }
    }

    private RTCConfiguration configuration() {
        RTCConfiguration config = new RTCConfiguration();
        for (String url : settings.iceServerList()) {
            RTCIceServer server = new RTCIceServer();
            server.urls.add(url);
            config.iceServers.add(server);
        }
        return config;
    }

    private PeerConnectionObserver observer() {
        return new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                if (candidate == null || candidate.sdp == null || !allowHost(candidate.sdp)) {
                    return;
                }
                try {
                    send(socket, Map.of(
                            "type", "ICE",
                            "candidate", candidate.sdp,
                            "sdpMid", candidate.sdpMid == null ? "0" : candidate.sdpMid,
                            "sdpMLineIndex", candidate.sdpMLineIndex
                    ));
                } catch (IOException ex) {
                    log.debug("ice send {}", ex.toString());
                }
            }

            @Override
            public void onDataChannel(RTCDataChannel channel) {
                log.info("datachannel={} protocol=udp-latest", channel.getLabel());
                if (!"input".equals(channel.getLabel())) {
                    return;
                }
                channel.registerObserver(new RTCDataChannelObserver() {
                    @Override
                    public void onBufferedAmountChange(long previousAmount) {
                    }

                    @Override
                    public void onStateChange() {
                    }

                    @Override
                    public void onMessage(RTCDataChannelBuffer buffer) {
                        if (buffer == null || buffer.data == null) {
                            return;
                        }
                        ByteBuffer data = buffer.data;
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        input.handle(bytes);
                    }
                });
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                log.info("connectionstate={}", state);
            }

            @Override
            public void onSignalingChange(RTCSignalingState state) {
            }

            @Override
            public void onIceConnectionChange(RTCIceConnectionState state) {
                log.info("ice={}", state);
            }

            @Override
            public void onStandardizedIceConnectionChange(RTCIceConnectionState state) {
            }

            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {
            }

            @Override
            public void onIceGatheringChange(RTCIceGatheringState state) {
            }

            @Override
            public void onIceCandidateError(RTCPeerConnectionIceErrorEvent event) {
            }

            @Override
            public void onAddStream(MediaStream stream) {
            }

            @Override
            public void onRemoveStream(MediaStream stream) {
            }

            @Override
            public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] streams) {
            }

            @Override
            public void onRemoveTrack(RTCRtpReceiver receiver) {
            }

            @Override
            public void onTrack(RTCRtpTransceiver transceiver) {
            }

            @Override
            public void onRenegotiationNeeded() {
            }
        };
    }

    private boolean allowHost(String line) {
        String[] parts = line.replaceFirst("^candidate:", "").trim().split("\\s+");
        if (parts.length < 8) {
            return true;
        }
        String ip = parts[4];
        String kind = parts[7];
        if (!"host".equals(kind)) {
            return true;
        }
        List<String> explicit = settings.iceHostIpList();
        if (!explicit.isEmpty()) {
            return explicit.contains(ip);
        }
        if (!"public".equalsIgnoreCase(settings.getIceHostPolicy())) {
            return true;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            return !(address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress());
        } catch (Exception ex) {
            return false;
        }
    }

    private void awaitSet(RTCSessionDescription description, boolean remote) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        SetSessionDescriptionObserver observer = new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                done.complete(null);
            }

            @Override
            public void onFailure(String error) {
                done.completeExceptionally(new IllegalStateException(error));
            }
        };
        synchronized (lock) {
            if (remote) {
                peer.setRemoteDescription(description, observer);
            } else {
                peer.setLocalDescription(description, observer);
            }
        }
        done.get(8, TimeUnit.SECONDS);
    }

    private RTCSessionDescription awaitAnswer() throws Exception {
        CompletableFuture<RTCSessionDescription> done = new CompletableFuture<>();
        synchronized (lock) {
            peer.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    done.complete(description);
                }

                @Override
                public void onFailure(String error) {
                    done.completeExceptionally(new IllegalStateException(error));
                }
            });
        }
        return done.get(8, TimeUnit.SECONDS);
    }

    private boolean startFfmpeg() {
        List<String> cmd = List.of(
                "ffmpeg", "-loglevel", "error",
                "-f", "x11grab", "-draw_mouse", "0",
                "-framerate", Integer.toString(settings.fpsInt()),
                "-video_size", settings.width() + "x" + settings.height(),
                "-i", settings.getDisplay(),
                "-f", "rawvideo", "-pix_fmt", "yuv420p", "-an", "pipe:1"
        );
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.environment().put("DISPLAY", settings.getDisplay());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            ffmpeg = builder.start();
            log.info("capture ffmpeg {}", settings.getDisplay());
            return true;
        } catch (IOException ex) {
            log.warn("ffmpeg no disponible ({}); barras SMPTE", ex.getMessage());
            return false;
        }
    }

    private void readFfmpeg() {
        int width = settings.width();
        int height = settings.height();
        int size = width * height * 3 / 2;
        byte[] frame = new byte[size];
        try (InputStream in = ffmpeg.getInputStream()) {
            while (pumping.get()) {
                if (!readFully(in, frame)) {
                    break;
                }
                pushI420(frame, width, height);
            }
        } catch (Exception ex) {
            if (pumping.get()) {
                log.warn("capture {}", ex.toString());
            }
        }
    }

    private void paintSmpte() {
        int width = settings.width();
        int height = settings.height();
        int ySize = width * height;
        int cSize = (width / 2) * (height / 2);
        byte[] frame = new byte[ySize + cSize * 2];
        long period = Math.max(16, 1000L / settings.fpsInt());
        while (pumping.get()) {
            fillBars(frame, width, height, tick++);
            pushI420(frame, width, height);
            try {
                Thread.sleep(period);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pushI420(byte[] raw, int width, int height) {
        CustomVideoSource source = videoSource;
        if (source == null) {
            return;
        }
        NativeI420Buffer buffer = NativeI420Buffer.allocate(width, height);
        copyPlane(raw, 0, buffer.getDataY(), buffer.getStrideY(), width, height);
        int chroma = width * height;
        copyPlane(raw, chroma, buffer.getDataU(), buffer.getStrideU(), width / 2, height / 2);
        copyPlane(raw, chroma + (width / 2) * (height / 2), buffer.getDataV(), buffer.getStrideV(), width / 2, height / 2);
        VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
        source.pushFrame(frame);
        frame.release();
    }

    private static void copyPlane(byte[] src, int offset, ByteBuffer dst, int stride, int width, int height) {
        dst.clear();
        for (int row = 0; row < height; row++) {
            dst.position(row * stride);
            dst.put(src, offset + row * width, width);
        }
    }

    private static void fillBars(byte[] frame, int width, int height, int tick) {
        int[] yv = {235, 210, 170, 145, 106, 81, 41, 16};
        int[] u = {128, 16, 166, 54, 202, 90, 240, 128};
        int[] v = {128, 146, 16, 34, 222, 240, 110, 128};
        int shift = (tick / 15) % 8;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int band = ((x * 8 / width) + shift) % 8;
                frame[y * width + x] = (byte) yv[band];
            }
        }
        int chroma = width * height;
        int cw = width / 2;
        int ch = height / 2;
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int band = ((x * 8 / cw) + shift) % 8;
                frame[chroma + y * cw + x] = (byte) u[band];
                frame[chroma + cw * ch + y * cw + x] = (byte) v[band];
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    private void stopMedia() {
        pumping.set(false);
        if (ffmpeg != null) {
            ffmpeg.destroyForcibly();
            ffmpeg = null;
        }
        if (pump != null) {
            pump.interrupt();
            pump = null;
        }
        if (videoSource != null) {
            CustomVideoSource source = videoSource;
            videoSource = null;
            disposeNative(source::dispose);
        }
        if (factory != null) {
            PeerConnectionFactory current = factory;
            factory = null;
            disposeNative(current::dispose);
        }
    }

    private void closePeer() {
        synchronized (lock) {
            closePeerLocked();
            socket = null;
        }
    }

    private void closePeerLocked() {
        remoteReady = false;
        pendingRemote.clear();
        VideoTrack track = videoTrack;
        RTCPeerConnection current = peer;
        videoTrack = null;
        peer = null;
        if (current != null) {
            disposeNative(current::close);
        }
        if (track != null) {
            disposeNative(track::dispose);
        }
    }

    private static void disposeNative(Runnable action) {
        try {
            action.run();
        } catch (Throwable ex) {
            log.debug("webrtc dispose {}", ex.toString());
        }
    }

    private void send(WebSocketSession session, Map<String, Object> payload) throws IOException {
        if (session == null || !session.isOpen()) {
            return;
        }
        synchronized (session) {
            session.sendMessage(new TextMessage(json.writeValueAsString(payload)));
        }
    }
}
