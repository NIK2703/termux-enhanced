#pragma once
#include <stdbool.h>
#include "terminal.h"
static inline bool urls_mode_is_active(const struct terminal *term) { return tll_length(term->url.list) > 0; }
void urls_reset(struct terminal *term);
