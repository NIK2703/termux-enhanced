#define _GNU_SOURCE

#include <alloca.h>
#include <dirent.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>

#define MAX_RULES 8
#define PATH_BUF PATH_MAX

struct remap_rule {
    char from[PATH_BUF];
    char to[PATH_BUF];
    size_t from_len;
};

static struct remap_rule g_rules[MAX_RULES];
static int g_nrules = 0;
static int g_enabled = 0;

static pthread_once_t g_once = PTHREAD_ONCE_INIT;

static char g_old_files[PATH_BUF];
static char g_new_files[PATH_BUF];
static char g_old_data[PATH_BUF];
static char g_new_data[PATH_BUF];
static char g_loader[PATH_BUF];
static char g_libpath[PATH_BUF];

static int has_prefix_boundary(const char *s, const char *prefix, size_t prefix_len) {
    if (strncmp(s, prefix, prefix_len) != 0)
        return 0;
    char c = s[prefix_len];
    return c == '\0' || c == '/';
}

static void add_rule(const char *from, const char *to) {
    if (!from || !to || !*from || !*to || g_nrules >= MAX_RULES)
        return;
    snprintf(g_rules[g_nrules].from, sizeof(g_rules[g_nrules].from), "%s", from);
    snprintf(g_rules[g_nrules].to, sizeof(g_rules[g_nrules].to), "%s", to);
    g_rules[g_nrules].from_len = strlen(from);
    g_nrules++;
}

static void strip_files_suffix(const char *files_dir, char *out, size_t out_len) {
    size_t len = strlen(files_dir);
    if (len > 6 && strcmp(files_dir + len - 6, "/files") == 0)
        len -= 6;
    if (len >= out_len) len = out_len - 1;
    memcpy(out, files_dir, len);
    out[len] = '\0';
}

static void sort_rules_by_from_length_desc(void) {
    for (int i = 0; i < g_nrules; i++) {
        for (int j = i + 1; j < g_nrules; j++) {
            if (g_rules[j].from_len > g_rules[i].from_len) {
                struct remap_rule tmp = g_rules[i];
                g_rules[i] = g_rules[j];
                g_rules[j] = tmp;
            }
        }
    }
}

static void init_rules(void) {
    const char *old_files = getenv("TERMUX_REMAP_OLD_FILES_DIR");
    const char *new_files = getenv("TERMUX_REMAP_NEW_FILES_DIR");

    if (!old_files || !new_files || !*old_files || !*new_files) {
        g_enabled = 0;
        return;
    }

    snprintf(g_old_files, sizeof(g_old_files), "%s", old_files);
    snprintf(g_new_files, sizeof(g_new_files), "%s", new_files);

    strip_files_suffix(g_old_files, g_old_data, sizeof(g_old_data));
    strip_files_suffix(g_new_files, g_new_data, sizeof(g_new_data));

    const char *libpath = getenv("TERMUX_REMAP_LIBPATH");
    if (libpath && *libpath)
        snprintf(g_libpath, sizeof(g_libpath), "%s", libpath);
    else
        snprintf(g_libpath, sizeof(g_libpath), "%s/usr/lib", g_new_files);

    const char *loader = getenv("TERMUX_REMAP_LOADER");
    if (loader && *loader)
        snprintf(g_loader, sizeof(g_loader), "%s", loader);
    else
        snprintf(g_loader, sizeof(g_loader), "%s/usr/lib/ld-linux-aarch64.so.1", g_new_files);

    add_rule(g_old_files, g_new_files);
    add_rule(g_old_data, g_new_data);

    if (strncmp(g_old_data, "/data/data/", 11) == 0) {
        const char *pkg = g_old_data + 11;
        char user_files[PATH_BUF], user_data[PATH_BUF];
        snprintf(user_files, sizeof(user_files), "/data/user/0/%s/files", pkg);
        snprintf(user_data, sizeof(user_data), "/data/user/0/%s", pkg);
        add_rule(user_files, g_new_files);
        add_rule(user_data, g_new_data);
    }

    sort_rules_by_from_length_desc();

    // Scope check: only enable for processes running from Termux paths
    char exe[PATH_BUF];
    ssize_t n = syscall(__NR_readlinkat, AT_FDCWD, "/proc/self/exe", exe, sizeof(exe) - 1);
    if (n > 0) {
        exe[n] = '\0';
        if (has_prefix_boundary(exe, g_new_files, strlen(g_new_files)) ||
            has_prefix_boundary(exe, g_old_files, strlen(g_old_files)) ||
            has_prefix_boundary(exe, g_new_data, strlen(g_new_data)) ||
            has_prefix_boundary(exe, g_old_data, strlen(g_old_data))) {
            g_enabled = 1;
        }
    } else {
        g_enabled = 1;
    }
}

