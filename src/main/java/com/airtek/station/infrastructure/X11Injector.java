package com.airtek.station.infrastructure;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Teclado y ratón hacia el {@code DISPLAY} vía XTEST ({@code libXtst}).
 *
 * <p>Equivalente a {@code game-station/controller/input/x11.py}: un solo
 * flush por {@link #inject}, keycodes Linux + 8, reintentos al abrir display.
 */

public final class X11Injector {

    private static final Logger log = LoggerFactory.getLogger(X11Injector.class);
    private static final int LINUX_TO_X = 8;

    private final String display;
    private final int width;
    private final int height;
    private final ReentrantLock lock = new ReentrantLock();

    private Pointer dpy;
    private boolean failed;
    private int tries;
    private final Set<Integer> keys = new HashSet<>();
    private int buttons;
    private Integer absX;
    private Integer absY;

    public X11Injector(String display, int width, int height) {
        this.display = display;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    public void close() {
        lock.lock();
        try {
            if (dpy != null) {
                try {
                    Xtst lib = lib();
                    for (int code : keys) {
                        lib.XTestFakeKeyEvent(dpy, code + LINUX_TO_X, 0, 0);
                    }
                    for (int bit = 0; bit < 5; bit++) {
                        if ((buttons & (1 << bit)) != 0) {
                            Integer xbtn = domButton(bit);
                            if (xbtn != null) {
                                lib.XTestFakeButtonEvent(dpy, xbtn, 0, 0);
                            }
                        }
                    }
                    lib.XFlush(dpy);
                    lib.XCloseDisplay(dpy);
                } catch (Throwable ignored) {
                    /* el display ya se fue */
                }
                dpy = null;
            }
            keys.clear();
            buttons = 0;
            absX = null;
            absY = null;
        } finally {
            lock.unlock();
        }
    }

    public void inject(int[] abs, int relX, int relY, int wheel, Integer buttonMask, Set<Integer> wantKeys) {
        lock.lock();
        try {
            Xtst lib;
            Pointer displayPtr;
            try {
                lib = lib();
                displayPtr = connection(lib);
            } catch (Throwable ex) {
                if (!failed) {
                    failed = true;
                    log.warn("input_backend=log (X11 {})", ex.toString());
                }
                return;
            }
            if (displayPtr == null) {
                return;
            }
            boolean wrote = false;
            if (abs != null) {
                int x = Math.max(0, Math.min(width - 1, abs[0]));
                int y = Math.max(0, Math.min(height - 1, abs[1]));
                if (absX == null || absY == null || absX != x || absY != y) {
                    lib.XTestFakeMotionEvent(displayPtr, 0, x, y, 0);
                    absX = x;
                    absY = y;
                    wrote = true;
                }
            }
            if (relX != 0 || relY != 0) {
                lib.XTestFakeRelativeMotionEvent(displayPtr, relX, relY, 0);
                wrote = true;
            }
            if (buttonMask != null) {
                int mask = buttonMask & 0x1F;
                int changed = buttons ^ mask;
                if (changed != 0) {
                    for (int bit = 0; bit < 5; bit++) {
                        if ((changed & (1 << bit)) == 0) {
                            continue;
                        }
                        Integer xbtn = domButton(bit);
                        if (xbtn == null) {
                            continue;
                        }
                        lib.XTestFakeButtonEvent(displayPtr, xbtn, (mask & (1 << bit)) != 0 ? 1 : 0, 0);
                    }
                    buttons = mask;
                    wrote = true;
                }
            }
            if (wheel != 0) {
                int xbtn = wheel > 0 ? 4 : 5;
                int n = Math.min(8, Math.abs(wheel));
                for (int i = 0; i < n; i++) {
                    lib.XTestFakeButtonEvent(displayPtr, xbtn, 1, 0);
                    lib.XTestFakeButtonEvent(displayPtr, xbtn, 0, 0);
                }
                wrote = true;
            }
            if (wantKeys != null) {
                Set<Integer> target = new HashSet<>();
                for (int code : wantKeys) {
                    if (code > 0) {
                        target.add(code);
                    }
                }
                for (int code : keys) {
                    if (!target.contains(code)) {
                        lib.XTestFakeKeyEvent(displayPtr, code + LINUX_TO_X, 0, 0);
                        wrote = true;
                    }
                }
                for (int code : target) {
                    if (!keys.contains(code)) {
                        lib.XTestFakeKeyEvent(displayPtr, code + LINUX_TO_X, 1, 0);
                        wrote = true;
                    }
                }
                keys.clear();
                keys.addAll(target);
            }
            if (wrote) {
                lib.XFlush(displayPtr);
            }
        } finally {
            lock.unlock();
        }
    }

    private Pointer connection(Xtst lib) {
        if (failed) {
            return null;
        }
        if (dpy == null) {
            dpy = lib.XOpenDisplay(display);
            if (dpy == null) {
                tries++;
                if (tries >= 5) {
                    failed = true;
                    log.warn("input_backend=log (X11 sin display {})", display);
                }
                return null;
            }
            tries = 0;
            log.info("input_backend=xtest display={}", display);
        }
        return dpy;
    }

    private static Integer domButton(int dom) {
        return switch (dom) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 3;
            case 3 -> 8;
            case 4 -> 9;
            default -> null;
        };
    }

    private static Xtst lib() {
        return Native.load("Xtst", Xtst.class);
    }

    interface Xtst extends Library {
        Pointer XOpenDisplay(String name);

        int XCloseDisplay(Pointer display);

        int XFlush(Pointer display);

        int XTestFakeKeyEvent(Pointer display, int keycode, int pressed, long delay);

        int XTestFakeButtonEvent(Pointer display, int button, int pressed, long delay);

        int XTestFakeMotionEvent(Pointer display, int screen, int x, int y, long delay);

        int XTestFakeRelativeMotionEvent(Pointer display, int dx, int dy, long delay);
    }
}
