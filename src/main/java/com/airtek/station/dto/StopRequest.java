package com.airtek.station.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body de {@code POST /stop}. El {@code sessionId} se valida (no vacío) y no
 * se compara con la sesión activa: stop es idempotente y siempre vuelve a
 * {@code IDLE}. No borra {@code /cache} ni termina la JVM.
 */

public record StopRequest(@NotBlank String sessionId) {
}
