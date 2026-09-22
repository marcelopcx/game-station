#!/bin/sh
set -eu
mkdir -p /tmp/pulse /tmp/pulse-run /cache /opt/station-library /opt/game
chmod 777 /tmp/pulse /cache 2>/dev/null || true
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/pulse-run}"
export PULSE_RUNTIME_PATH="${PULSE_RUNTIME_PATH:-/tmp/pulse}"
export PULSE_STATE_PATH="${PULSE_STATE_PATH:-/tmp/pulse}"
export PULSE_SERVER="${PULSE_SERVER:-unix:/tmp/pulse/native}"
export PULSE_SINK="${PULSE_SINK:-game}"
if [ -x /usr/bin/pulseaudio ] && [ -f /etc/pulse/game.pa ]; then
  pulseaudio -n \
    --file=/etc/pulse/game.pa \
    --exit-idle-time=-1 \
    --daemonize=yes \
    --use-pid-file=false \
    --disallow-exit \
    || echo "pulse no arrancó; el juego puede usar el fallback de audio"
fi
exec java \
  --enable-native-access=ALL-UNNAMED \
  --add-opens webrtc.java/dev.onvoid.webrtc=ALL-UNNAMED \
  --add-opens webrtc.java/dev.onvoid.webrtc.logging=ALL-UNNAMED \
  --add-opens webrtc.java/dev.onvoid.webrtc.media=ALL-UNNAMED \
  --add-opens webrtc.java/dev.onvoid.webrtc.media.audio=ALL-UNNAMED \
  --add-opens webrtc.java/dev.onvoid.webrtc.media.video=ALL-UNNAMED \
  -jar /opt/game-station/app.jar
