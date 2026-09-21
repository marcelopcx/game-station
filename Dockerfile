FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
        libx11-6 libxext6 libxfixes3 libxdamage1 libxtst6 libxrandr2 \
        libxcomposite1 libglib2.0-0 libgbm1 libdrm2 ffmpeg xvfb \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/game-station
COPY target/game-station-1.0.0.jar app.jar

ENV STATION_HTTP_PORT=8090
EXPOSE 8090

ENTRYPOINT ["java", \
  "--enable-native-access=ALL-UNNAMED", \
  "--add-opens", "webrtc.java/dev.onvoid.webrtc=ALL-UNNAMED", \
  "--add-opens", "webrtc.java/dev.onvoid.webrtc.logging=ALL-UNNAMED", \
  "--add-opens", "webrtc.java/dev.onvoid.webrtc.media=ALL-UNNAMED", \
  "--add-opens", "webrtc.java/dev.onvoid.webrtc.media.audio=ALL-UNNAMED", \
  "--add-opens", "webrtc.java/dev.onvoid.webrtc.media.video=ALL-UNNAMED", \
  "-jar", "app.jar"]
