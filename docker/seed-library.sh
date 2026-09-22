#!/bin/sh
# Deja un payload.bin y su checksum bajo LIBRARY_ROOT/<gameId>/<version>.
# El cliente manda checksum sha256:00 y la estación lee este sidecar.
set -eu
GAME_ID="${1:?gameId}"
VERSION="${2:-1.0.0}"
ROOT="${LIBRARY_ROOT:-/opt/station-library}"
DEST="$ROOT/$GAME_ID/$VERSION"
mkdir -p "$DEST"
dd if=/dev/zero of="$DEST/payload.bin" bs=1048576 count=32 status=none
DIGEST=$(sha256sum "$DEST/payload.bin" | awk '{print $1}')
printf 'sha256:%s\n' "$DIGEST" > "$DEST/checksum"
echo "library $GAME_ID/$VERSION sha256:$DIGEST"
