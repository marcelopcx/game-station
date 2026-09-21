package com.airtek.station;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada. Un proceso, un manifiesto, un juego.
 * El escaneo de componentes arranca en {@code com.airtek.station}.
 */
@SpringBootApplication
public class StationApplication {

    public static void main(String[] args) {
        SpringApplication.run(StationApplication.class, args);
    }
}
