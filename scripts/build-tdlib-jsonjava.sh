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

# Build only arm64-v8a during Android device validation. Avoid compiling four
# architectures on every candidate build. Keep upstream commit and build args pinned.
# The official Dockerfile uses all four ABIs by default; derive a reviewed local
# copy instead of modifying the checked-out official TDLib source.
DOCKERFILE="$WORK/Dockerfile.arm64"
cp "$WORK/source/example/android/Dockerfile" "$DOCKERFILE"
python3 - "$DOCKERFILE" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
old = 'td/example/android/build-tdlib.sh SDK "$ANDROID_NDK_VERSION" openssl "$ANDROID_STL" "$TDLIB_INTERFACE" &&'
new = 'td/example/android/build-tdlib.sh SDK "$ANDROID_NDK_VERSION" openssl "$ANDROID_STL" "$TDLIB_INTERFACE" 26 "arm64-v8a" &&'
if s.count(old) != 1:
    raise SystemExit("Official TDLib Dockerfile layout changed; manual review needed")
p.write_text(s.replace(old, new))
PY
echo "Reviewed arm64-v8a build recipe:"
sha256sum "$DOCKERFILE"
mkdir -p "$WORK/output"
docker build \
  --file "$DOCKERFILE" \
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
