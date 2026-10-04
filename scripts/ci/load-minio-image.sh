#!/usr/bin/env bash
set -Eeuo pipefail
# The upstream registry no longer serves this pinned release anonymously.
# This cache contains only the unmodified public image, never a data volume.
# Matching upstream source and license are published beside the image asset.
readonly image='quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z'
readonly archive='minio-2025.07.23-linux-amd64.tar.gz'
readonly archive_sha='e9cb26d0dfc356cc467c8588465bd271f02b5613e61f03142343269693614b92'
cache_dir="$(mktemp -d)"
trap 'rm -rf -- "$cache_dir"' EXIT
gh release download ci-minio-2025.07.23 --repo qianyangyang01/quotation-frontend --pattern "$archive" --dir "$cache_dir"
(cd "$cache_dir" && printf '%s  %s\n' "$archive_sha" "$archive" | sha256sum --check --strict)
gzip -dc "$cache_dir/$archive" | docker load
# The containerd store reports the OCI index digest; the classic Docker store
# reports the config digest from this archive's manifest.json instead.
actual_id="$(docker image inspect "$image" --format '{{.Id}}')"
case "$actual_id" in
  sha256:d249d1fb6966de4d8ad26c04754b545205ff15a62e4fd19ebd0f26fa5baacbc0|sha256:a98a9d647e700e45c1d3d2e44709f23952a39c199731d84e623eb558fd5501f4) ;;
  *) echo "Unexpected MinIO image identity: $actual_id" >&2; exit 1 ;;
esac
docker run --rm --entrypoint minio "$image" --version
