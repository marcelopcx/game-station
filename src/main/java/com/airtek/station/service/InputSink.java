package com.airtek.station.service;

import com.airtek.station.model.GameManifest;
import com.airtek.station.config.StationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.airtek.station.infrastructure.DatagramParser;
import com.airtek.station.infrastructure.LookFeed;
import com.airtek.station.infrastructure.UinputPad;
import com.airtek.station.infrastructure.X11Injector;

/**
 * Sink del DataChannel {@code input}.
 *
 * <p>El browser manda datagramas (magic {@code 0xA7}, versión 3) con hasta
 * cuatro snapshots. {@link #handle(byte[])} descarta secuencias viejas
 * (comparación circular de 16 bits) y aplica solo lo nuevo: el pad más
 * reciente gana; el mouse relativo acumula dx/dy/rueda a lo largo de los
 * snapshots pendientes; el mouse absoluto pisa la posición.
 *
 * <p>Con {@code needs.relativeMouse}, el giro va a {@link LookFeed} (SDL);
 * XTEST recibe botones, rueda y posición absoluta solo si no hay look relativo.
 */

@Component
public class InputSink {

    private static final Logger log = LoggerFactory.getLogger(InputSink.class);
    private static final int[] REST = {0, 0, 0, 0, 0, 0};

    private final StationProperties settings;
    private final List<UinputPad> pads = new ArrayList<>();
    private final LookFeed look = new LookFeed();
    private X11Injector x11;
    private boolean keyboard;
    private boolean mouse;
    private boolean relativeMouse;
    private int lastAbsX = Integer.MIN_VALUE;
    private int lastAbsY = Integer.MIN_VALUE;
    private int appliedSeq = -1;
    private volatile long lastInputMillis;
    private boolean loggedKeys;

    public InputSink(StationProperties settings) {
        this.settings = settings;
    }

    public long lastInputMillis() {
        return lastInputMillis;
    }

    public void open(GameManifest manifest) {
        close();
        GameManifest.Needs needs = manifest.getNeeds();
        keyboard = needs.isKeyboard();
        mouse = needs.isMouse();
        relativeMouse = needs.isRelativeMouse();
        lastAbsX = Integer.MIN_VALUE;
        lastAbsY = Integer.MIN_VALUE;
        int count = Math.max(0, Math.min(4, needs.getGamepad()));
        for (int i = 0; i < count; i++) {
            UinputPad pad = new UinputPad(i);
            pad.open();
            pads.add(pad);
        }
        if (relativeMouse) {
            look.open();
        }
        if (keyboard || mouse) {
            x11 = new X11Injector(settings.getDisplay(), settings.width(), settings.height());
        }
        log.info(
                "input open protocol=udp-latest pads={} keyboard={} mouse={} relativeMouse={} display={}",
                count, keyboard, mouse, relativeMouse, settings.getDisplay()
        );
    }

    public void close() {
        for (UinputPad pad : pads) {
            pad.apply(0, REST);
            pad.close();
        }
        pads.clear();
        look.close();
        if (x11 != null) {
            x11.close();
            x11 = null;
        }
        keyboard = false;
        mouse = false;
        relativeMouse = false;
        lastAbsX = Integer.MIN_VALUE;
        lastAbsY = Integer.MIN_VALUE;
        appliedSeq = -1;
        lastInputMillis = 0;
        loggedKeys = false;
    }

    public synchronized void handle(byte[] data) {
        List<DatagramParser.Snapshot> snaps = DatagramParser.parse(data);
        if (snaps == null || snaps.isEmpty()) {
            log.warn("input: datagrama inválido len={}", data == null ? 0 : data.length);
            return;
        }
        DatagramParser.Snapshot latest = snaps.get(snaps.size() - 1);
        if (appliedSeq >= 0 && !DatagramParser.newer(latest.seq(), appliedSeq)) {
            return;
        }
        List<DatagramParser.Snapshot> pending = new ArrayList<>();
        int cursor = appliedSeq;
        for (DatagramParser.Snapshot snap : snaps) {
            if (cursor < 0 || DatagramParser.newer(snap.seq(), cursor)) {
                pending.add(snap);
                cursor = snap.seq();
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        latest = pending.get(pending.size() - 1);
        int relX = 0;
        int relY = 0;
        int wheel = 0;
        for (DatagramParser.Snapshot snap : pending) {
            if (!snap.hasMouse() || snap.mouseAbs()) {
                continue;
            }
            relX += snap.mouseX();
            relY += snap.mouseY();
            wheel += snap.wheel();
        }
        for (DatagramParser.Snapshot snap : pending) {
            if (snap.hasMouse()) {
                wheel += snap.mouseAbs() ? snap.wheel() : 0;
            }
        }
        if (relativeMouse) {
            int prevX = lastAbsX;
            int prevY = lastAbsY;
            for (DatagramParser.Snapshot snap : pending) {
                if (!snap.hasMouse() || !snap.mouseAbs()) {
                    continue;
                }
                if (prevX != Integer.MIN_VALUE) {
                    relX += snap.mouseX() - prevX;
                    relY += snap.mouseY() - prevY;
                }
                prevX = snap.mouseX();
                prevY = snap.mouseY();
            }
            if (prevX != Integer.MIN_VALUE) {
                lastAbsX = prevX;
                lastAbsY = prevY;
            }
        }
        appliedSeq = latest.seq();
        lastInputMillis = System.currentTimeMillis();
        if (mouse && relativeMouse) {
            look.add(relX, relY);
        }
        if (latest.hasPads()) {
            applyPads(latest.pads());
        }
        if (x11 != null && (latest.hasKeyboard() || latest.hasMouse())) {
            int[] abs = null;
            Integer buttons = null;
            Set<Integer> keys = null;
            if (mouse && latest.hasMouse()) {
                if (latest.mouseAbs() && !relativeMouse) {
                    abs = new int[]{latest.mouseX(), latest.mouseY()};
                }
                buttons = latest.mouseButtons();
            }
            if (keyboard && latest.hasKeyboard()) {
                keys = latest.keys();
                if (!loggedKeys && keys != null && !keys.isEmpty()) {
                    loggedKeys = true;
                    log.info("input keys first count={} codes={}", keys.size(), keys);
                }
            }
            int xRel = mouse && !relativeMouse ? relX : 0;
            int yRel = mouse && !relativeMouse ? relY : 0;
            x11.inject(
                    abs,
                    xRel,
                    yRel,
                    mouse ? wheel : 0,
                    buttons,
                    keys
            );
        }
    }

    private void applyPads(Map<Integer, DatagramParser.PadSnapshot> padsState) {
        boolean[] live = new boolean[pads.size()];
        for (DatagramParser.PadSnapshot snap : padsState.values()) {
            if (snap.slot() < 0 || snap.slot() >= pads.size()) {
                continue;
            }
            pads.get(snap.slot()).apply(snap.buttons(), snap.axes());
            live[snap.slot()] = true;
        }
        for (int slot = 0; slot < pads.size(); slot++) {
            if (!live[slot]) {
                pads.get(slot).apply(0, REST);
            }
        }
    }
}
