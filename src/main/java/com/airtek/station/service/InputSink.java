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
 * <p>Los pads se crean siempre, aunque {@code /dev/uinput} falle, para que
 * el slot del cliente no se corra. Teclado y mouse van a XTEST solo si el
 * manifiesto los pide. {@link #lastInputMillis()} alimenta el timeout de idle
 * (0 = todavía no hubo un datagrama válido).
 */

@Component
public class InputSink {

    private static final Logger log = LoggerFactory.getLogger(InputSink.class);
    private static final int[] REST = {0, 0, 0, 0, 0, 0};

    private final StationProperties settings;
    private final List<UinputPad> pads = new ArrayList<>();
    private X11Injector x11;
    private boolean keyboard;
    private boolean mouse;
    private int appliedSeq = -1;
    private volatile long lastInputMillis;

    public InputSink(StationProperties settings) {
        this.settings = settings;
    }

    public long lastInputMillis() {
        return lastInputMillis;
    }

    /**
     * Crea un pad por cada slot pedido por {@code needs.gamepad} (tope 4) y,
     * si el manifiesto pide teclado o ratón, el inyector XTEST del display
     * configurado. Un {@code open} fallido del pad no saca el slot: el índice
     * del cliente tiene que coincidir con el del kernel.
     *
     * @param manifest juego que acaba de pasar a {@code PLAYING}
     */
    public void open(GameManifest manifest) {
        close();
        GameManifest.Needs needs = manifest.getNeeds();
        keyboard = needs.isKeyboard();
        mouse = needs.isMouse();
        int count = Math.max(0, Math.min(4, needs.getGamepad()));
        for (int i = 0; i < count; i++) {
            UinputPad pad = new UinputPad(i);
            pad.open();
            pads.add(pad);
        }
        if (keyboard || mouse) {
            x11 = new X11Injector(settings.getDisplay(), settings.width(), settings.height());
        }
        log.info(
                "input open protocol=udp-latest pads={} keyboard={} mouse={} display={}",
                count, keyboard, mouse, settings.getDisplay()
        );
    }

    /**
     * Suelta los botones, cierra los fd de uinput y la conexión X. Deja el
     * sink listo para otro {@link #open}.
     */
    public void close() {
        for (UinputPad pad : pads) {
            pad.apply(0, REST);
            pad.close();
        }
        pads.clear();
        if (x11 != null) {
            x11.close();
            x11 = null;
        }
        keyboard = false;
        mouse = false;
        appliedSeq = -1;
    }

    /**
     * Aplica el snapshot más nuevo del datagrama si su {@code seq} avanza.
     * dx, dy y la rueda de los snapshots relativos pendientes se suman; la
     * rueda de los absolutos pendientes también. Posición absoluta, botones,
     * teclas y pads salen solo del último. Un buffer que no parsea se
     * loguea y se ignora.
     *
     * @param data payload binario del DataChannel {@code input}
     */
    public void handle(byte[] data) {
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
        appliedSeq = latest.seq();
        lastInputMillis = System.currentTimeMillis();
        if (latest.hasPads()) {
            applyPads(latest.pads());
        }
        if (x11 != null && (latest.hasKeyboard() || latest.hasMouse())) {
            int[] abs = null;
            Integer buttons = null;
            Set<Integer> keys = null;
            if (mouse && latest.hasMouse()) {
                if (latest.mouseAbs()) {
                    abs = new int[]{latest.mouseX(), latest.mouseY()};
                }
                buttons = latest.mouseButtons();
            }
            if (keyboard && latest.hasKeyboard()) {
                keys = latest.keys();
            }
            x11.inject(
                    abs,
                    mouse ? relX : 0,
                    mouse ? relY : 0,
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
