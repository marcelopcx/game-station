package com.airtek.station.infrastructure;

/**
 * Líneas {@code SDL_GAMECONTROLLERCONFIG} del pad virtual.
 *
 * <p>El GUID es el de un USB Xbox 360 ({@code 045e:028e}, versión {@code 0x0110})
 * con el product id {@code 0x028E + slot}. El mapping
 * ({@code a:b0 … rightstick:b10}, triggers en {@code a2}/{@code a5},
 * d-pad en el hat 0) coincide con el orden joydev que arma
 * {@link UinputPad}: los códigos evdev se enumeran por valor numérico, y
 * {@code BTN_TL2}/{@code BTN_TR2} no se registran para que Select/Start no
 * se corran. Se exporta una línea por slot, separadas por newline, que el
 * runtime mete en el entorno del juego.
 */

public final class SdlMapping {

    private static final String BINDINGS =
            "a:b0,b:b1,x:b2,y:b3,back:b6,start:b7,guide:b8,"
                    + "leftshoulder:b4,rightshoulder:b5,leftstick:b9,rightstick:b10,"
                    + "leftx:a0,lefty:a1,rightx:a3,righty:a4,lefttrigger:a2,righttrigger:a5,"
                    + "dpup:h0.1,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,platform:Linux,";

    private SdlMapping() {
    }

    public static String config(int pads) {
        int n = Math.max(0, Math.min(4, pads));
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < n; index++) {
            int product = 0x028E + index;
            String name = index == 0 ? "Airtek Cloud Pad" : "Airtek Cloud Pad " + (index + 1);
            out.append(guid(product)).append(',').append(name).append(',').append(BINDINGS).append('\n');
        }
        return out.toString();
    }

    private static String guid(int product) {
        int vendor = 0x045E;
        int version = 0x0110;
        byte[] raw = new byte[]{
                0x03, 0x00, 0x00, 0x00,
                (byte) (vendor & 0xFF), (byte) ((vendor >> 8) & 0xFF), 0x00, 0x00,
                (byte) (product & 0xFF), (byte) ((product >> 8) & 0xFF), 0x00, 0x00,
                (byte) (version & 0xFF), (byte) ((version >> 8) & 0xFF), 0x00, 0x00
        };
        StringBuilder hex = new StringBuilder(32);
        for (byte b : raw) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
