package com.airtek.station.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * Acumulador de giro del mouse para {@code SDL_GetRelativeMouseState}.
 * Lo consume {@code libgameinput.so}, precargada en el juego: SDL en Linux
 * ignora el mouse inyectado por XTEST cuando usa el dispositivo crudo.
 *
 * <p>Escritura con {@link VarHandle#getAndAdd} sobre la vista int del buffer
 * (índices 1 y 2 → bytes 4 y 8); read-modify-write con {@code getInt} pierde
 * deltas frente al {@code atomic_exchange} del .so.
 */
public final class LookFeed {

    private static final Logger log = LoggerFactory.getLogger(LookFeed.class);
    private static final Path FILE = Path.of("/tmp/game-input");
    /** Vista int LE: índice 1 = byte offset 4 (dx), 2 = offset 8 (dy). */
    private static final VarHandle INT_LE =
            MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final int DX_INDEX = 1;
    private static final int DY_INDEX = 2;

    private MappedByteBuffer buffer;
    private boolean logged;

    /** Crea el archivo antes de que arranque el juego, para que la biblioteca lo encuentre. */
    public static void prepare() {
        try (RandomAccessFile file = new RandomAccessFile(FILE.toFile(), "rw")) {
            if (file.length() < 4096) {
                file.setLength(4096);
            }
            FILE.toFile().setReadable(true, false);
            FILE.toFile().setWritable(true, false);
        } catch (IOException ex) {
            log.warn("look feed prepare failed {}", ex.toString());
        }
    }

    public void open() {
        prepare();
        try (RandomAccessFile file = new RandomAccessFile(FILE.toFile(), "rw")) {
            buffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 4096);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(4, 0);
            buffer.putInt(8, 0);
            log.info("look feed=sdl path={}", FILE);
        } catch (IOException ex) {
            buffer = null;
            log.warn("look feed failed {}", ex.toString());
        }
    }

    public void add(int dx, int dy) {
        MappedByteBuffer page = buffer;
        if (page == null || (dx == 0 && dy == 0)) {
            return;
        }
        if (dx != 0) {
            INT_LE.getAndAdd(page, DX_INDEX, dx);
        }
        if (dy != 0) {
            INT_LE.getAndAdd(page, DY_INDEX, dy);
        }
        if (!logged) {
            logged = true;
            log.info("look motion dx={} dy={}", dx, dy);
        }
    }

    public void close() {
        buffer = null;
        logged = false;
    }
}
