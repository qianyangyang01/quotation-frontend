#!/bin/sh
set -eu
# Append immutable build assets. Never replace bytes at a URL an open page uses.
source_dir="${1:-/opt/quotation/assets}"
target_dir="${2:-/usr/share/nginx/html/assets}"
test -d "$source_dir" || { echo "Missing frontend assets: $source_dir" >&2; exit 1; }
mkdir -p "$target_dir"
if [ -n "$(find "$source_dir" -type l -print -quit)" ]; then
  echo "Refusing symlinks in frontend assets" >&2; exit 1
fi
find "$source_dir" -type f | while IFS= read -r source_file; do
  relative="${source_file#"$source_dir"/}"
  target_file="$target_dir/$relative"
  mkdir -p "$(dirname "$target_file")"
  if [ -e "$target_file" ]; then
    cmp -s "$source_file" "$target_file" || {
      echo "Immutable asset collision: $relative" >&2; exit 1
    }
  else
    temporary="$(mktemp "$(dirname "$target_file")/.asset-pending.XXXXXX")"
    trap 'rm -f "$temporary"' EXIT HUP INT TERM
    cp "$source_file" "$temporary"
    chmod 0644 "$temporary"
    mv -n "$temporary" "$target_file"
    cmp -s "$source_file" "$target_file" || {
      echo "Concurrent asset collision: $relative" >&2; exit 1
    }
    rm -f "$temporary"
    trap - EXIT HUP INT TERM
  fi
done
