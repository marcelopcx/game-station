/**
 * Adaptadores de sistema que no conocen Spring MVC.
 *
 * <p>{@link com.airtek.station.infrastructure.DatagramParser} decodifica el
 * datagrama little-endian (historial 1..4; el cliente manda 2).
 * {@link com.airtek.station.infrastructure.UinputPad} crea el pad virtual
 * Xbox 360 en {@code /dev/uinput}; en macOS {@code open} devuelve false y
 * el slot se conserva. {@link com.airtek.station.infrastructure.X11Injector}
 * inyecta con XTEST en el {@code DISPLAY} del juego, no en la pantalla del
 * host. {@link com.airtek.station.infrastructure.ProcessGroup} vacía el
 * entorno del {@code ProcessBuilder}, lo rellena y mata descendientes antes
 * que al padre. {@link com.airtek.station.infrastructure.SdlMapping} es el
 * string {@code SDL_GAMECONTROLLERCONFIG} que el juego usa para nombrar
 * ejes y botones.
 */
package com.airtek.station.infrastructure;
