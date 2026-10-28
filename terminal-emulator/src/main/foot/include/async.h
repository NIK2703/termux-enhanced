#pragma once
#include <stddef.h>
enum async_write_status { ASYNC_WRITE_DONE, ASYNC_WRITE_REMAIN, ASYNC_WRITE_ERR };
enum async_write_status async_write(int fd, const void *data, size_t len, size_t *idx);
