package com.airtek.station.infrastructure;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Parser del datagrama de input (SCTP unreliable, mismo 5-tuple ICE que el video).
 *
 * <p>Layout little-endian:
 * <pre>
 * 0  uint8   magic 0xA7
 * 1  uint8   version = 3
 * 2  uint8   count 1..4, el snapshot más viejo primero
 * 3  uint8   reservado
 * luego, por snapshot (13 bytes de cabecera):
 *    uint16 seq | uint32 frameId | uint8 flags | uint8 mouseButtons
 *    int16  x   | int16  y       | int8  wheel
 *    uint8 nkeys + uint16 KEY_* × nkeys   (máx. 24)
 *    uint8 npads + (uint8 slot, uint32 buttons, int16[6] axes) × npads
 * </pre>
 * Flags: bit0 mouse, bit1 absoluto, bit2 teclado, bit3 pads. Un paquete que
 * no cierra (count fuera de rango, nkeys/npads que no entran) devuelve
 * {@code null}; el sink lo loguea y no mueve el reloj de idle.
 *
 * <p>{@link #newer(int, int)} trata {@code seq} como uint16 circular: un
 * delta en {@code (0, 32768)} es más nuevo. {@code prev < 0} acepta el
 * primero.
 */

public final class DatagramParser {

    public static final int MAGIC = 0xA7;
    public static final int VERSION = 3;
    public static final int HISTORY = 4;
    public static final int FLAG_MOUSE = 1;
    public static final int FLAG_MOUSE_ABS = 1 << 1;
    public static final int FLAG_KEYBOARD = 1 << 2;
    public static final int FLAG_PADS = 1 << 3;
    public static final int MAX_KEYS = 24;
    public static final int MAX_PADS = 4;

    private DatagramParser() {
    }

    /**
     * Un frame de pad ya decodificado. {@code buttons} es máscara de bits
     * del standard mapping (bit 0 = A … bit 16 = Guide). {@code axes} tiene
     * seis enteros: LX, LY, RX, RY, LT, RT, en el rango del cliente
     * (−32767..32767; gatillos 0..32767).
     *
     * @param slot    0..3
     * @param buttons máscara
     * @param axes    seis muestras; el sink no las copia
     */
    public record PadSnapshot(int slot, int buttons, int[] axes) {
    }

    /**
     * Fotografía de controles. {@code seq} es uint16. {@code frameId} es el
     * contador de frames que el cliente creía estar viendo (no lo usa el sink
     * para inyectar; queda para correlacionar lag).
     */
    public record Snapshot(
            int seq,
            long frameId,
            boolean hasMouse,
            boolean mouseAbs,
            int mouseX,
            int mouseY,
            int mouseButtons,
            int wheel,
            boolean hasKeyboard,
            Set<Integer> keys,
            boolean hasPads,
            Map<Integer, PadSnapshot> pads
    ) {
    }

    /**
     * Decodifica un datagrama completo.
     *
     * @param data bytes del DataChannel; {@code null} o magic distinto de {@code 0xA7} → {@code null}
     * @return snapshots del más viejo al más nuevo, o {@code null} si el buffer no cierra
     */
    public static java.util.List<Snapshot> parse(byte[] data) {
        if (data == null || data.length < 4 || (data[0] & 0xFF) != MAGIC) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.get() & 0xFF;
        int ver = buf.get() & 0xFF;
        int count = buf.get() & 0xFF;
        buf.get();
        if (magic != MAGIC || ver != VERSION || count < 1 || count > HISTORY) {
            return null;
        }
        java.util.ArrayList<Snapshot> snaps = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Snapshot snap = readSnapshot(buf);
            if (snap == null) {
                return null;
            }
            snaps.add(snap);
        }
        return snaps;
    }

    /**
     * Compara dos {@code seq} uint16 con aritmética circular. La ventana
     * válida es la mitad del espacio (32767). Un salto mayor se trata como
     * reordenamiento o replay y el sink lo descarta.
     *
     * @param seq  secuencia del snapshot candidato
     * @param prev última secuencia aplicada; negativo si todavía no hubo ninguna
     * @return {@code true} si {@code seq} avanza respecto de {@code prev}
     */
    public static boolean newer(int seq, int prev) {
        if (prev < 0) {
            return true;
        }
        int delta = (seq - prev) & 0xFFFF;
        return delta > 0 && delta < 0x8000;
    }

    private static Snapshot readSnapshot(ByteBuffer buf) {
        if (buf.remaining() < 15) {
            return null;
        }
        int seq = buf.getShort() & 0xFFFF;
        long frameId = buf.getInt() & 0xFFFFFFFFL;
        int flags = buf.get() & 0xFF;
        int buttons = buf.get() & 0x1F;
        int mx = buf.getShort();
        int my = buf.getShort();
        int wheel = buf.get();
        if (buf.remaining() < 1) {
            return null;
        }
        int nkeys = buf.get() & 0xFF;
        if (nkeys > MAX_KEYS || buf.remaining() < nkeys * 2 + 1) {
            return null;
        }
        Set<Integer> keys = new LinkedHashSet<>();
        for (int i = 0; i < nkeys; i++) {
            int code = buf.getShort() & 0xFFFF;
            if (code > 0) {
                keys.add(code);
            }
        }
        int npads = buf.get() & 0xFF;
        if (npads > MAX_PADS || buf.remaining() < npads * 17) {
            return null;
        }
        Map<Integer, PadSnapshot> pads = new LinkedHashMap<>();
        for (int i = 0; i < npads; i++) {
            int slot = buf.get() & 0xFF;
            int mask = buf.getInt();
            int[] axes = new int[6];
            for (int a = 0; a < 6; a++) {
                axes[a] = buf.getShort();
            }
            if (slot <= 3) {
                pads.put(slot, new PadSnapshot(slot, mask, axes));
            }
        }
        return new Snapshot(
                seq,
                frameId,
                (flags & FLAG_MOUSE) != 0,
                (flags & FLAG_MOUSE_ABS) != 0,
                mx,
                my,
                buttons,
                wheel,
                (flags & FLAG_KEYBOARD) != 0,
                keys,
                (flags & FLAG_PADS) != 0,
                pads
        );
    }
}
