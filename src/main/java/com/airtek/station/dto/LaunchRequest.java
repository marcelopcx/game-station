package com.airtek.station.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body de {@code POST /launch}. {@code gameId} tiene que ser el {@code id} del
 * manifiesto bakeado en esta imagen. {@code sessionId} debe ser el de la
 * preparación que dejó la estación en {@code READY}.
 */

public record LaunchRequest(
        @NotBlank String sessionId,
        @NotBlank String gameId
) {
}
