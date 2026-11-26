#!/usr/bin/env bash
set -euo pipefail

VULKAN_VERSION=1.4.363.0
VULKAN_SHA256=197962f5cbf80baf2775a03336a01cee7c8745686c65aaa70d3f751ade4d7e43

archive="vulkansdk-linux-x86_64-$VULKAN_VERSION.tar.xz"
curl --fail --silent --show-error --location --retry 3 \
  "https://sdk.lunarg.com/sdk/download/$VULKAN_VERSION/linux/$archive" \
  --output "$RUNNER_TEMP/$archive"
echo "$VULKAN_SHA256  $RUNNER_TEMP/$archive" | sha256sum --check
tar -xJf "$RUNNER_TEMP/$archive" -C "$RUNNER_TEMP"
sdk="$RUNNER_TEMP/$VULKAN_VERSION/x86_64"
test -f "$sdk/include/vulkan/vulkan.hpp"
"$sdk/bin/glslc" --version
echo "VULKAN_SDK=$sdk" >> "$GITHUB_ENV"
