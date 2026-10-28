#pragma once
#include <stdint.h>
#include <stdbool.h>
typedef uint32_t xkb_keysym_t;
typedef uint32_t xkb_mod_mask_t;
typedef uint32_t xkb_layout_index_t;
struct xkb_context;
struct xkb_keymap;
struct xkb_state;
struct xkb_compose_table;
struct xkb_compose_state;
struct xkb_rule_names;
enum xkb_compose_status { XKB_COMPOSE_NOTHING, XKB_COMPOSE_COMPOSING, XKB_COMPOSE_COMPOSED, XKB_COMPOSE_CANCELLED };
