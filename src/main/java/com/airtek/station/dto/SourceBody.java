package com.airtek.station.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Origen del artefacto en {@code POST /prepare}.
 *
 * <p>{@code type=local} copia {@code path} (o su mapeo bajo {@code LIBRARY_ROOT}
 * si el path empieza por {@code /library/}). {@code checksum} es
 * {@code sha256:<hex>} o el hex pelado. Un digest de solo ceros es placeholder:
 * el preparer exige entonces un sidecar {@code checksum} junto al payload.
 * {@code url} se acepta en el JSON y no se usa.
 */

public record SourceBody(
        String type,
        String path,
        String url,
        @NotBlank String checksum
) {
    public String typeOrLocal() {
        return type == null || type.isBlank() ? "local" : type;
    }
}
