package com.airtek.station.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Body de {@code POST /prepare}. Los nombres son el wire camelCase, no el
 * estilo Java {@code session_id}. {@code sessionId} lo genera el browser y la
 * estación lo repite en el 202 y en los eventos {@code STATE}.
 */

public record PrepareRequest(
        @NotBlank String sessionId,
        @NotBlank String gameId,
        @NotBlank String version,
        @Valid SourceBody source
) {
}
