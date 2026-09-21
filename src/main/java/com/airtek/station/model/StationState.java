package com.airtek.station.model;

/**
 * Estados de la máquina. El {@link #name()} es el valor del wire
 * ({@code IDLE}, {@code PREPARING}, {@code READY}, {@code PLAYING}, {@code FAILED})
 * tanto en {@code GET /health} como en {@code { type: STATE, state }}.
 *
 * <pre>
 * IDLE --prepare--&gt; PREPARING --copia--&gt; READY --launch--&gt; PLAYING
 *   ^                   |                                    |
 *   |                   +-- FAILED --(2s)-- stop ------------+
 *   +------------------------ stop --------------------------+
 * </pre>
 */

public enum StationState {
    IDLE,
    PREPARING,
    READY,
    PLAYING,
    FAILED
}
