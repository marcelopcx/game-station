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
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpSendParameters;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
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
 * Video: ffmpeg x11grab → I420 → OpenH264 en libwebrtc (ver {@code station.video-encoder}).
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
    private static final int AUDIO_RATE = 48_000;
    /** 10 ms. WebRTC entrega el audio en bloques de ese tamaño. */
    private static final int AUDIO_SAMPLES = 480;
    private static final long AUDIO_FRAME_NS = 10_000_000L;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_BYTES = AUDIO_SAMPLES * 2 * AUDIO_CHANNELS;

    private final StationProperties settings;
    private final GameRuntime runtime;
    private final InputSink input;
    private final ObjectMapper json;
    private final Object lock = new Object();

    private PeerConnectionFactory factory;
    private AudioDeviceModule audioDevices;
    private RTCPeerConnection peer;
    private CustomVideoSource videoSource;
    private VideoTrack videoTrack;
    private CustomAudioSource audioSource;
    private AudioTrack audioTrack;
    private Thread pump;
    private Thread audioPump;
    private Process ffmpeg;
    private Process audioProc;
    private final AtomicBoolean pumping = new AtomicBoolean();
    private WebSocketSession socket;
    private volatile boolean remoteReady;
    private boolean answerSent;
    private final List<RTCIceCandidate> pendingRemote = new ArrayList<>();
    private final List<RTCIceCandidate> pendingLocal = new ArrayList<>();
    private volatile String encoder;
    private int tick;
    private long videoTimestampNs;
    /** Un solo hilo: XTEST/uinput no son thread-safe y el orden importa. */
    private final ExecutorService inputExecutor =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("input", 0).factory());
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
        audioDevices = new AudioDeviceModule(AudioLayer.kDummyAudio);
        factory = new PeerConnectionFactory(factoryFieldTrials(), audioDevices);
        videoSource = new CustomVideoSource();
        audioSource = new CustomAudioSource();
        pumping.set(true);
        videoTimestampNs = 0;
        if (runtime.capturesDisplay()) {
            startAudio();
        }
        if (runtime.capturesDisplay() && startFfmpeg()) {
            encoder = "openh264";
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
            try {
                send(session, Map.of("type", "ERROR", "code", "SIGNALING", "message", ex.getMessage()));
            } catch (IOException ignored) {
            }
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
            if (audioSource != null && audioProc != null) {
                audioTrack = factory.createAudioTrack("game-audio", audioSource);
                peer.addTrack(audioTrack, List.of("game"));
                log.info("audio track=opus source=pulse");
            }
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
        flushLocalIce();
        tuneVideoSender();
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
                emitIce(candidate);
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
                        inputExecutor.execute(() -> input.handle(bytes));
                    }
                });
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                log.info("connectionstate={}", state);
                if (state == RTCPeerConnectionState.CONNECTED) {
                    tuneVideoSender();
                }
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

    private void emitIce(RTCIceCandidate candidate) {
        synchronized (lock) {
            if (!answerSent) {
                pendingLocal.add(candidate);
                return;
            }
        }
        sendIce(candidate);
    }

    private void flushLocalIce() {
        List<RTCIceCandidate> batch;
        synchronized (lock) {
            answerSent = true;
            batch = new ArrayList<>(pendingLocal);
            pendingLocal.clear();
        }
        for (RTCIceCandidate candidate : batch) {
            sendIce(candidate);
        }
    }

    private void sendIce(RTCIceCandidate candidate) {
        String line = browserCandidate(candidate.sdp);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "ICE");
        payload.put("candidate", line);
        if (candidate.sdpMid != null && !candidate.sdpMid.isBlank()) {
            payload.put("sdpMid", candidate.sdpMid);
        }
        if (candidate.sdpMLineIndex >= 0) {
            payload.put("sdpMLineIndex", candidate.sdpMLineIndex);
        }
        log.info("ice out mid={} index={} line={}", candidate.sdpMid, candidate.sdpMLineIndex, line);
        try {
            send(socket, payload);
        } catch (IOException ex) {
            log.debug("ice send {}", ex.toString());
        }
    }

    private static String browserCandidate(String sdp) {
        String line = sdp == null ? "" : sdp.trim();
        if (line.startsWith("a=")) {
            line = line.substring(2).trim();
        }
        if (!line.startsWith("candidate:")) {
            line = "candidate:" + line;
        }
        return line;
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

    private static Map<String, String> factoryFieldTrials() {
        return Map.of(
                "WebRTC-VideoRateControl", "webrtc:std",
                "WebRTC-LowLatencyRenderer", "Enabled"
        );
    }

    private boolean startFfmpeg() {
        List<String> cmd = List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-probesize", "32", "-analyzeduration", "0",
                "-fflags", "nobuffer", "-flags", "low_delay",
                "-f", "x11grab", "-draw_mouse", "0",
                "-thread_queue_size", "2",
                "-framerate", Integer.toString(settings.fpsInt()),
                "-video_size", settings.width() + "x" + settings.height(),
                "-i", settings.getDisplay(),
                "-an",
                "-f", "rawvideo", "-pix_fmt", "yuv420p",
                "pipe:1"
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

    private void startAudio() {
        String device = System.getenv().getOrDefault("STATION_PULSE_SOURCE", "game.monitor");
        String pulse = System.getenv().getOrDefault("PULSE_SERVER", "unix:/tmp/pulse/native");
        List<List<String>> commands = List.of(
                List.of(
                        "parec", "--raw", "--format=s16le",
                        "--rate=" + AUDIO_RATE, "--channels=" + AUDIO_CHANNELS,
                        "--device=" + device, "--latency-msec=5"
                ),
                List.of(
                        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                        "-f", "pulse", "-i", device,
                        "-ac", Integer.toString(AUDIO_CHANNELS),
                        "-ar", Integer.toString(AUDIO_RATE),
                        "-f", "s16le", "pipe:1"
                )
        );
        for (List<String> cmd : commands) {
            try {
                ProcessBuilder builder = new ProcessBuilder(cmd);
                builder.environment().put("PULSE_SERVER", pulse);
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process proc = builder.start();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                if (!proc.isAlive()) {
                    log.warn("audio {} exited {}", cmd.get(0), proc.exitValue());
                    proc.destroyForcibly();
                    continue;
                }
                audioProc = proc;
                audioPump = Thread.ofPlatform().name("audio").start(this::readAudio);
                log.info("audio source=pulse device={} via={}", device, cmd.get(0));
                return;
            } catch (IOException ex) {
                log.warn("audio {} failed: {}", cmd.get(0), ex.toString());
                audioProc = null;
            }
        }
    }

    private void readAudio() {
        Process proc = audioProc;
        if (proc == null) {
            return;
        }
        byte[] frame = new byte[AUDIO_BYTES];
        boolean heard = false;
        long next = System.nanoTime();
        try (InputStream in = proc.getInputStream()) {
            while (pumping.get()) {
                if (!readFully(in, frame)) {
                    break;
                }
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                }
                next += AUDIO_FRAME_NS;
                if (next < System.nanoTime() - 5 * AUDIO_FRAME_NS) {
                    next = System.nanoTime();
                }
                if (!heard) {
                    for (byte sample : frame) {
                        if (sample != 0) {
                            heard = true;
                            log.info("audio signal=yes");
                            break;
                        }
                    }
                }
                CustomAudioSource source = audioSource;
                if (source != null) {
                    source.pushAudio(frame.clone(), 16, AUDIO_RATE, AUDIO_CHANNELS, AUDIO_SAMPLES);
                }
            }
        } catch (Exception ex) {
            if (pumping.get()) {
                log.warn("audio {}", ex.toString());
            }
        }
    }

    private void readFfmpeg() {
        int width = settings.width();
        int height = settings.height();
        int size = width * height * 3 / 2;
        byte[] latest = new byte[size];
        byte[] scratch = new byte[size];
        byte[] newer = new byte[size];
        Object frameLock = new Object();
        AtomicBoolean hasFrame = new AtomicBoolean(false);
        Thread grabber = Thread.ofPlatform().name("x11grab").start(() -> {
            try (InputStream in = ffmpeg.getInputStream()) {
                while (pumping.get()) {
                    if (!readFully(in, scratch)) {
                        break;
                    }
                    byte[] chosen = scratch;
                    byte[] spare = newer;
                    while (in.available() >= size && readFully(in, spare)) {
                        chosen = spare;
                        spare = chosen == scratch ? newer : scratch;
                    }
                    synchronized (frameLock) {
                        System.arraycopy(chosen, 0, latest, 0, size);
                        hasFrame.set(true);
                    }
                }
            } catch (Exception ex) {
                if (pumping.get()) {
                    log.warn("x11grab {}", ex.toString());
                }
            }
        });
        long periodNs = 1_000_000_000L / Math.max(1, settings.fpsInt());
        long nextPushNs = System.nanoTime() + periodNs;
        try {
            while (pumping.get()) {
                long waitNs = nextPushNs - System.nanoTime();
                if (waitNs > 0) {
                    LockSupport.parkNanos(waitNs);
                }
                if (!hasFrame.get()) {
                    continue;
                }
                byte[] pushCopy = new byte[size];
                synchronized (frameLock) {
                    System.arraycopy(latest, 0, pushCopy, 0, size);
                }
                pushI420(pushCopy, width, height);
                nextPushNs += periodNs;
                long behind = System.nanoTime() - nextPushNs;
                if (behind > periodNs * 2L) {
                    nextPushNs = System.nanoTime() + periodNs;
                }
            }
        } finally {
            grabber.interrupt();
            try {
                grabber.join(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void tuneVideoSender() {
        RTCPeerConnection connection;
        synchronized (lock) {
            connection = peer;
        }
        if (connection == null) {
            return;
        }
        int bitrate = settings.videoBitrate();
        int fps = settings.fpsInt();
        for (RTCRtpSender sender : connection.getSenders()) {
            MediaStreamTrack track = sender.getTrack();
            if (track == null || !MediaStreamTrack.VIDEO_TRACK_KIND.equals(track.getKind())) {
                continue;
            }
            RTCRtpSendParameters params = sender.getParameters();
            if (params.encodings == null || params.encodings.isEmpty()) {
                log.warn("video encode params unavailable");
                return;
            }
            RTCRtpEncodingParameters enc = params.encodings.get(0);
            enc.maxBitrate = bitrate;
            enc.minBitrate = Math.min(400_000, Math.max(200_000, bitrate / 10));
            enc.maxFramerate = (double) fps;
            sender.setParameters(params);
            log.info("video encode maxBitrate={} minBitrate={} maxFps={}", enc.maxBitrate, enc.minBitrate, fps);
            return;
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
        long periodNs = 1_000_000_000L / Math.max(1, settings.fpsInt());
        if (videoTimestampNs == 0) {
            videoTimestampNs = System.nanoTime();
        } else {
            videoTimestampNs += periodNs;
        }
        VideoFrame frame = new VideoFrame(buffer, videoTimestampNs);
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
        if (audioProc != null) {
            audioProc.destroyForcibly();
            audioProc = null;
        }
        if (pump != null) {
            pump.interrupt();
            pump = null;
        }
        if (audioPump != null) {
            audioPump.interrupt();
            try {
                audioPump.join(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            audioPump = null;
        }
        if (audioSource != null) {
            CustomAudioSource source = audioSource;
            audioSource = null;
            disposeNative(source::dispose);
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
        if (audioDevices != null) {
            AudioDeviceModule devices = audioDevices;
            audioDevices = null;
            disposeNative(devices::dispose);
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
        answerSent = false;
        pendingRemote.clear();
        pendingLocal.clear();
        VideoTrack track = videoTrack;
        AudioTrack sound = audioTrack;
        RTCPeerConnection current = peer;
        videoTrack = null;
        audioTrack = null;
        peer = null;
        if (current != null) {
            disposeNative(current::close);
        }
        if (track != null) {
            disposeNative(track::dispose);
        }
        if (sound != null) {
            disposeNative(sound::dispose);
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
