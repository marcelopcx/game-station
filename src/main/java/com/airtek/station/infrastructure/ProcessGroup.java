package com.airtek.station.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lista de procesos de una partida (Xvfb y el binario del juego).
 *
 * <p>{@link #spawn} hereda un entorno explícito: limpia el map de
 * {@code ProcessBuilder} y copia el que le pasan, porque el default de Java
 * ya trae el entorno del JVM y mezclarlo duplicaría {@code DISPLAY}. stdout
 * y stderr van a {@code DISCARD}; el fallo de arranque se ve como
 * {@code IOException} con el nombre del binario.
 *
 * <p>{@link #stop} recorre la lista al revés y destruye cada árbol
 * ({@link Process#descendants()} y luego el padre), con 3 s de gracia antes
 * de {@code destroyForcibly}. No usa {@code setsid}: en Java 21 el árbol de
 * {@code ProcessHandle} alcanza para Xvfb y el juego que cuelga de él.
 */

public class ProcessGroup {

    private static final Logger log = LoggerFactory.getLogger(ProcessGroup.class);

    private final List<Process> processes = new ArrayList<>();

    public Process spawn(List<String> argv, Map<String, String> env, String name, String cwd) throws IOException {
        log.info("exec {} cmd={} cwd={}", name, argv, cwd == null ? "." : cwd);
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.environment().clear();
        builder.environment().putAll(env);
        if (cwd != null && !cwd.isBlank()) {
            builder.directory(new java.io.File(cwd));
        }
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = builder.start();
            processes.add(process);
            return process;
        } catch (IOException ex) {
            throw new IOException(argv.get(0) + " no está en PATH (proc=" + name + ")", ex);
        }
    }

    public void stop() {
        List<Process> copy = new ArrayList<>(processes);
        processes.clear();
        for (int i = copy.size() - 1; i >= 0; i--) {
            destroyTree(copy.get(i));
        }
        log.info("process group stopped");
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
