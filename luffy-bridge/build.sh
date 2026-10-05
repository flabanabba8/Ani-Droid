#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
source_dir="$root_dir/.reference/luffy"
revision=0e0abc6bb1c10a02c2d0db2711a04ca9bb836deb
if [[ ! -d "$source_dir/.git" ]]; then
    git clone https://github.com/DemonKingSwarn/luffy.git "$source_dir"
    git -C "$source_dir" checkout "$revision"
fi
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$revision" ]] || { echo 'Luffy source revision differs from the pinned revision.' >&2; exit 1; }
mkdir -p "$source_dir/cmd/anidroid-bridge" "$root_dir/luffy-bridge/build"
if git -C "$source_dir" apply --reverse --check "$root_dir/luffy-bridge/overlay/fallback.patch" 2>/dev/null; then
    : # The exact overlay is already applied.
else
    git -C "$source_dir" apply --check "$root_dir/luffy-bridge/overlay/fallback.patch"
    git -C "$source_dir" apply "$root_dir/luffy-bridge/overlay/fallback.patch"
fi
cp "$root_dir/luffy-bridge/overlay/"*.go "$source_dir/core/providers/"
cp "$root_dir/luffy-bridge/"main*.go "$source_dir/cmd/anidroid-bridge/"
cd "$source_dir"
gofmt -w cmd/anidroid-bridge/main.go core/providers/vixsrc*.go core/providers/lookmovie.go
go test ./core/... ./cmd/... > "$root_dir/luffy-bridge/build/upstream-tests.log" 2>&1
CGO_ENABLED=0 go build -trimpath -ldflags='-s -w -X github.com/demonkingswarn/luffy/core.Version=v1.2.1+0e0abc6-fallbacks' -o "$root_dir/luffy-bridge/build/luffy-linux-amd64" .
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags='-s -w -X github.com/demonkingswarn/luffy/core.Version=v1.2.1+0e0abc6-fallbacks' -o "$root_dir/luffy-bridge/build/luffy-windows-amd64.exe" .
CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o "$root_dir/luffy-bridge/build/ani-luffy-bridge" ./cmd/anidroid-bridge

CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o "$root_dir/luffy-bridge/build/ani-luffy-bridge.exe" ./cmd/anidroid-bridge
