#!/bin/sh
# Extract the upstream foot cell renderer used by the Android Canvas bridge.
#
# The pinned render.c block is copied verbatim; only the three-line provenance
# header is added. --check is run by CMake on every build, so editing the .inc
# by hand (or updating third_party/foot without re-pinning) fails the build.
#
# Usage:
#   check-render-cell.sh --check   verify the generated file
#   check-render-cell.sh --write   regenerate it (after a deliberate re-pin)

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../../../../.." && pwd)

source_file="$repo_root/third_party/foot/render.c"
output_file="$repo_root/terminal-emulator/src/main/foot/src/foot-render-cell.inc"

# Both hashes are part of the pin. Update them together when third_party/foot
# moves to a new upstream commit, then run this script with --write.
source_sha256=97280b358e859d2c4f40f7ddf0b55311d7d66f9a5d9fe6e2c221611c6e0187cb
output_sha256=dcf1ba3b1562d2703e05e153998a4fbc07ad4073504dc324666656a0ace9efe5

# attrs_to_font() through the closing brace of render_row(). The source hash
# above makes this range exact: any upstream line shift changes the hash and
# aborts before extraction.
start_line=226
end_line=1213

usage()
{
    echo "usage: $0 [--check|--write]" >&2
    exit 2
}

if command -v sha256sum >/dev/null 2>&1; then
    sha256() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
    sha256() { shasum -a 256 "$1" | awk '{print $1}'; }
else
    echo "error: need sha256sum or shasum to verify the pinned renderer" >&2
    exit 1
fi

if [ "$#" -gt 1 ]; then
    usage
fi
mode=${1:---check}
case "$mode" in
    --check|--write) ;;
    *) usage ;;
esac

if [ ! -f "$source_file" ]; then
    echo "error: missing pinned foot source: $source_file" >&2
    exit 1
fi

actual_source_sha256=$(sha256 "$source_file")
if [ "$actual_source_sha256" != "$source_sha256" ]; then
    echo "error: third_party/foot/render.c does not match the pinned hash" >&2
    echo "  expected: $source_sha256" >&2
    echo "  actual:   $actual_source_sha256" >&2
    echo "Re-pin the submodule, update source_sha256/output_sha256, then run --write." >&2
    exit 1
fi

tmp="${TMPDIR:-/tmp}/foot-render-cell.$$"
trap 'rm -f "$tmp"' EXIT HUP INT TERM

{
    printf '%s\n' \
        "/* Generated from third_party/foot/render.c (sha256 $source_sha256)." \
        " * Source lines: attrs_to_font() through render_row(). Do not edit; see" \
        " * docs/foot-renderer-migration.md and the extractor command there. */"
    sed -n "${start_line},${end_line}p" "$source_file"
} > "$tmp"

if [ "$mode" = "--write" ]; then
    cp "$tmp" "$output_file"
    actual_output_sha256=$(sha256 "$output_file")
    echo "wrote $output_file"
    echo "  sha256: $actual_output_sha256"
    if [ "$actual_output_sha256" != "$output_sha256" ]; then
        echo "note: update output_sha256 in $0 to $actual_output_sha256" >&2
    fi
    exit 0
fi

if [ ! -f "$output_file" ]; then
    echo "error: missing generated file: $output_file" >&2
    echo "Run $0 --write" >&2
    exit 1
fi

actual_output_sha256=$(sha256 "$output_file")
if [ "$actual_output_sha256" != "$output_sha256" ]; then
    echo "error: foot-render-cell.inc is out of sync with the pinned render.c" >&2
    echo "  expected: $output_sha256" >&2
    echo "  actual:   $actual_output_sha256" >&2
    diff -u "$output_file" "$tmp" | head -80 >&2 || true
    echo "Run $0 --write" >&2
    exit 1
fi

echo "foot-render-cell.inc: ok ($output_sha256)"
