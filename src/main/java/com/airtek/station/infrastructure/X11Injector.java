package com.airtek.station.infrastructure;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Teclado y ratón hacia el {@code DISPLAY} vía XTEST ({@code libXtst}).
 *
 * <p>Si la librería o el display no están, el primer fallo deja el inyector
 * mudo y loguea {@code input_backend=log}. No reintenta: un Xvfb caído no
 * se recupera a mitad de partida.
 *
 * <p>Keycode X = {@code KEY_*} de Linux + 8 (el offset evdev de X). Botones
 * DOM 0..4 → X 1, 2, 3, 8, 9 (izquierdo, medio, derecho, atrás, adelante).
 * La rueda son clicks de los botones 4 y 5, como máximo 8 por datagrama.
 * {@link #inject} hace un solo {@code XFlush} y no emite lo que no cambió:
 * la posición absoluta se compara con la última, los botones por XOR de la
 * máscara y las teclas por diferencia de conjuntos. {@link #close} suelta
 * teclas y botones antes de {@code XCloseDisplay}.
 */

public final class X11Injector {

    private static final Logger log = LoggerFactory.getLogger(X11Injector.class);
    private static final int LINUX_TO_X = 8;

    private final String display;
    private final int width;
    private final int height;
    private Pointer dpy;
    private boolean failed;
    private final Set<Integer> keys = new HashSet<>();
    private int buttons;
    private int absX = Integer.MIN_VALUE;
    private int absY = Integer.MIN_VALUE;

    public X11Injector(String display, int width, int height) {
        this.display = display;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    public void close() {
        if (dpy != null) {
            try {
                Xtst lib = lib();
                for (int code : keys) {
                    lib.XTestFakeKeyEvent(dpy, code + LINUX_TO_X, false, 0);
                }
                for (int bit = 0; bit < 5; bit++) {
                    if ((buttons & (1 << bit)) != 0) {
                        Integer xbtn = domButton(bit);
                        if (xbtn != null) {
                            lib.XTestFakeButtonEvent(dpy, xbtn, false, 0);
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
    }

    /**
     * Un flush de XTEST. El keycode de Linux es {@code KEY_*} + 8. Los botones
     * DOM 0..4 mapean a X 1, 2, 3, 8, 9. La rueda son los botones 4 y 5.
     * El primer fallo desactiva el inyector para el resto de la partida.
     *
     * @param abs        {@code [x, y]} absoluto en píxeles del display, o {@code null}
     * @param relX       delta horizontal acumulado
     * @param relY       delta vertical acumulado
     * @param wheel      ticks; positivo hacia arriba
     * @param buttonMask máscara DOM, o {@code null} si este flush no trae botones
     * @param wantKeys   conjunto {@code KEY_*} que debe quedar pulsado
     */
    public void inject(int[] abs, int relX, int relY, int wheel, Integer buttonMask, Set<Integer> wantKeys) {
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
            if (absX != x || absY != y) {
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
                    lib.XTestFakeButtonEvent(displayPtr, xbtn, (mask & (1 << bit)) != 0, 0);
                }
                buttons = mask;
                wrote = true;
            }
        }
        if (wheel != 0) {
            int xbtn = wheel > 0 ? 4 : 5;
            int n = Math.min(8, Math.abs(wheel));
            for (int i = 0; i < n; i++) {
                lib.XTestFakeButtonEvent(displayPtr, xbtn, true, 0);
                lib.XTestFakeButtonEvent(displayPtr, xbtn, false, 0);
            }
            wrote = true;
        }
        if (wantKeys != null) {
            for (int code : keys) {
                if (!wantKeys.contains(code)) {
                    lib.XTestFakeKeyEvent(displayPtr, code + LINUX_TO_X, false, 0);
                    wrote = true;
                }
            }
            for (int code : wantKeys) {
                if (code > 0 && !keys.contains(code)) {
                    lib.XTestFakeKeyEvent(displayPtr, code + LINUX_TO_X, true, 0);
                    wrote = true;
                }
            }
            keys.clear();
            keys.addAll(wantKeys);
        }
        if (wrote) {
            lib.XFlush(displayPtr);
        }
    }

    private Pointer connection(Xtst lib) {
        if (failed) {
            return null;
        }
        if (dpy == null) {
            dpy = lib.XOpenDisplay(display);
            if (dpy == null) {
                failed = true;
                log.warn("input_backend=log (X11 sin display {})", display);
                return null;
            }
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

        int XTestFakeKeyEvent(Pointer display, int keycode, boolean pressed, long delay);

        int XTestFakeButtonEvent(Pointer display, int button, boolean pressed, long delay);

        int XTestFakeMotionEvent(Pointer display, int screen, int x, int y, long delay);

        int XTestFakeRelativeMotionEvent(Pointer display, int dx, int dy, long delay);
    }
}
