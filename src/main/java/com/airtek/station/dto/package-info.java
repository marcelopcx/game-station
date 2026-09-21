/**
 * Cuerpos de las peticiones REST.
 *
 * <p>Records validados con Bean Validation. {@code sessionId} y {@code gameId}
 * son obligatorios en prepare, launch y stop. {@code source} solo viaja en
 * prepare: {@code type} ({@code local} en este corte), {@code path} y
 * {@code checksum}. Jackson usa los nombres camelCase del record; no hay
 * {@code @JsonProperty} porque el cliente ya manda ese casing.
 */
package com.airtek.station.dto;