static const char *remap_path(const char *path, char *buf, size_t buf_len) {
    if (!path || path[0] != '/')
        return path;

    pthread_once(&g_once, init_rules);

    if (!g_enabled)
        return path;

    for (int i = 0; i < g_nrules; i++) {
        const struct remap_rule *r = &g_rules[i];
        if (has_prefix_boundary(path, r->from, r->from_len)) {
            snprintf(buf, buf_len, "%s%s", r->to, path + r->from_len);
            return buf;
        }
    }

    return path;
}

// ── Raw syscall helpers (no recursion into hooks) ──

static int raw_exists(const char *path) {
    return syscall(__NR_faccessat, AT_FDCWD, path, F_OK, 0) == 0;
}

static int raw_open_readonly(const char *path) {
    return (int)syscall(__NR_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
}

static const char *remap_at_cwd(int dirfd, const char *pathname, char *buf, size_t buf_len) {
    if (dirfd == AT_FDCWD)
        return remap_path(pathname, buf, buf_len);
    return pathname;
}

static int count_args(char *const argv[]) {
    int argc = 0;
    while (argv && argv[argc])
        argc++;
    return argc;
}

static void fill_wrapped_argv(char **nargv, char *const argv[], int argc, const char *p) {
    int i = 0;

    nargv[i++] = (char *)g_loader;
    nargv[i++] = (char *)"--library-path";
    nargv[i++] = (char *)g_libpath;

    const char *preserve_argv0 = getenv("TERMUX_REMAP_PRESERVE_ARGV0");
    if (preserve_argv0 && preserve_argv0[0] == '1' && argc > 0) {
        nargv[i++] = (char *)"--argv0";
        nargv[i++] = argv[0];
    }

    nargv[i++] = (char *)p;
    for (int j = 1; j < argc; j++)
        nargv[i++] = argv[j];
    nargv[i] = NULL;
}

// ── ELF inspection ──

static int elf_needs_loader_wrap(const char *path) {
    if (!g_enabled)
        return 0;

    // Only wrap binaries under the runtime Termux files dir
    if (!has_prefix_boundary(path, g_new_files, strlen(g_new_files)))
        return 0;

    // Do not wrap the loader itself
    if (strcmp(path, g_loader) == 0)
        return 0;

    if (!raw_exists(g_loader))
        return 0;

    int fd = raw_open_readonly(path);
    if (fd < 0)
        return 0;

    Elf64_Ehdr eh;
    ssize_t r = read(fd, &eh, sizeof(eh));
    if (r != (ssize_t)sizeof(eh)) {
        close(fd);
        return 0;
    }

    if (memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0) {
        close(fd);
        return 0;
    }

    if (eh.e_ident[EI_CLASS] != ELFCLASS64) {
        close(fd);
        return 0;
    }

    if (eh.e_type != ET_EXEC && eh.e_type != ET_DYN) {
        close(fd);
        return 0;
    }

    int needs_wrap = 0;
    for (int i = 0; i < eh.e_phnum; i++) {
        Elf64_Phdr ph;
        off_t off = (off_t)(eh.e_phoff + (uint64_t)i * (uint64_t)eh.e_phentsize);
        ssize_t pr = pread(fd, &ph, sizeof(ph), off);
        if (pr != (ssize_t)sizeof(ph))
            break;
        if (ph.p_type == PT_INTERP) {
            char interp[PATH_BUF];
            size_t n = ph.p_filesz;
            if (n >= sizeof(interp))
                n = sizeof(interp) - 1;
            ssize_t ir = pread(fd, interp, n, (off_t)ph.p_offset);
            if (ir == (ssize_t)n) {
                interp[n] = '\0';
                if (interp[0] == '/' && !raw_exists(interp))
                    needs_wrap = 1;
            }
            break;
        }
    }

    close(fd);
    return needs_wrap;
}

// ── Hooked libc functions ──

typedef int (*openat_fn)(int, const char *, int, ...);
typedef int (*open_fn)(const char *, int, ...);
typedef FILE *(*fopen_fn)(const char *, const char *);
typedef FILE *(*fopen64_fn)(const char *, const char *);
typedef int (*access_fn)(const char *, int);
typedef int (*faccessat_fn)(int, const char *, int, int);
typedef int (*stat_fn)(const char *, struct stat *);
typedef int (*lstat_fn)(const char *, struct stat *);
typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*execveat_fn)(int, const char *, char *const[], char *const[], int);
typedef int (*fstatat_fn)(int, const char *, struct stat *, int);
typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
typedef ssize_t (*readlinkat_fn)(int, const char *, char *, size_t);
typedef int (*statx_fn)(int, const char *, int, unsigned int, struct statx *);
typedef DIR *(*opendir_fn)(const char *);
typedef char *(*realpath_fn)(const char *, char *);

