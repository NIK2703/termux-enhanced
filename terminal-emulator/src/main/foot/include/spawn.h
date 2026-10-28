#pragma once
#include <stdbool.h>
#include <unistd.h>
#include "config.h"
#include "reaper.h"
pid_t spawn(struct reaper *reaper, const char *cwd, char *const argv[], int stdin_fd, int stdout_fd, int stderr_fd, reaper_cb cb, void *cb_data, const char *xdg_activation_token);
bool spawn_expand_template(const struct config_spawn_template *template, size_t key_count, const char *key_names[], const char *key_values[], size_t *argc, char ***argv);
