#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p tools/samples
curl -fL --retry 3 https://www.gutenberg.org/ebooks/1342.epub3.images -o tools/samples/pride-and-prejudice.epub
curl -fL --retry 3 https://www.gutenberg.org/ebooks/11.epub3.images -o tools/samples/alice.epub
curl -fL --retry 3 https://www.gutenberg.org/ebooks/11.txt.utf-8 -o tools/samples/alice.txt
