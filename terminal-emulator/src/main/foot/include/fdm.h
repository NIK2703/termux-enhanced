#pragma once
#include <stdbool.h>
struct fdm;
typedef bool (*fdm_fd_handler_t)(struct fdm *fdm, int fd, int events, void *data);
typedef bool (*fdm_signal_handler_t)(struct fdm *fdm, int signo, void *data);
typedef void (*fdm_hook_t)(struct fdm *fdm, void *data);
enum fdm_hook_priority { FDM_HOOK_PRIORITY_LOW, FDM_HOOK_PRIORITY_NORMAL, FDM_HOOK_PRIORITY_HIGH };
#include <sys/epoll.h>
struct fdm *fdm_init(void);
void fdm_destroy(struct fdm *fdm);
bool fdm_add(struct fdm *fdm, int fd, int events, fdm_fd_handler_t handler, void *data);
bool fdm_del(struct fdm *fdm, int fd);
bool fdm_del_no_close(struct fdm *fdm, int fd);

bool fdm_event_add(struct fdm *fdm, int fd, int events);
bool fdm_event_del(struct fdm *fdm, int fd, int events);
