# El jar se compila dentro de la imagen para que webrtc-java traiga
# el nativo de la arquitectura del host (linux-aarch64 en el Spark).
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        ffmpeg \
        xvfb \
        x11-utils \
        mesa-utils \
        libgl1 \
        libgl1-mesa-dri \
        libglu1-mesa \
        libx11-6 \
        libxext6 \
        libxfixes3 \
        libxdamage1 \
        libxtst6 \
        libxrandr2 \
        libxi6 \
        libxxf86vm1 \
        libxcomposite1 \
        libxrender1 \
        libglib2.0-0 \
        libgbm1 \
        libdrm2 \
        libasound2 \
        libasound2-plugins \
        libopenal1 \
        libpulse0 \
        pulseaudio \
        pulseaudio-utils \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/game-station
COPY --from=build /src/target/game-station-1.0.0.jar app.jar
COPY docker/entrypoint.sh /usr/local/bin/station-entrypoint
COPY docker/seed-library.sh /usr/local/bin/station-seed-library
COPY docker/pulse/game.pa /etc/pulse/game.pa
RUN chmod +x /usr/local/bin/station-entrypoint /usr/local/bin/station-seed-library \
    && mkdir -p /tmp/pulse /tmp/pulse-run /cache /opt/game /opt/station-library \
    && chmod 777 /tmp/pulse /cache

ENV HOME=/root
ENV STATION_DISPLAY=:99
ENV STATION_SIZE=1280x720
ENV STATION_FPS=30
ENV STATION_HTTP_PORT=8090
ENV LIBRARY_ROOT=/opt/station-library
ENV CACHE_ROOT=/cache
ENV IDLE_TIMEOUT_S=300
ENV GAME_MANIFEST=/opt/game/manifest.yaml
ENV PULSE_SERVER=unix:/tmp/pulse/native
ENV PULSE_SINK=game
ENV SDL_AUDIODRIVER=pulse
ENV ALSOFT_DRIVERS=pulse
EXPOSE 8090
ENTRYPOINT ["/usr/local/bin/station-entrypoint"]
