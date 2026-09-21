package com.airtek.station.infrastructure;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprueba que un buffer armado como el {@code encodeDatagram} del cliente
 * (magic, versión, un snapshot con tecla y pad) se lee con los ejes y el
 * bitmask esperados, y que un buffer de 8 bytes que no es un datagrama se
 * rechaza.
 */

class DatagramParserTest {

    @Test
    void readsTheSnapshotTheBrowserSends() {
        byte[] packet = encode(2, 11, 17, 1 << 0 | 1 << 9, new int[]{1000, -2000, 0, 0, 16000, 0});
        List<DatagramParser.Snapshot> snaps = DatagramParser.parse(packet);
        assertNotNull(snaps);
        DatagramParser.Snapshot latest = snaps.get(0);
        assertEquals(2, latest.seq());
        assertEquals(11, latest.frameId());
        assertTrue(latest.hasKeyboard());
        assertTrue(latest.keys().contains(17));
        assertEquals(1000, latest.pads().get(0).axes()[0]);
        assertEquals(16000, latest.pads().get(0).axes()[4]);
        assertTrue(DatagramParser.newer(2, 1));
        assertNull(DatagramParser.parse(new byte[]{1, 1, 0, 0, 0, 0, 0, 0}));
    }

    private static byte[] encode(int seq, int frame, int key, int buttons, int[] axes) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 13 + 1 + 2 + 1 + 17).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0xA7);
        buf.put((byte) 3);
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.putShort((short) seq);
        buf.putInt(frame);
        buf.put((byte) (DatagramParser.FLAG_KEYBOARD | DatagramParser.FLAG_PADS));
        buf.put((byte) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.putShort((short) key);
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.putInt(buttons);
        for (int axis : axes) {
            buf.putShort((short) axis);
        }
        return buf.array();
    }
}
