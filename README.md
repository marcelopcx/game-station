# game-station

Estación de juego de Airtek. Un proceso Spring Boot prepara un título, lo ejecuta en un display virtual y entrega el video al browser por WebRTC. El input del navegador vuelve por un DataChannel y se aplica a un pad virtual y al teclado y ratón del display.

Un proceso. Un juego por manifiesto. `POST /stop` cierra la partida y deja la JVM en pie.

```
Browser
  REST   /health  /prepare  /launch  /stop     :8090
  WS     /ws/control                            estados de la partida
  WS     /ws/webrtc                             SDP e ICE
  RTP                                           sale de libwebrtc, no de este proceso
DataChannel input → datagrama de snapshots → pad uinput + teclado y ratón XTEST
```

Sin `GAME_MANIFEST` carga el manifiesto embebido `test-pattern`: barras de color por WebRTC, para probar el cliente sin Xvfb ni un binario.

## Requisitos

- Java 21
- Maven 3.9 o superior
- Para un juego con display: Linux con Xvfb, ffmpeg y `/dev/uinput`

## Ejecutar en local

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn spring-boot:run
```

En macOS no existe `/dev/uinput`. El pad se acepta y queda registrado en el log; el patrón de barras sí se entrega.

El cliente apunta a esta estación:

```bash
VITE_STATION_URL=http://127.0.0.1:8090 VITE_GAME_ID=test-pattern npm run dev
```

Abrí `http://localhost:5173`, prepará y jugá. Tiene que verse el patrón de barras.

## API

Contrato camelCase. Los errores salen como `{ "error": { "code", "message" } }`.

| Método | Ruta | Respuesta |
| --- | --- | --- |
| `GET` | `/health` | 200. Estado, juego soportado, `needs` y encoder |
| `POST` | `/prepare` | 202. La copia sigue en segundo plano |
| `POST` | `/launch` | 200. `{ state, wsUrl: "/ws/webrtc", needs }` |
| `POST` | `/stop` | 204. Idempotente |

`/ws/control` emite `STATE` y `ERROR`. `/ws/webrtc` negocia con el browser como offerer. Un segundo socket recibe `PEER_BUSY`.

La máquina de estados es `IDLE → PREPARING → READY → PLAYING`. `FAILED` vuelve a `IDLE`.

Códigos: `STATION_BUSY` y `PREPARE_IN_PROGRESS` (409), `GAME_NOT_READY` (424), `UNKNOWN_GAME` y `BAD_REQUEST` (400), `LAUNCH_FAILED` (500). La copia puede fallar con `CHECKSUM_MISMATCH`, `SOURCE_NOT_FOUND`, `UNSUPPORTED_SOURCE` o `PREPARE_FAILED`; eso llega por el socket de control, no como HTTP.

## Configuración

| Variable | Default | Uso |
| --- | --- | --- |
| `STATION_HTTP_PORT` | `8090` | Puerto HTTP |
| `STATION_ID` | `spark-1` | Identificador que publica `/health` |
| `STATION_DISPLAY` | `:99` | Display de Xvfb |
| `STATION_SIZE` | `1280x720` | Tamaño del framebuffer |
| `STATION_FPS` | `30` | Cuadros de la captura |
| `LIBRARY_ROOT` | `/opt/station-library` | Origen local de los assets |
| `CACHE_ROOT` | `/tmp/airtek-cache` | Copia verificada por SHA-256 |
| `IDLE_TIMEOUT_S` | `300` | Cierra la partida sin input |
| `GAME_MANIFEST` | vacío | YAML del juego. Vacío usa el embebido |
| `ICE_SERVERS` | STUN de Google | Servidores ICE, separados por coma |
| `ICE_HOST_IPS` | vacío | IPs que el browser puede alcanzar |
| `ICE_HOST_POLICY` | `all` | `public` descarta loopback, link-local y site-local |

## Juego con display

El manifiesto declara `command`, `args`, `env` y `needs`. Con `needs.display` la estación levanta Xvfb y captura ese display con ffmpeg (`x11grab`). libwebrtc codifica el video. `needs.gamepad` entre 0 y 4 crea pads Xbox 360 virtuales y publica `SDL_GAMECONTROLLERCONFIG`.

```bash
export GAME_MANIFEST=/opt/game/manifest.yaml
export LIBRARY_ROOT=/opt/station-library
export CACHE_ROOT=/cache
export ICE_HOST_IPS=<ip-que-ve-el-browser>
export ICE_HOST_POLICY=public
java --enable-native-access=ALL-UNNAMED \
  --add-opens webrtc.java/dev.onvoid.webrtc=ALL-UNNAMED \
  --add-opens webrtc.java/dev.onvoid.webrtc.logging=ALL-UNNAMED \
  --add-opens webrtc.java/dev.onvoid.webrtc.media=ALL-UNNAMED \
  --add-opens webrtc.java/dev.onvoid.webrtc.media.audio=ALL-UNNAMED \
  --add-opens webrtc.java/dev.onvoid.webrtc.media.video=ALL-UNNAMED \
  -jar target/game-station-1.0.0.jar
```

Imagen:

```bash
mvn -DskipTests package
docker build -t airtek/game-station:1.0.0 .
```

## Input

El DataChannel `input` lleva un datagrama little-endian: magic `0xA7`, versión 3 y de 1 a 4 snapshots. Cada snapshot trae `seq`, `frameId`, ratón, teclas `KEY_*` y hasta cuatro pads (máscara de botones y seis ejes). La estación aplica el snapshot más nuevo. Los pads salen por `/dev/uinput` y el teclado y el ratón por XTEST en el display del juego.

## Estructura

```
com.airtek.station
├── StationApplication
├── config          propiedades y registro de los WebSocket
├── controller      /health, /prepare, /launch, /stop
├── dto             cuerpos de las peticiones
├── exception       códigos del contrato
├── model           estados y manifiesto
├── service         partida, catálogo, cache, runtime, input y peer
├── websocket       /ws/control y /ws/webrtc
└── infrastructure  datagrama, uinput, XTEST, SDL y procesos
```

`src/main/resources/application.yml` trae los defaults. `manifest.yaml` es el juego embebido.

## Tests

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn test
```

Cubren el parser del datagrama y el recorrido HTTP: health en `IDLE`, prepare hasta `READY`, launch con `/ws/webrtc` y stop de vuelta a `IDLE`.
