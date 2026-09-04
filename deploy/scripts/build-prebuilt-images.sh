#!/usr/bin/env bash
set -Eeuo pipefail
if [[ $# -lt 1 || $# -gt 2 || ! "$1" =~ ^quotation-[0-9]{4}\.[0-9]{2}\.[0-9]{2}-[0-9]{2}$ ]]; then
  echo "Usage: build-prebuilt-images.sh quotation-YYYY.MM.DD-NN [output-directory]" >&2; exit 2
fi
release="$1"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_dir="$(cd "$script_dir/../.." && pwd)"
output_dir="${2:-$repository_dir/.codex-artifacts/prebuilt/$release}"
[[ -z "$(git -C "$repository_dir" status --porcelain)" ]] || { echo "ERROR: prebuilt images require a clean quotation commit" >&2; exit 1; }
git_sha="$(git -C "$repository_dir" rev-parse HEAD)"
mkdir -p "$output_dir"
archive="$output_dir/quotation-images-$release.tar.gz"
test ! -e "$archive" || { echo "ERROR: archive already exists: $archive" >&2; exit 1; }

docker build --pull --label "com.milano.quotation.release=$release" --label "com.milano.quotation.git-sha=$git_sha" -t "quotation-backend:$release" "$repository_dir/backend"
docker build --pull --label "com.milano.quotation.release=$release" --label "com.milano.quotation.git-sha=$git_sha" -t "quotation-frontend:$release" "$repository_dir"
for image in "quotation-backend:$release" "quotation-frontend:$release"; do
  test "$(docker image inspect --format '{{ index .Config.Labels "com.milano.quotation.release" }}' "$image")" = "$release" || { echo "ERROR: built image release label mismatch: $image" >&2; exit 1; }
  test "$(docker image inspect --format '{{ index .Config.Labels "com.milano.quotation.git-sha" }}' "$image")" = "$git_sha" || { echo "ERROR: built image git SHA label mismatch: $image" >&2; exit 1; }
done
docker save "quotation-backend:$release" "quotation-frontend:$release" | gzip -1 > "$archive"
(cd "$output_dir" && sha256sum "$(basename "$archive")" > SHA256SUMS)
printf '%s\n' "release=$release" "git_sha=$git_sha" "archive=$archive" > "$output_dir/manifest.txt"
echo "$archive"
