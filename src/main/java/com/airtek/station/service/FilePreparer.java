package com.airtek.station.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import com.airtek.station.exception.PrepareException;

/**
 * Copia {@code source.path} desde la library al cache y verifica SHA-256.
 *
 * <p>El payload es el propio path si es archivo, o {@code payload.bin} si es
 * directorio. Un cache hit (el {@code payload.bin} destino ya tiene el digest
 * esperado) no reescribe y reporta progreso 100. Si el digest no cierra, se
 * borra el destino y se lanza {@link com.airtek.station.exception.PrepareException}
 * {@code CHECKSUM_MISMATCH}.
 *
 * <p>{@code source.type=http} y {@code azure-sas} se rechazan con
 * {@code UNSUPPORTED_SOURCE}: este corte solo copia local. El progreso se
 * publica con el callback en el hilo que llama a {@link #run}; el servicio
 * lo reemite por el hub.
 */

@Component
public class FilePreparer {

    private static final Logger log = LoggerFactory.getLogger(FilePreparer.class);
    private static final int CHUNK = 1024 * 1024;

    private final Path libraryRoot;
    private final Path cacheRoot;

    public FilePreparer(com.airtek.station.config.StationProperties properties) {
        this.libraryRoot = Path.of(properties.getLibraryRoot());
        this.cacheRoot = Path.of(properties.getCacheRoot());
        try {
            Files.createDirectories(cacheRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("no se pudo crear " + cacheRoot, ex);
        }
    }

    public Path cacheRoot() {
        return cacheRoot;
    }

    public List<Map<String, String>> listCache() {
        List<Map<String, String>> entries = new ArrayList<>();
        if (!Files.isDirectory(cacheRoot)) {
            return entries;
        }
        try (var games = Files.list(cacheRoot)) {
            games.filter(Files::isDirectory).sorted().forEach(game -> {
                try (var versions = Files.list(game)) {
                    versions.filter(Files::isDirectory).sorted().forEach(ver -> {
                        if (Files.isRegularFile(ver.resolve("payload.bin"))) {
                            entries.add(Map.of("gameId", game.getFileName().toString(), "version", ver.getFileName().toString()));
                        }
                    });
                } catch (IOException ignored) {
                    /* un directorio ilegible no entra al health */
                }
            });
        } catch (IOException ignored) {
            return entries;
        }
        return entries;
    }

    /**
     * Copia el payload al cache {@code gameId/version/payload.bin} y compara
     * SHA-256. Un checksum placeholder (ceros, incluido {@code sha256:00})
     * exige un sidecar en la library; si no está, {@code CHECKSUM_MISMATCH}.
     *
     * @param gameId   segmento bajo {@code cache-root}
     * @param version  segmento bajo {@code gameId}
     * @param type     {@code local}; {@code http} y {@code azure-sas} lanzan {@code UNSUPPORTED_SOURCE}
     * @param path     ruta bajo {@code library-root}, o absoluta dentro de esa raíz
     * @param checksum hex o {@code sha256:} + hex
     * @param progress recibe porcentaje y fase en el hilo llamador
     * @return {@code true} si el {@code payload.bin} ya tenía el digest (cache hit)
     */
    public boolean run(String gameId, String version, String type, String path, String checksum, BiConsumer<Integer, String> progress) {
        if ("azure-sas".equals(type) || "http".equals(type)) {
            throw new PrepareException("UNSUPPORTED_SOURCE", "source.type=" + type + " no está en este corte; usá local");
        }
        if (path == null || path.isBlank()) {
            throw new PrepareException("SOURCE_NOT_FOUND", "source.path vacío");
        }
        Path srcRoot = resolve(path);
        if (!Files.exists(srcRoot)) {
            throw new PrepareException("SOURCE_NOT_FOUND", "no existe " + path);
        }
        Path srcPayload = payload(srcRoot);
        String expected = parseSha(checksum);
        if (isPlaceholder(expected)) {
            String sidecar = readSidecar(srcRoot);
            if (sidecar == null) {
                throw new PrepareException("CHECKSUM_MISMATCH", "checksum placeholder y no hay sidecar en library");
            }
            expected = sidecar;
        }
        Path destRoot = cacheRoot.resolve(gameId).resolve(version);
        Path destPayload = destRoot.resolve("payload.bin");
        try {
            if (Files.isRegularFile(destPayload) && digest(destPayload).equals(expected)) {
                progress.accept(100, "Cache hit");
                log.info("prepare cacheHit=true game={} version={}", gameId, version);
                return true;
            }
            progress.accept(0, "Copiando assets");
            Files.createDirectories(destRoot);
            copy(srcPayload, destPayload, progress);
            Path sidecar = srcRoot.resolve("checksum");
            if (Files.isRegularFile(sidecar)) {
                Files.copy(sidecar, destRoot.resolve("checksum"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String got = digest(destPayload);
            if (!got.equals(expected)) {
                Files.deleteIfExists(destPayload);
                throw new PrepareException("CHECKSUM_MISMATCH", "checksum sha256:" + got + " != sha256:" + expected);
            }
        } catch (PrepareException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new PrepareException("PREPARE_FAILED", ex.getMessage());
        }
        progress.accept(100, "Verificado");
        log.info("prepare cacheHit=false game={} version={}", gameId, version);
        return false;
    }

    private Path resolve(String path) {
        Path given = Path.of(path);
        if (Files.exists(given)) {
            return given;
        }
        String prefix = "/library/";
        if (path.startsWith(prefix)) {
            Path mapped = libraryRoot.resolve(path.substring(prefix.length()));
            if (Files.exists(mapped)) {
                return mapped;
            }
        }
        Path named = libraryRoot.resolve(given.getFileName() == null ? path : given.getFileName().toString());
        return Files.exists(named) ? named : given;
    }

    private static Path payload(Path root) {
        if (Files.isRegularFile(root)) {
            return root;
        }
        Path candidate = root.resolve("payload.bin");
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        throw new PrepareException("SOURCE_NOT_FOUND", "no hay payload.bin en " + root);
    }

    private static String readSidecar(Path root) {
        for (String name : List.of("checksum", "payload.bin.sha256")) {
            Path side = Files.isDirectory(root) ? root.resolve(name) : root.resolveSibling(name);
            if (Files.isRegularFile(side)) {
                try {
                    String line = Files.readString(side).trim().split("\\s+")[0];
                    return parseSha(line);
                } catch (IOException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    static String parseSha(String value) {
        String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (raw.startsWith("sha256:")) {
            raw = raw.substring(7);
        }
        return raw;
    }

    private static boolean isPlaceholder(String digest) {
        return digest.isEmpty() || digest.chars().allMatch(ch -> ch == '0');
    }

    private static String digest(Path path) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception ex) {
            throw new IOException(ex);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = in.read(buf)) > 0) {
                sha.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void copy(Path src, Path dest, BiConsumer<Integer, String> progress) throws IOException {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        long total = Math.max(Files.size(src), 1);
        long copied = 0;
        try (InputStream in = Files.newInputStream(src); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                copied += n;
                progress.accept((int) Math.min(99, copied * 100 / total), "Copiando assets");
            }
        }
        Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
