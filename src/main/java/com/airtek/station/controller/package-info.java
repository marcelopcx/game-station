/**
 * API HTTP que consume {@code game-client}.
 *
 * <p>Contrato camelCase. {@code GET /health} es 200. {@code POST /prepare}
 * es 202 y no espera a que el archivo termine de copiarse.
 * {@code POST /launch} es 200 con {@code wsUrl}. {@code POST /stop} es 204
 * e idempotente. Los fallos salen como {@code {error:{code,message}}} desde
 * {@link com.airtek.station.exception.StationExceptionHandler}; este paquete
 * no arma ese JSON.
 */
package com.airtek.station.controller;
