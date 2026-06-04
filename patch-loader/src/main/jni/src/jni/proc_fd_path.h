#pragma once

#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <limits.h>
#include <string>
#include <sys/types.h>
#include <unistd.h>

namespace lspd {

    static inline bool is_proc_fd_dir_path(const char* pathname) {
        if (pathname == nullptr) {
            return false;
        }
        if (strcmp(pathname, "/proc/self/fd") == 0 || strcmp(pathname, "/proc/thread-self/fd") == 0) {
            return true;
        }

        char pid_fd_path[64];
        int len = snprintf(pid_fd_path, sizeof(pid_fd_path), "/proc/%d/fd", getpid());
        return len > 0
               && len < static_cast<int>(sizeof(pid_fd_path))
               && strcmp(pathname, pid_fd_path) == 0;
    }

    struct ProcFdReadlinkatPath {
        int dirfd;
        const char* original_path;
        std::string storage;

        const char* path() const {
            return storage.empty() ? original_path : storage.c_str();
        }
    };

    template <typename ReadlinkDirfd>
    static void prepare_proc_fd_readlinkat_path(ProcFdReadlinkatPath* out,
                                                int dirfd,
                                                const char* pathname,
                                                ReadlinkDirfd readlink_dirfd) {
        out->dirfd = dirfd;
        out->original_path = pathname;
        out->storage.clear();

        if (dirfd < 0 || pathname == nullptr || pathname[0] == '\0' || pathname[0] == '/') {
            return;
        }

        char dirfd_link[64];
        int link_len = snprintf(dirfd_link, sizeof(dirfd_link), "/proc/self/fd/%d", dirfd);
        if (link_len <= 0 || link_len >= static_cast<int>(sizeof(dirfd_link))) {
            return;
        }

        char dir_path[PATH_MAX];
        ssize_t rc = readlink_dirfd(dirfd_link, dir_path, sizeof(dir_path) - 1);
        if (rc <= 0 || rc >= static_cast<ssize_t>(sizeof(dir_path))) {
            return;
        }

        dir_path[rc] = '\0';
        if (!is_proc_fd_dir_path(dir_path)) {
            return;
        }

        while (strncmp(pathname, "./", 2) == 0) {
            pathname += 2;
        }
        if (pathname[0] == '\0') {
            return;
        }

        out->storage.assign(dir_path);
        out->storage.push_back('/');
        out->storage.append(pathname);
        out->dirfd = AT_FDCWD;
    }

}
