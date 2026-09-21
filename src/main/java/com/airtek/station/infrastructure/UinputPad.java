package com.airtek.station.infrastructure;

import com.sun.jna.Library;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Un gamepad Xbox 360 en {@code /dev/uinput}.
 *
 * <p>Solo abre en Linux. En macOS {@link #open()} devuelve {@code false} y
 * {@link #apply(int, int[])} no escribe: la partida de {@code test-pattern}
 * sigue. El setup es el clásico: {@code UI_SET_EVBIT/KEYBIT/ABSBIT}, write
 * de {@code uinput_user_dev} (nombre 80 bytes, {@code input_id}, {@code abs*}
 * de 64 entradas) y {@code UI_DEV_CREATE}. Los ioctl se pasan por valor; el
 * kernel lee {@code arg} como el número de bit, no como puntero.
 *
 * <p>Botones de cliente → evdev, para que el índice joydev caiga en el
 * string SDL: A={@code BTN_SOUTH}, B={@code BTN_EAST}, X={@code BTN_NORTH}
 * (el {@code BTN_X} del kernel), Y={@code BTN_WEST}, LB/RB, Select, Start,
 * sticks, Guide. Los gatillos son solo ejes ({@code ABS_Z}, {@code ABS_RZ},
 * 0..32767). El d-pad (bits 12..15) es {@code ABS_HAT0X/Y}, -1..1, Y
 * negativo hacia arriba. Cada {@code input_event} son 24 bytes (timeval de
 * 16 en 64-bit + type/code/value); el SYN_REPORT cierra el frame.
 */

public final class UinputPad {

    private static final Logger log = LoggerFactory.getLogger(UinputPad.class);

    private static final int O_RDWR = 2;
    private static final int UI_SET_EVBIT = 0x40045564;
    private static final int UI_SET_KEYBIT = 0x40045565;
    private static final int UI_SET_ABSBIT = 0x40045566;
    private static final int UI_DEV_CREATE = 0x5501;
    private static final int UI_DEV_DESTROY = 0x5502;
    private static final int EV_SYN = 0;
    private static final int EV_KEY = 1;
    private static final int EV_ABS = 3;
    private static final int BUS_USB = 0x03;
    private static final int ABS_CNT = 64;
    private static final int ABS_X = 0;
    private static final int ABS_Y = 1;
    private static final int ABS_Z = 2;
    private static final int ABS_RX = 3;
    private static final int ABS_RY = 4;
    private static final int ABS_RZ = 5;
    private static final int ABS_HAT0X = 0x10;
    private static final int ABS_HAT0Y = 0x11;

    private static final int[] CLIENT_TO_BTN = new int[17];

    static {
        CLIENT_TO_BTN[0] = 0x130;
        CLIENT_TO_BTN[1] = 0x131;
        CLIENT_TO_BTN[2] = 0x133;
        CLIENT_TO_BTN[3] = 0x134;
        CLIENT_TO_BTN[4] = 0x136;
        CLIENT_TO_BTN[5] = 0x137;
        CLIENT_TO_BTN[8] = 0x13A;
        CLIENT_TO_BTN[9] = 0x13B;
        CLIENT_TO_BTN[10] = 0x13D;
        CLIENT_TO_BTN[11] = 0x13E;
        CLIENT_TO_BTN[16] = 0x13C;
    }

    private static final int[] AXIS = {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ};

    private final int index;
    private int fd = -1;
    private int prevMask;
    private final int[] prevAxes = new int[6];
    private int hatX;
    private int hatY;

    public UinputPad(int index) {
        this.index = index;
    }

    /**
     * Registra un Xbox 360 virtual. Los botones siguen el orden joydev que
     * espera el string SDL: A, B, X ({@code BTN_NORTH}), Y ({@code BTN_WEST}),
     * LB, RB, Back, Start, Guide, sticks. Los gatillos son solo ejes
     * {@code ABS_Z} / {@code ABS_RZ}; no se registran {@code BTN_TL2}/{@code TR2}.
     * Fuera de Linux, o si {@code /dev/uinput} no abre, devuelve {@code false}
     * y {@link #apply} queda en no-op.
     *
     * @return {@code true} si {@code UI_DEV_CREATE} cerró bien
     */
    public boolean open() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return false;
        }
        try {
            fd = C.INSTANCE.open("/dev/uinput", O_RDWR);
            if (fd < 0) {
                log.warn("input_backend=log pad={} (/dev/uinput no disponible)", index);
                return false;
            }
            ioctl(UI_SET_EVBIT, EV_KEY);
            ioctl(UI_SET_EVBIT, EV_ABS);
            for (int code : CLIENT_TO_BTN) {
                if (code != 0) {
                    ioctl(UI_SET_KEYBIT, code);
                }
            }
            for (int axis : AXIS) {
                ioctl(UI_SET_ABSBIT, axis);
            }
            ioctl(UI_SET_ABSBIT, ABS_HAT0X);
            ioctl(UI_SET_ABSBIT, ABS_HAT0Y);
            writeSetup();
            if (C.INSTANCE.ioctl(fd, UI_DEV_CREATE, 0) < 0) {
                close();
                log.warn("input_backend=log pad={} (UI_DEV_CREATE)", index);
                return false;
            }
            log.info("input_backend=uinput pad={}", index);
            return true;
        } catch (Throwable ex) {
            close();
            log.warn("input_backend=log pad={} ({})", index, ex.toString());
            return false;
        }
    }

    public void close() {
        if (fd >= 0) {
            try {
                C.INSTANCE.ioctl(fd, UI_DEV_DESTROY, 0);
            } catch (Throwable ignored) {
                /* ya cerrado */
            }
            C.INSTANCE.close(fd);
            fd = -1;
        }
    }

    /**
     * Escribe solo los bits y ejes que cambiaron desde la llamada anterior.
     * La máscara se recorta a 17 bits. El d-pad (bits 12..15) sale como hat
     * {@code ABS_HAT0X}/{@code ABS_HAT0Y}, con Y negativa hacia arriba.
     * Ejes de cliente: 0 LX, 1 LY, 2 RX, 3 RY, 4 LT, 5 RT.
     *
     * @param mask botones del snapshot
     * @param axes seis muestras; si faltan, el eje no se toca
     */
    public void apply(int mask, int[] axes) {
        if (fd < 0) {
            return;
        }
        mask &= 0x1FFFF;
        boolean wrote = false;
        for (int code = 0; code < 17; code++) {
            if (code >= 12 && code <= 15) {
                continue;
            }
            int down = (mask >> code) & 1;
            int prev = (prevMask >> code) & 1;
            if (down == prev || CLIENT_TO_BTN[code] == 0) {
                continue;
            }
            emit(EV_KEY, CLIENT_TO_BTN[code], down);
            wrote = true;
        }
        int nextHatX = (mask & (1 << 14)) != 0 ? -1 : ((mask & (1 << 15)) != 0 ? 1 : 0);
        int nextHatY = (mask & (1 << 12)) != 0 ? -1 : ((mask & (1 << 13)) != 0 ? 1 : 0);
        if (nextHatX != hatX) {
            emit(EV_ABS, ABS_HAT0X, nextHatX);
            hatX = nextHatX;
            wrote = true;
        }
        if (nextHatY != hatY) {
            emit(EV_ABS, ABS_HAT0Y, nextHatY);
            hatY = nextHatY;
            wrote = true;
        }
        int n = Math.min(6, axes == null ? 0 : axes.length);
        for (int i = 0; i < n; i++) {
            boolean trigger = AXIS[i] == ABS_Z || AXIS[i] == ABS_RZ;
            int lo = trigger ? 0 : -32767;
            int hi = 32767;
            int val = Math.max(lo, Math.min(hi, axes[i]));
            if (prevAxes[i] == val) {
                continue;
            }
            emit(EV_ABS, AXIS[i], val);
            prevAxes[i] = val;
            wrote = true;
        }
        if (wrote) {
            emit(EV_SYN, 0, 0);
        }
        prevMask = mask;
    }

    private void writeSetup() {
        ByteBuffer dev = ByteBuffer.allocate(80 + 8 + 4 + ABS_CNT * 4 * 4).order(ByteOrder.nativeOrder());
        String name = index == 0 ? "Airtek Cloud Pad" : "Airtek Cloud Pad " + (index + 1);
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        dev.put(bytes, 0, Math.min(bytes.length, 79));
        dev.position(80);
        dev.putShort((short) BUS_USB);
        dev.putShort((short) 0x045E);
        dev.putShort((short) (0x028E + index));
        dev.putShort((short) 0x0110);
        dev.putInt(0);
        int base = 92;
        putAbs(dev, base, ABS_X, -32767, 32767, 128);
        putAbs(dev, base, ABS_Y, -32767, 32767, 128);
        putAbs(dev, base, ABS_RX, -32767, 32767, 128);
        putAbs(dev, base, ABS_RY, -32767, 32767, 128);
        putAbs(dev, base, ABS_Z, 0, 32767, 0);
        putAbs(dev, base, ABS_RZ, 0, 32767, 0);
        putAbs(dev, base, ABS_HAT0X, -1, 1, 0);
        putAbs(dev, base, ABS_HAT0Y, -1, 1, 0);
        byte[] raw = dev.array();
        if (C.INSTANCE.write(fd, raw, raw.length) != raw.length) {
            throw new IllegalStateException("write uinput_user_dev");
        }
    }

    private static void putAbs(ByteBuffer dev, int base, int axis, int min, int max, int flat) {
        int span = ABS_CNT * 4;
        dev.putInt(base + axis * 4, max);
        dev.putInt(base + span + axis * 4, min);
        dev.putInt(base + span * 3 + axis * 4, flat);
    }

    private void emit(int type, int code, int value) {
        ByteBuffer ev = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder());
        ev.putLong(0);
        ev.putLong(0);
        ev.putShort((short) type);
        ev.putShort((short) code);
        ev.putInt(value);
        C.INSTANCE.write(fd, ev.array(), 24);
    }

    private void ioctl(int request, int arg) {
        if (C.INSTANCE.ioctl(fd, request, arg) < 0) {
            throw new IllegalStateException("ioctl " + request);
        }
    }

    interface C extends Library {
        C INSTANCE = Native.load("c", C.class);

        int open(String path, int flags);

        int close(int fd);

        int ioctl(int fd, int request, int arg);

        int write(int fd, byte[] buf, int count);
    }
}
