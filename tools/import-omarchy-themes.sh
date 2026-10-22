#!/usr/bin/env bash
# Copies the colors.toml of every installed Omarchy theme into the app's
# assets (app/src/main/assets/themes/<name>.toml). Themes without a
# colors.toml are skipped. Check each third-party theme's licence before
# publishing a build that includes it.
set -euo pipefail
out="$(dirname "$0")/../app/src/main/assets/themes"
mkdir -p "$out"
for dir in /usr/share/omarchy/themes "$HOME/.config/omarchy/themes"; do
  [ -d "$dir" ] || continue
  for t in "$dir"/*/; do
    name=$(basename "$t")
    if [ -f "$t/colors.toml" ]; then
      cp "$t/colors.toml" "$out/$name.toml"
      echo "  $name"
    fi
  done
done
