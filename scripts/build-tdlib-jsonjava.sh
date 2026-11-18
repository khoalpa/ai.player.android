#!/usr/bin/env bash
# Build official TDLib JSONJava for Android from reviewed immutable upstream commit.
# Run with Docker in Linux/WSL2: bash scripts/build-tdlib-jsonjava.sh
set -euo pipefail
TDLIB_COMMIT="03220f6b3043c6fd7785fbf0901d63f19ad19974"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/tdlib-official"
mkdir -p "$WORK"
command -v docker >/dev/null || { echo "Docker is required" >&2; exit 2; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 2; }

if [[ ! -d "$WORK/source/.git" ]]; then
  git clone https://github.com/tdlib/td.git "$WORK/source"
fi
git -C "$WORK/source" fetch origin "$TDLIB_COMMIT"
git -C "$WORK/source" checkout --detach "$TDLIB_COMMIT"
test "$(git -C "$WORK/source" rev-parse HEAD)" = "$TDLIB_COMMIT"

mkdir -p "$WORK/output"
docker build \
  --build-arg "COMMIT_HASH=$TDLIB_COMMIT" \
  --build-arg "TDLIB_INTERFACE=JSONJava" \
  --output "type=local,dest=$WORK/output" \
  "$WORK/source/example/android"

test -s "$WORK/output/tdlib.zip"
mkdir -p "$WORK/unpacked"
unzip -oq "$WORK/output/tdlib.zip" -d "$WORK/unpacked"
cmp "$WORK/unpacked/tdlib/java/org/drinkless/tdlib/JsonClient.java" \
    "$ROOT/app/src/main/java/org/drinkless/tdlib/JsonClient.java" || {
      echo "The Java wrapper differs from the generated native library" >&2
      exit 3
    }

found=0
for file in "$WORK"/unpacked/tdlib/libs/*/libtdjsonjava.so; do
  test -f "$file" || continue
  found=1
  abi="$(basename "$(dirname "$file")")"
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  cp "$file" "$ROOT/app/src/main/jniLibs/$abi/libtdjsonjava.so"
  sha256sum "$ROOT/app/src/main/jniLibs/$abi/libtdjsonjava.so"
done
test "$found" -eq 1 || { echo "No JSONJava native libraries" >&2; exit 4; }
echo "Pinned TDLib SHA: $TDLIB_COMMIT"
echo "Review generated native binaries and licenses before committing to PR #5."
