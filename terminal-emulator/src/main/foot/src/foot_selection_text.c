/*
 * Upstream selection text extraction.
 *
 * selection.c cannot be linked as a whole because it owns Wayland clipboard
 * and scroll-timer state. The continuous text-only block is extracted verbatim
 * into foot-selection-text.inc; extract.c is compiled unmodified from the
 * pinned foot source. The Android adapter only supplies absolute ring
 * coordinates.
 */

#include "debug.h"
#include "extract.h"
#include "grid.h"
#include "macros.h"
#include "terminal.h"
#include "util.h"

#include "foot-selection-text.inc"
