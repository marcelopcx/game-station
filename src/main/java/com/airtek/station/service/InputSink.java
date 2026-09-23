package com.airtek.station.service;

import com.airtek.station.model.GameManifest;
import com.airtek.station.config.StationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import com.airtek.station.infrastructure.DatagramParser;
import com.airtek.station.infrastructure.LookFeed;
import com.airtek.station.infrastructure.UinputPad;
import com.airtek.station.infrastructure.X11Injector;

/**
 * Sink del DataChannel {@code input}.
 *
 * <p>Misma arquitectura que {@code game-station/controller/input/sink.py}: el
 * hilo del DataChannel solo encola; pads e X11 se aplican en hilos dedicados
 * con un solo flush XTEST por lote. Con {@code needs.relativeMouse}, el giro
 * va a {@link LookFeed}; teclado, botones y rueda siguen por XTEST.
 */

@Component
public class InputSink {

    private static final Logger log = LoggerFactory.getLogger(InputSink.class);
    private static final int[] REST = {0, 0, 0, 0, 0, 0};

    private final StationProperties settings;
    private final List<UinputPad> pads = new ArrayList<>();
    private final LookFeed look = new LookFeed();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition cond = lock.newCondition();

    private X11Injector x11;
    private boolean keyboard;
    private boolean mouse;
    private boolean relativeMouse;
    private int appliedSeq = -1;
    private volatile long lastInputMillis;
    private boolean loggedKeys;
    private volatile boolean stopWorkers;

    private Thread padThread;
    private Thread x11Thread;

    private Map<Integer, DatagramParser.PadSnapshot> padJob;
    private final Set<Integer> padLive = new HashSet<>();
    private Set<Integer> keysJob;
    private Integer buttonsJob;
    private int[] absJob;
    private int relXJob;
    private int relYJob;
    private int wheelJob;
    private boolean x11Dirty;

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
        resetJobs();
        stopWorkers = false;
        if (!pads.isEmpty()) {
            padThread = Thread.ofPlatform().daemon().name("input-pads").start(this::runPads);
        }
        if (x11 != null) {
            x11Thread = Thread.ofPlatform().daemon().name("input-x11").start(this::runX11);
        }
        log.info(
                "input open protocol=udp-latest pads={} keyboard={} mouse={} relativeMouse={} display={}",
                count, keyboard, mouse, relativeMouse, settings.getDisplay()
        );
    }

    public void close() {
        stopWorkers = true;
        lock.lock();
        try {
            cond.signalAll();
        } finally {
            lock.unlock();
        }
        joinWorker(padThread);
        joinWorker(x11Thread);
        padThread = null;
        x11Thread = null;
        for (UinputPad pad : pads) {
            pad.apply(0, REST);
            pad.close();
        }
        pads.clear();
        padLive.clear();
        look.close();
        if (x11 != null) {
            x11.close();
            x11 = null;
        }
        keyboard = false;
        mouse = false;
        relativeMouse = false;
        appliedSeq = -1;
        lastInputMillis = 0;
        loggedKeys = false;
        resetJobs();
    }

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
            if (!snap.hasMouse()) {
                continue;
            }
            if (!snap.mouseAbs()) {
                relX += snap.mouseX();
                relY += snap.mouseY();
            }
            wheel += snap.wheel();
        }
        if (mouse && relativeMouse) {
            look.add(relX, relY);
        }
        lock.lock();
        try {
            appliedSeq = latest.seq();
            lastInputMillis = System.currentTimeMillis();
            if (latest.hasPads()) {
                padJob = latest.pads();
            }
            if (latest.hasKeyboard() && keyboard) {
                keysJob = new HashSet<>(latest.keys());
                if (!loggedKeys && !keysJob.isEmpty()) {
                    loggedKeys = true;
                    log.info("input keys first count={} codes={}", keysJob.size(), keysJob);
                }
                x11Dirty = true;
            }
            if (latest.hasMouse() && mouse) {
                if (latest.mouseAbs() && !relativeMouse) {
                    absJob = new int[]{latest.mouseX(), latest.mouseY()};
                }
                if (!relativeMouse) {
                    relXJob += relX;
                    relYJob += relY;
                }
                wheelJob += wheel;
                buttonsJob = latest.mouseButtons();
                x11Dirty = true;
            }
            cond.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void resetJobs() {
        padJob = null;
        keysJob = null;
        buttonsJob = null;
        absJob = null;
        relXJob = 0;
        relYJob = 0;
        wheelJob = 0;
        x11Dirty = false;
    }

    private void runPads() {
        while (!stopWorkers) {
            Map<Integer, DatagramParser.PadSnapshot> job = null;
            lock.lock();
            try {
                while (padJob == null && !stopWorkers) {
                    await();
                }
                job = padJob;
                padJob = null;
            } finally {
                lock.unlock();
            }
            if (job != null) {
                emitPads(job);
            }
        }
    }

    private void emitPads(Map<Integer, DatagramParser.PadSnapshot> padsState) {
        Set<Integer> live = new HashSet<>();
        for (DatagramParser.PadSnapshot snap : padsState.values()) {
            if (snap.slot() < 0 || snap.slot() >= pads.size()) {
                continue;
            }
            pads.get(snap.slot()).apply(snap.buttons(), snap.axes());
            live.add(snap.slot());
        }
        for (int slot : new HashSet<>(padLive)) {
            if (!live.contains(slot) && slot < pads.size()) {
                pads.get(slot).apply(0, REST);
            }
        }
        padLive.clear();
        padLive.addAll(live);
    }

    private void runX11() {
        while (!stopWorkers) {
            int[] abs = null;
            int relX = 0;
            int relY = 0;
            int wheel = 0;
            Integer buttons = null;
            Set<Integer> keys = null;
            lock.lock();
            try {
                while (!x11Dirty && !stopWorkers) {
                    await();
                }
                if (!x11Dirty) {
                    continue;
                }
                abs = absJob;
                relX = relXJob;
                relY = relYJob;
                wheel = wheelJob;
                buttons = buttonsJob;
                keys = keysJob;
                absJob = null;
                relXJob = 0;
                relYJob = 0;
                wheelJob = 0;
                x11Dirty = false;
            } finally {
                lock.unlock();
            }
            X11Injector injector = x11;
            if (injector == null) {
                continue;
            }
            injector.inject(
                    mouse ? abs : null,
                    mouse && !relativeMouse ? relX : 0,
                    mouse && !relativeMouse ? relY : 0,
                    mouse ? wheel : 0,
                    mouse ? buttons : null,
                    keyboard ? keys : null
            );
        }
    }

    private void await() {
        try {
            cond.awaitNanos(250_000_000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinWorker(Thread thread) {
        if (thread == null || !thread.isAlive()) {
            return;
        }
        try {
            thread.join(2000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