int openat(int dirfd, const char *pathname, int flags, ...) {
    static openat_fn real = NULL;
    if (!real) real = (openat_fn)dlsym(RTLD_NEXT, "openat");

    char buf[PATH_BUF];
    const char *p = remap_at_cwd(dirfd, pathname, buf, sizeof(buf));

    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }

    return real(dirfd, p, flags, mode);
}

int openat64(int dirfd, const char *pathname, int flags, ...) {
    static openat_fn real = NULL;
    if (!real) real = (openat_fn)dlsym(RTLD_NEXT, "openat64");

    char buf[PATH_BUF];
    const char *p = remap_at_cwd(dirfd, pathname, buf, sizeof(buf));

    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }

    return real(dirfd, p, flags, mode);
}

int open(const char *pathname, int flags, ...) {
    static open_fn real = NULL;
    if (!real) real = (open_fn)dlsym(RTLD_NEXT, "open");

    char buf[PATH_BUF];
    const char *p = remap_path(pathname, buf, sizeof(buf));

    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }

    return real(p, flags, mode);
}

FILE *fopen(const char *pathname, const char *mode) {
    static fopen_fn real = NULL;
    if (!real) real = (fopen_fn)dlsym(RTLD_NEXT, "fopen");

    char buf[PATH_BUF];
    const char *p = remap_path(pathname, buf, sizeof(buf));
    return real(p, mode);
}

int access(const char *pathname, int mode) {
    static access_fn real = NULL;
    if (!real) real = (access_fn)dlsym(RTLD_NEXT, "access");

    char buf[PATH_BUF];
    const char *p = remap_path(pathname, buf, sizeof(buf));
    return real(p, mode);
}

int stat(const char *pathname, struct stat *statbuf) {
    static stat_fn real = NULL;
    if (!real) real = (stat_fn)dlsym(RTLD_NEXT, "stat");

    char buf[PATH_BUF];
    const char *p = remap_path(pathname, buf, sizeof(buf));
    return real(p, statbuf);
}

int lstat(const char *pathname, struct stat *statbuf) {
    static lstat_fn real = NULL;
    if (!real) real = (lstat_fn)dlsym(RTLD_NEXT, "lstat");

    char buf[PATH_BUF];
    const char *p = remap_path(pathname, buf, sizeof(buf));
    return real(p, statbuf);
}

int fstatat(int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    static fstatat_fn real = NULL;
    if (!real) real = (fstatat_fn)dlsym(RTLD_NEXT, "fstatat");

    char buf[PATH_BUF];
    const char *p = remap_at_cwd(dirfd, pathname, buf, sizeof(buf));

    return real(dirfd, p, statbuf, flags);
}

DIR *opendir(const char *name) {
    static opendir_fn real = NULL;
    if (!real) real = (opendir_fn)dlsym(RTLD_NEXT, "opendir");

    char buf[PATH_BUF];
    const char *p = remap_path(name, buf, sizeof(buf));
    return real(p);
}

ssize_t readlink(const char *pathname, char *buf, size_t bufsiz) {
    static readlink_fn real = NULL;
    if (!real) real = (readlink_fn)dlsym(RTLD_NEXT, "readlink");

    char rbuf[PATH_BUF];
    const char *p = remap_path(pathname, rbuf, sizeof(rbuf));
    return real(p, buf, bufsiz);
}

ssize_t readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    static readlinkat_fn real = NULL;
    if (!real) real = (readlinkat_fn)dlsym(RTLD_NEXT, "readlinkat");

    char rbuf[PATH_BUF];
    const char *p = remap_at_cwd(dirfd, pathname, rbuf, sizeof(rbuf));

    return real(dirfd, p, buf, bufsiz);
}

int execve(const char *pathname, char *const argv[], char *const envp[]) {
    static execve_fn real = NULL;
    if (!real) real = (execve_fn)dlsym(RTLD_NEXT, "execve");

    pthread_once(&g_once, init_rules);

    char buf[PATH_BUF];
    const char *p = remap_path(pathname, buf, sizeof(buf));

    if (elf_needs_loader_wrap(p)) {
        int argc = count_args(argv);
        char **nargv = alloca(sizeof(char *) * (argc + 8));
        fill_wrapped_argv(nargv, argv, argc, p);
        return real(g_loader, nargv, envp);
    }

    return real(p, argv, envp);
}

