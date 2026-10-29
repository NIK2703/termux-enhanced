#!/bin/sh
# Extract foot's upstream selection geometry and damage policy.
#
# The pinned render.c/selection.c block is copied verbatim; only the
# provenance header is added. --check is run by CMake on every build, so
# editing the .inc by hand (or updating third_party/foot without re-pinning)
# fails the build.
#
# Usage:
#   check-selection-region.sh --check   verify the generated file
#   check-selection-region.sh --write   regenerate it (after a deliberate re-pin)

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../../../../.." && pwd)

source_file="$repo_root/third_party/foot/selection.c"
output_file="$repo_root/terminal-emulator/src/main/foot/src/foot-selection.inc"

# Both hashes are part of the pin. Update them together when third_party/foot
# moves to a new upstream commit, then run this script with --write.
source_sha256=af300c0315bcd32bf19602f0b22147ddb2b52410fe04189df0b9ebb0199fd2a5
output_sha256=4bd93815e1bd226030acedeaa8783dca407ea4c28d2208a614b254d75cc9121f

# pixman_region_for_coords_normal() through the closing brace of
# mark_selected_region(). The source hash above makes this range exact: any
# upstream line shift changes the hash and aborts before extraction.
start_line=783
end_line=1002

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
    echo "error: need sha256sum or shasum to verify the pinned selection code" >&2
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
    echo "error: third_party/foot/selection.c does not match the pinned hash" >&2
    echo "  expected: $source_sha256" >&2
    echo "  actual:   $actual_source_sha256" >&2
    echo "Re-pin the submodule, update source_sha256/output_sha256, then run --write." >&2
    exit 1
fi

tmp="${TMPDIR:-/tmp}/foot-selection.$$"
trap 'rm -f "$tmp"' EXIT HUP INT TERM

{
    printf '%s\n' \
        "/* Generated from third_party/foot/selection.c (sha256 $source_sha256)." \
        " * Source lines: pixman_region_for_coords_normal() through" \
        " * mark_selected_region(). Do not edit; see" \
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
    echo "error: foot-selection.inc is out of sync with the pinned selection.c" >&2
    echo "  expected: $output_sha256" >&2
    echo "  actual:   $actual_output_sha256" >&2
    diff -u "$output_file" "$tmp" | head -80 >&2 || true
    echo "Run $0 --write" >&2
    exit 1
fi

echo "foot-selection.inc: ok ($output_sha256)"
