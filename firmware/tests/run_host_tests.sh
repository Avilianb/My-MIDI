#!/bin/sh
set -eu
cd "$(dirname "$0")"
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
${CC:-cc} -std=c11 -g -fsanitize=address,undefined -I stubs test_bridge.c -o "$out/test_bridge"
"$out/test_bridge"