int execveat(int dirfd, const char *pathname,
             char *const argv[], char *const envp[], int flags) {
    static execveat_fn real = NULL;
    if (!real) real = (execveat_fn)dlsym(RTLD_NEXT, "execveat");

    pthread_once(&g_once, init_rules);

    char buf[PATH_BUF];
    const char *p = remap_at_cwd(dirfd, pathname, buf, sizeof(buf));

    if (elf_needs_loader_wrap(p)) {
        int argc = count_args(argv);
        char **nargv = alloca(sizeof(char *) * (argc + 8));
        fill_wrapped_argv(nargv, argv, argc, p);
        return real(AT_FDCWD, g_loader, nargv, envp, flags & ~AT_EMPTY_PATH);
    }

    return real(dirfd, p, argv, envp, flags);
}

int statx(int dirfd, const char *pathname, int flags,
          unsigned int mask, struct statx *statxbuf) {
    static statx_fn real = NULL;
    if (!real) real = (statx_fn)dlsym(RTLD_NEXT, "statx");

    char buf[PATH_BUF];
    const char *p = remap_at_cwd(dirfd, pathname, buf, sizeof(buf));

    return real(dirfd, p, flags, mask, statxbuf);
}

// ── Additional hardening hooks ──

int open64(const char *pathname, int flags, ...) {
    static open_fn real = NULL;
    if (!real) real = (open_fn)dlsym(RTLD_NEXT, "open64");

    char buf[PATH_BUF];
    const char *p = remap_path(pathname, buf, sizeof(buf));

    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }

    return real(p, flags, mode);
}

FILE *fopen64(const char *pathname, const char *mode) {
    static fopen64_fn real = NULL;
    if (!real) real = (fopen64_fn)dlsym(RTLD_NEXT, "fopen64");

    char buf[PATH_BUF];
    const char *p = remap_path(pathname, buf, sizeof(buf));
    return real(p, mode);
}

int faccessat(int dirfd, const char *pathname, int mode, int flags) {
    static faccessat_fn real = NULL;
    if (!real) real = (faccessat_fn)dlsym(RTLD_NEXT, "faccessat");

    char buf[PATH_BUF];
    const char *p = remap_at_cwd(dirfd, pathname, buf, sizeof(buf));

    return real(dirfd, p, mode, flags);
}

char *realpath(const char *pathname, char *resolved) {
    static realpath_fn real = NULL;
    if (!real) real = (realpath_fn)dlsym(RTLD_NEXT, "realpath");

    char buf[PATH_BUF];
    const char *p = remap_path(pathname, buf, sizeof(buf));
    return real(p, resolved);
}

// ── glibc internal aliases ──

int __open_2(const char *pathname, int flags) {
    static open_fn real = NULL;
    if (!real) real = (open_fn)dlsym(RTLD_NEXT, "__open_2");

    char buf[PATH_BUF];
    const char *p = remap_path(pathname, buf, sizeof(buf));
    return real(p, flags);
}

int __openat_2(int dirfd, const char *pathname, int flags) {
    static openat_fn real = NULL;
    if (!real) real = (openat_fn)dlsym(RTLD_NEXT, "__openat_2");

    char buf[PATH_BUF];
    const char *p = remap_at_cwd(dirfd, pathname, buf, sizeof(buf));

    return real(dirfd, p, flags);
}

int __xstat(int ver, const char *pathname, struct stat *statbuf) {
    static stat_fn real = NULL;
    if (!real) real = (stat_fn)dlsym(RTLD_NEXT, "__xstat");

    char buf[PATH_BUF];
    const char *p = remap_path(pathname, buf, sizeof(buf));
    return real(p, statbuf);
}

int __lxstat(int ver, const char *pathname, struct stat *statbuf) {
    static lstat_fn real = NULL;
    if (!real) real = (lstat_fn)dlsym(RTLD_NEXT, "__lxstat");

    char buf[PATH_BUF];
    const char *p = remap_path(pathname, buf, sizeof(buf));
    return real(p, statbuf);
}

int __fxstatat(int ver, int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    static fstatat_fn real = NULL;
    if (!real) real = (fstatat_fn)dlsym(RTLD_NEXT, "__fxstatat");

    char buf[PATH_BUF];
    const char *p = pathname;

    if (dirfd == AT_FDCWD)
        p = remap_path(pathname, buf, sizeof(buf));

    return real(dirfd, p, statbuf, flags);
}
