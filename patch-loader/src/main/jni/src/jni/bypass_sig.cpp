//
// Created by VIP on 2021/4/25.
// Modified  by HSSkyBoy on 2025/12/15
//

#include "bypass_sig.h"

#include "native_util.h"
#include "core/native_api.h"
#include "common/logging.h"
#include "core/context.h"
#include "patch_loader.h"
#include "proc_fd_path.h"
#include "utils/hook_helper.hpp"
#include "utils/jni_helper.hpp"
#include <dlfcn.h>
#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <link.h>
#include <linux/memfd.h>
#include <linux/stat.h>
#include <limits.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/vfs.h>
#include <unistd.h>
#include <cstdarg>
#include <string>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

using lsplant::operator""_sym;

namespace lspd {

    using OpenAtFn = int(*)(int, const char*, int, ...);
    using OpenFn = int(*)(const char*, int, ...);
    using Open2Fn = int(*)(const char*, int);
    using AccessFn = int(*)(const char*, int);
    using ReadlinkFn = ssize_t(*)(const char*, char*, size_t);
    using ReadlinkAtFn = ssize_t(*)(int, const char*, char*, size_t);
    using RealpathFn = char*(*)(const char*, char*);
    using StatFn = int(*)(const char*, struct stat*);
    using Stat64Fn = int(*)(const char*, struct stat64*);
    using StatFsFn = int(*)(const char*, struct statfs*);
    using StatxFn = int(*)(int, const char*, int, unsigned int, struct statx*);
    using CloseFn = int(*)(int);
    using FopenFn = FILE*(*)(const char*, const char*);
    using ReadFn = ssize_t(*)(int, void*, size_t);
    using Pread64Fn = ssize_t(*)(int, void*, size_t, off64_t);
    using LseekFn = off_t(*)(int, off_t, int);
    using FstatFn = int(*)(int, struct stat*);
    using Fstat64Fn = int(*)(int, struct stat64*);
    using MmapFn = void*(*)(void*, size_t, int, int, int, off_t);
    using DlIteratePhdrFn = int(*)(int (*)(struct dl_phdr_info*, size_t, void*), void*);

    static std::string targetApkPath;
    static std::string redirectApkPath;
    static std::string currentPackageName;
    static std::vector<std::string> moduleNativeLibraryRoots;
    static void *openat_target = nullptr;
    static void *openat64_target = nullptr;
    static void *open_target = nullptr;
    static void *open64_target = nullptr;
    static void *__open_2_target = nullptr;
    static void *access_target = nullptr;
    static void *readlink_target = nullptr;
    static void *readlinkat_target = nullptr;
    static void *realpath_target = nullptr;
    static void *stat_target = nullptr;
    static void *lstat_target = nullptr;
    static void *stat64_target = nullptr;
    static void *lstat64_target = nullptr;
    static void *statfs_target = nullptr;
    static void *statx_target = nullptr;
    static void *close_target = nullptr;
    static void *fopen_target = nullptr;
    static void *read_target = nullptr;
    static void *pread64_target = nullptr;
    static void *lseek_target = nullptr;
    static void *fstat_target = nullptr;
    static void *fstat64_target = nullptr;
    static void *mmap_target = nullptr;
    static void *dl_iterate_phdr_target = nullptr;
    static OpenAtFn openat_backup = nullptr;
    static OpenAtFn openat64_backup = nullptr;
    static OpenFn open_backup = nullptr;
    static OpenFn open64_backup = nullptr;
    static Open2Fn __open_2_backup = nullptr;
    static AccessFn access_backup = nullptr;
    static ReadlinkFn readlink_backup = nullptr;
    static ReadlinkAtFn readlinkat_backup = nullptr;
    static RealpathFn realpath_backup = nullptr;
    static StatFn stat_backup = nullptr;
    static StatFn lstat_backup = nullptr;
    static Stat64Fn stat64_backup = nullptr;
    static Stat64Fn lstat64_backup = nullptr;
    static StatFsFn statfs_backup = nullptr;
    static StatxFn statx_backup = nullptr;
    static CloseFn close_backup = nullptr;
    static FopenFn fopen_backup = nullptr;
    static DlIteratePhdrFn dl_iterate_phdr_backup = nullptr;
    static bool openat_hook_installed = false;
    static bool openat64_hook_installed = false;
    static bool open_hook_installed = false;
    static bool open64_hook_installed = false;
    static bool __open_2_hook_installed = false;
    static bool access_hook_installed = false;
    static bool readlink_hook_installed = false;
    static bool readlinkat_hook_installed = false;
    static bool realpath_hook_installed = false;
    static bool stat_hook_installed = false;
    static bool lstat_hook_installed = false;
    static bool stat64_hook_installed = false;
    static bool lstat64_hook_installed = false;
    static bool statfs_hook_installed = false;
    static bool statx_hook_installed = false;
    static bool close_hook_installed = false;
    static bool fopen_hook_installed = false;
    static bool dl_iterate_phdr_hook_installed = false;
    static bool minimal_file_hook_mode = false;
    static bool g_lib_hide_enabled = false;
    static std::mutex g_path_mutex;
    static thread_local bool g_openat_reentry = false;
    static thread_local bool g_fopen_reentry = false;
    static thread_local std::string g_redirect_buffer;

    struct LibSnapshot {
        const char* soname;
        char path[PATH_MAX];
        char visible_path[PATH_MAX];
        int fd = -1;
    };

    struct MapEntry {
        uintptr_t start = 0;
        uintptr_t end = 0;
        unsigned long offset = 0;
        unsigned long long inode = 0;
        char perms[5] = {0};
        char dev[32] = {0};
        char path[PATH_MAX] = {0};
    };

    static LibSnapshot g_lib_snapshots[] = {
            {"libart.so", "", ""},
            {"libbinder.so", "", ""},
            {"libselinux.so", "", ""},
            {"libnpatch.so", "", ""},
            {"libandroid_runtime.so", "", ""},
            {"libc.so", "", ""},
    };

    static const char* const kSensitiveWords[] = {
            "frida",
            "rwxp",
            "zygisk",
            "lsposed",
            "lspd",
            "edxposed",
            "xposed",
            "riru",
            "npatch",
            "vector",
            "/data/local/tmp",
            "/data/adb/",
            nullptr,
    };

    static void ensure_lib_snapshots();

    static bool needs_mode(int flags) {
        if ((flags & O_CREAT) != 0) {
            return true;
        }
#ifdef O_TMPFILE
        if ((flags & O_TMPFILE) == O_TMPFILE) {
            return true;
        }
#endif
        return false;
    }

    static void copy_path(char* dest, const char* src) {
        if (dest == nullptr) {
            return;
        }
        if (src == nullptr) {
            dest[0] = '\0';
            return;
        }
        strncpy(dest, src, PATH_MAX - 1);
        dest[PATH_MAX - 1] = '\0';
    }

    static std::string to_lower(std::string value) {
        std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
            return static_cast<char>(std::tolower(c));
        });
        return value;
    }

    static bool contains_sensitive_word(const char* text) {
        if (text == nullptr) {
            return false;
        }
        std::string lower = to_lower(text);
        for (const char* const* word = kSensitiveWords; *word != nullptr; ++word) {
            if (lower.find(*word) != std::string::npos) {
                return true;
            }
        }
        return false;
    }

    static bool is_self_proc_file(const char* pathname, const char* name) {
        if (pathname == nullptr || name == nullptr) {
            return false;
        }

        char self_path[64];
        char proc_pid_path[64];
        char task_self_path[64];
        snprintf(self_path, sizeof(self_path), "/proc/self/%s", name);
        snprintf(proc_pid_path, sizeof(proc_pid_path), "/proc/%d/%s", getpid(), name);
        snprintf(task_self_path, sizeof(task_self_path), "/proc/thread-self/%s", name);
        return strcmp(pathname, self_path) == 0
               || strcmp(pathname, proc_pid_path) == 0
               || strcmp(pathname, task_self_path) == 0;
    }

    static bool is_maps_path(const char* pathname) {
        return is_self_proc_file(pathname, "maps");
    }

    static bool is_smaps_path(const char* pathname) {
        return is_self_proc_file(pathname, "smaps");
    }

    static bool is_mem_path(const char* pathname) {
        return is_self_proc_file(pathname, "mem");
    }

    static bool is_dev_fuse_path(const char* pathname) {
        return pathname != nullptr && strcmp(pathname, "/dev/fuse") == 0;
    }

    static bool parse_decimal_fd(const char* text, int* out_fd) {
        if (text == nullptr || out_fd == nullptr || *text == '\0') {
            return false;
        }
        int value = 0;
        for (const char* p = text; *p != '\0'; ++p) {
            if (*p < '0' || *p > '9') {
                return false;
            }
            value = value * 10 + (*p - '0');
            if (value < 0 || value > 65535) {
                return false;
            }
        }
        *out_fd = value;
        return true;
    }

    static bool parse_proc_fd_path(const char* pathname, int* out_fd) {
        if (pathname == nullptr || out_fd == nullptr) {
            return false;
        }
        static constexpr char self_fd_prefix[] = "/proc/self/fd/";
        static constexpr char thread_self_fd_prefix[] = "/proc/thread-self/fd/";
        if (strncmp(pathname, self_fd_prefix, sizeof(self_fd_prefix) - 1) == 0) {
            return parse_decimal_fd(pathname + sizeof(self_fd_prefix) - 1, out_fd);
        }
        if (strncmp(pathname, thread_self_fd_prefix, sizeof(thread_self_fd_prefix) - 1) == 0) {
            return parse_decimal_fd(pathname + sizeof(thread_self_fd_prefix) - 1, out_fd);
        }
        char pid_fd_prefix[64];
        int prefix_len = snprintf(pid_fd_prefix, sizeof(pid_fd_prefix), "/proc/%d/fd/", getpid());
        if (prefix_len > 0
            && strncmp(pathname, pid_fd_prefix, static_cast<size_t>(prefix_len)) == 0) {
            return parse_decimal_fd(pathname + prefix_len, out_fd);
        }
        return false;
    }



    static bool path_matches_target_locked(const char* pathname) {
        if (pathname == nullptr || targetApkPath.empty()) {
            return false;
        }
        if (strcmp(pathname, targetApkPath.c_str()) == 0) {
            return true;
        }
        size_t target_len = targetApkPath.size();
        return strncmp(pathname, targetApkPath.c_str(), target_len) == 0
               && strcmp(pathname + target_len, " (deleted)") == 0;
    }

    static bool path_matches_redirect_locked(const char* pathname) {
        if (pathname == nullptr || redirectApkPath.empty()) {
            return false;
        }
        if (strcmp(pathname, redirectApkPath.c_str()) == 0) {
            return true;
        }
        size_t redirect_len = redirectApkPath.size();
        return strncmp(pathname, redirectApkPath.c_str(), redirect_len) == 0
               && strcmp(pathname + redirect_len, " (deleted)") == 0;
    }



    static int find_lib_snapshot_index_by_fd(int fd) {
        if (fd < 0) {
            return -1;
        }
        for (size_t i = 0; i < sizeof(g_lib_snapshots) / sizeof(g_lib_snapshots[0]); ++i) {
            if (g_lib_snapshots[i].fd == fd) {
                return static_cast<int>(i);
            }
        }
        return -1;
    }

    static bool fd_is_lib_snapshot(int fd) {
        if (fd < 0) {
            return false;
        }
        if (find_lib_snapshot_index_by_fd(fd) >= 0) {
            return true;
        }
        return false;
    }

    static const char* neutral_runtime_lib_path() {
        return sizeof(void*) == 8
               ? "/apex/com.android.runtime/lib64/bionic/libc.so"
               : "/apex/com.android.runtime/lib/bionic/libc.so";
    }

    static int find_lib_snapshot_index_by_visible_path(const char* pathname) {
        if (pathname == nullptr || !g_lib_hide_enabled) {
            return -1;
        }
        for (size_t i = 0; i < sizeof(g_lib_snapshots) / sizeof(g_lib_snapshots[0]); ++i) {
            const auto& snapshot = g_lib_snapshots[i];
            if (snapshot.path[0] == '\0' || snapshot.visible_path[0] == '\0') {
                continue;
            }
            if (strcmp(pathname, snapshot.visible_path) == 0) {
                return static_cast<int>(i);
            }
        }
        return -1;
    }

    static bool try_get_lib_snapshot_visible_path(const char* pathname, std::string* out) {
        if (pathname == nullptr || out == nullptr || !g_lib_hide_enabled) {
            return false;
        }
        int fd = -1;
        if (!parse_proc_fd_path(pathname, &fd)) {
            return false;
        }
        int snapshot_index = find_lib_snapshot_index_by_fd(fd);
        if (snapshot_index < 0) {
            return false;
        }
        const auto& snapshot = g_lib_snapshots[snapshot_index];
        *out = snapshot.visible_path[0] != '\0'
               ? snapshot.visible_path
               : neutral_runtime_lib_path();
        return true;
    }

    static const char* get_visible_or_redirected_path(const char* pathname,
                                                      bool prefer_visible,
                                                      std::string* storage) {
        if (pathname == nullptr || storage == nullptr) {
            return pathname;
        }

        {
            std::scoped_lock lock(g_path_mutex);
            if (prefer_visible && path_matches_redirect_locked(pathname)) {
                *storage = targetApkPath;
                return storage->c_str();
            }
            if (!prefer_visible && path_matches_target_locked(pathname)) {
                *storage = redirectApkPath;
                return storage->c_str();
            }
        }

        if (prefer_visible && try_get_lib_snapshot_visible_path(pathname, storage)) {
            return storage->c_str();
        }



        if (!prefer_visible) {
            int snapshot_index = find_lib_snapshot_index_by_visible_path(pathname);
            if (snapshot_index >= 0) {
                *storage = g_lib_snapshots[snapshot_index].path;
                return storage->c_str();
            }
        }

        return pathname;
    }

    static bool query_redirected_statx(const char* visible_path, struct statx* stx) {
        if (visible_path == nullptr || stx == nullptr) {
            return false;
        }
        std::string redirected_path;
        const char* actual_path = get_visible_or_redirected_path(visible_path, false, &redirected_path);
        if (actual_path == visible_path) {
            return false;
        }
        memset(stx, 0, sizeof(*stx));
        long rc = syscall(__NR_statx, AT_FDCWD, actual_path, 0, STATX_BASIC_STATS, stx);
        return rc == 0;
    }

    static bool query_visible_statx(const char* visible_path, struct statx* stx) {
        if (visible_path == nullptr || stx == nullptr) {
            return false;
        }
        memset(stx, 0, sizeof(*stx));
        long rc = syscall(__NR_statx, AT_FDCWD, visible_path, 0, STATX_BASIC_STATS, stx);
        return rc == 0;
    }

    template <typename StatLike>
    static bool rewrite_stat_like_result(const char* visible_path, StatLike* st) {
        if (visible_path == nullptr || st == nullptr) {
            return false;
        }
        struct statx stx = {};
        if (!query_redirected_statx(visible_path, &stx)) {
            return false;
        }
        st->st_ino = stx.stx_ino;
        st->st_mode = stx.stx_mode;
        st->st_nlink = stx.stx_nlink;
        st->st_uid = stx.stx_uid;
        st->st_gid = stx.stx_gid;
        st->st_size = stx.stx_size;
        st->st_blocks = stx.stx_blocks;
        st->st_blksize = static_cast<decltype(st->st_blksize)>(stx.stx_blksize);
        return true;
    }

    static bool rewrite_statx_result(const char* visible_path, struct statx* stx) {
        if (visible_path == nullptr || stx == nullptr) {
            return false;
        }
        struct statx redirected = {};
        if (!query_redirected_statx(visible_path, &redirected)) {
            return false;
        }
        *stx = redirected;
        return true;
    }



    static bool parse_maps_entry(const char* line, MapEntry* entry) {
        if (line == nullptr || entry == nullptr) {
            return false;
        }
        unsigned long start = 0;
        unsigned long end = 0;
        unsigned long offset = 0;
        unsigned long long inode = 0;
        char perms[5] = {0};
        char dev[32] = {0};
        char path[PATH_MAX] = {0};
        int fields = sscanf(line, "%lx-%lx %4s %lx %31s %llu %4095s",
                            &start, &end, perms, &offset, dev, &inode, path);
        if (fields < 4) {
            return false;
        }
        entry->start = static_cast<uintptr_t>(start);
        entry->end = static_cast<uintptr_t>(end);
        entry->offset = offset;
        entry->inode = fields >= 6 ? inode : 0;
        strncpy(entry->perms, perms, sizeof(entry->perms) - 1);
        entry->perms[sizeof(entry->perms) - 1] = '\0';
        if (fields >= 5) {
            strncpy(entry->dev, dev, sizeof(entry->dev) - 1);
            entry->dev[sizeof(entry->dev) - 1] = '\0';
        } else {
            entry->dev[0] = '\0';
        }
        if (fields >= 7) {
            copy_path(entry->path, path);
        } else {
            entry->path[0] = '\0';
        }
        return true;
    }

    static int create_memfd_from_string(const char* name, const std::string& content) {
        int fd = static_cast<int>(syscall(__NR_memfd_create, name, MFD_CLOEXEC));
        if (fd < 0) {
            return -1;
        }
        const char* data = content.data();
        size_t left = content.size();
        while (left > 0) {
            ssize_t written = write(fd, data, left);
            if (written < 0) {
                if (errno == EINTR) {
                    continue;
                }
                close(fd);
                return -1;
            }
            data += written;
            left -= static_cast<size_t>(written);
        }
        lseek(fd, 0, SEEK_SET);
        return fd;
    }

    static std::string read_fd_to_string(int fd) {
        std::string content;
        char buffer[8192];
        while (true) {
            ssize_t bytes = read(fd, buffer, sizeof(buffer));
            if (bytes < 0) {
                if (errno == EINTR) {
                    continue;
                }
                break;
            }
            if (bytes == 0) {
                break;
            }
            content.append(buffer, static_cast<size_t>(bytes));
        }
        return content;
    }

    static const char* find_snapshot_path_for_line(const char* line) {
        if (line == nullptr) {
            return nullptr;
        }
        for (auto& snapshot : g_lib_snapshots) {
            if (snapshot.path[0] != '\0' && strstr(line, snapshot.soname) != nullptr) {
                return snapshot.path;
            }
        }
        return nullptr;
    }

    static bool is_anonymous_executable_line(const char* line, const MapEntry& entry) {
        if (line == nullptr) {
            return false;
        }
        bool executable = strstr(entry.perms, "r-xp") != nullptr || strstr(entry.perms, "--xp") != nullptr;
        return executable && entry.path[0] == '\0';
    }

    static bool stat_path_for_maps(const std::string& path, char* out_dev, size_t out_dev_size,
                                   unsigned long long* out_inode) {
        if (path.empty() || out_dev == nullptr || out_inode == nullptr || out_dev_size == 0) {
            return false;
        }

        struct stat st = {};
        if (stat(path.c_str(), &st) != 0) {
            return false;
        }

        snprintf(out_dev, out_dev_size, "%02x:%02x",
                 static_cast<unsigned int>(major(st.st_dev)),
                 static_cast<unsigned int>(minor(st.st_dev)));
        *out_inode = static_cast<unsigned long long>(st.st_ino);
        return true;
    }

    static bool map_path_matches_target(const char* path) {
        if (path == nullptr || path[0] == '\0') {
            return false;
        }
        if (targetApkPath.empty()) {
            return false;
        }
        if (strcmp(path, targetApkPath.c_str()) == 0) {
            return true;
        }
        size_t target_len = targetApkPath.size();
        return strncmp(path, targetApkPath.c_str(), target_len) == 0
               && strcmp(path + target_len, " (deleted)") == 0;
    }

    static std::string rewrite_apk_inode_maps_content(const std::string& content) {
        char redirect_dev[32] = {0};
        unsigned long long redirect_inode = 0;
        if (!stat_path_for_maps(redirectApkPath, redirect_dev, sizeof(redirect_dev), &redirect_inode)) {
            return content;
        }

        std::string rewritten_content;
        size_t pos = 0;
        while (pos < content.size()) {
            size_t end = content.find('\n', pos);
            if (end == std::string::npos) {
                end = content.size();
            }
            std::string line = content.substr(pos, end - pos);
            bool has_newline = end < content.size();
            pos = has_newline ? end + 1 : end;

            MapEntry entry;
            if (parse_maps_entry(line.c_str(), &entry) && map_path_matches_target(entry.path)) {
                char rewritten[PATH_MAX + 128];
                snprintf(rewritten, sizeof(rewritten),
                         "%012lx-%012lx %s %08lx %s %llu %s",
                         static_cast<unsigned long>(entry.start),
                         static_cast<unsigned long>(entry.end),
                         entry.perms,
                         entry.offset,
                         redirect_dev,
                         redirect_inode,
                         entry.path);
                line = rewritten;
            }

            rewritten_content += line;
            if (has_newline) {
                rewritten_content += '\n';
            }
        }
        return rewritten_content;
    }

    static std::string sanitize_maps_like_content(const std::string& content) {
        ensure_lib_snapshots();

        std::string sanitized;
        size_t pos = 0;
        while (pos < content.size()) {
            size_t end = content.find('\n', pos);
            if (end == std::string::npos) {
                end = content.size();
            }
            std::string line = content.substr(pos, end - pos);
            bool has_newline = end < content.size();
            pos = has_newline ? end + 1 : end;

            if (contains_sensitive_word(line.c_str())) {
                continue;
            }

            MapEntry entry;
            if (parse_maps_entry(line.c_str(), &entry)) {
                const char* snapshot_path = find_snapshot_path_for_line(line.c_str());
                if (snapshot_path != nullptr) {
                    char rewritten[PATH_MAX + 128];
                    snprintf(rewritten, sizeof(rewritten),
                             "%012lx-%012lx %s %08lx 00:00 0 %s",
                             static_cast<unsigned long>(entry.start),
                             static_cast<unsigned long>(entry.end),
                             entry.perms,
                             entry.offset,
                             snapshot_path);
                    line = rewritten;
                } else if (is_anonymous_executable_line(line.c_str(), entry)) {
                    size_t perm_pos = line.find(entry.perms);
                    if (perm_pos != std::string::npos) {
                        line.replace(perm_pos, strlen(entry.perms), "r--p");
                    }
                }
            }

            sanitized += line;
            if (has_newline) {
                sanitized += '\n';
            }
        }
        return sanitized;
    }

    static int open_read_only_native(const char* pathname) {
        return static_cast<int>(syscall(__NR_openat,
                                        AT_FDCWD,
                                        pathname,
                                        O_RDONLY | O_CLOEXEC));
    }

    static bool create_lib_snapshot_from_maps(const char* soname, char* out_path) {
        if (soname == nullptr || out_path == nullptr) {
            return false;
        }
        int maps_fd = open_read_only_native("/proc/self/maps");
        if (maps_fd < 0) {
            return false;
        }
        std::string maps = read_fd_to_string(maps_fd);
        close(maps_fd);

        char source_path[PATH_MAX] = {0};
        size_t pos = 0;
        while (pos < maps.size()) {
            size_t end = maps.find('\n', pos);
            if (end == std::string::npos) {
                end = maps.size();
            }
            std::string line = maps.substr(pos, end - pos);
            pos = end < maps.size() ? end + 1 : end;

            if (line.find(soname) == std::string::npos) {
                continue;
            }
            MapEntry entry;
            if (!parse_maps_entry(line.c_str(), &entry) || entry.path[0] == '\0') {
                continue;
            }
            if (entry.path[0] == '/') {
                copy_path(source_path, entry.path);
                break;
            }
        }

        if (source_path[0] == '\0') {
            return false;
        }

        int source_fd = open_read_only_native(source_path);
        if (source_fd < 0) {
            return false;
        }
        struct stat st = {};
        if (fstat(source_fd, &st) != 0 || st.st_size <= 0) {
            close(source_fd);
            return false;
        }
        void* file_data = mmap(nullptr, st.st_size, PROT_READ | PROT_WRITE,
                               MAP_PRIVATE, source_fd, 0);
        close(source_fd);
        if (file_data == MAP_FAILED) {
            return false;
        }

        auto* ehdr = reinterpret_cast<ElfW(Ehdr)*>(file_data);
        if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) == 0
                && ehdr->e_phoff > 0
                && ehdr->e_phnum > 0) {
            auto* phdr = reinterpret_cast<ElfW(Phdr)*>(
                    reinterpret_cast<char*>(file_data) + ehdr->e_phoff);

            pos = 0;
            while (pos < maps.size()) {
                size_t end = maps.find('\n', pos);
                if (end == std::string::npos) {
                    end = maps.size();
                }
                std::string line = maps.substr(pos, end - pos);
                pos = end < maps.size() ? end + 1 : end;

                MapEntry entry;
                if (!parse_maps_entry(line.c_str(), &entry)
                        || strstr(entry.path, soname) == nullptr
                        || strcmp(entry.path, source_path) != 0) {
                    continue;
                }

                for (int i = 0; i < ehdr->e_phnum; ++i) {
                    if (phdr[i].p_type != PT_LOAD
                            || phdr[i].p_offset != static_cast<ElfW(Off)>(entry.offset)
                            || phdr[i].p_offset >= static_cast<ElfW(Off)>(st.st_size)) {
                        continue;
                    }
                    size_t map_size = entry.end > entry.start ? entry.end - entry.start : 0;
                    size_t copy_size = std::min(static_cast<size_t>(phdr[i].p_memsz), map_size);
                    copy_size = std::min(copy_size, static_cast<size_t>(st.st_size - phdr[i].p_offset));
                    memcpy(reinterpret_cast<char*>(file_data) + phdr[i].p_offset,
                           reinterpret_cast<void*>(entry.start), copy_size);
                }
            }
        }

        int snapshot_fd = static_cast<int>(syscall(__NR_memfd_create, "runtime-cache", MFD_CLOEXEC));
        if (snapshot_fd < 0) {
            munmap(file_data, st.st_size);
            return false;
        }
        const char* data = reinterpret_cast<const char*>(file_data);
        size_t left = static_cast<size_t>(st.st_size);
        while (left > 0) {
            ssize_t written = write(snapshot_fd, data, left);
            if (written < 0) {
                if (errno == EINTR) {
                    continue;
                }
                close(snapshot_fd);
                munmap(file_data, st.st_size);
                return false;
            }
            data += written;
            left -= static_cast<size_t>(written);
        }
        munmap(file_data, st.st_size);
        snprintf(out_path, PATH_MAX, "/proc/self/fd/%d", snapshot_fd);
        for (auto& snapshot : g_lib_snapshots) {
            if (snapshot.soname == soname) {
                snapshot.fd = snapshot_fd;
                copy_path(snapshot.visible_path, source_path);
                break;
            }
        }
        return true;
    }

    static void ensure_lib_snapshots() {
        for (auto& snapshot : g_lib_snapshots) {
            if (snapshot.path[0] == '\0') {
                create_lib_snapshot_from_maps(snapshot.soname, snapshot.path);
            }
        }
    }

    void PrepareLibHideSnapshots() {
        g_lib_hide_enabled = true;
        ensure_lib_snapshots();
    }

    void RefreshLibHideSnapshots() {
        if (!g_lib_hide_enabled) {
            return;
        }
        for (auto& snapshot : g_lib_snapshots) {
            if (snapshot.fd >= 0) {
                syscall(__NR_close, snapshot.fd);
                snapshot.fd = -1;
            }
            snapshot.path[0] = '\0';
            snapshot.visible_path[0] = '\0';
            create_lib_snapshot_from_maps(snapshot.soname, snapshot.path);
        }
    }

    static bool is_jiagu_or_stub_caller(const void* caller_pc) {
        if (caller_pc == nullptr) {
            return true;
        }

        Dl_info info = {};
        if (dladdr(caller_pc, &info) == 0 || info.dli_fname == nullptr || info.dli_fname[0] == '\0') {
            return true;
        }

        const char* fname = info.dli_fname;
        if (strstr(fname, "libnpatch.so") != nullptr) {
            return true;
        }

        std::string caller_path = to_lower(fname);
        return caller_path.find("/.jiagu/") != std::string::npos
               || caller_path.find("libjiagu") != std::string::npos
               || caller_path.find("jiagu") != std::string::npos
               || caller_path.find("qihoo") != std::string::npos
               || caller_path.find("qihu") != std::string::npos
               || caller_path.find("360") != std::string::npos;
    }

    static bool path_is_under_root(const std::string& path, const std::string& root) {
        if (root.empty() || path.size() < root.size() || path.compare(0, root.size(), root) != 0) {
            return false;
        }
        return path.size() == root.size() || path[root.size()] == '/';
    }

    static bool is_npatch_module_native_caller(const void* caller_pc) {
        if (caller_pc == nullptr) return false;
        Dl_info info = {};
        if (dladdr(caller_pc, &info) == 0 || info.dli_fname == nullptr || info.dli_fname[0] == '\0') {
            return false;
        }
        std::string caller_path(info.dli_fname);
        static constexpr char deleted_suffix[] = " (deleted)";
        if (caller_path.size() >= sizeof(deleted_suffix) - 1
            && caller_path.compare(caller_path.size() - (sizeof(deleted_suffix) - 1),
                                   sizeof(deleted_suffix) - 1, deleted_suffix) == 0) {
            caller_path.resize(caller_path.size() - (sizeof(deleted_suffix) - 1));
        }
        std::scoped_lock lock(g_path_mutex);
        for (const auto& root : moduleNativeLibraryRoots) {
            if (path_is_under_root(caller_path, root)) return true;
        }
        return false;
    }

    static bool should_redirect_apk_contents(const void* caller_pc) {
        // 【重要】这里必须按调用方分流。targetApkPath 是外层修补 APK，而 redirectApkPath
        // （origin.apk）不含 NPatch 注入的模块/加固资源。若把加固模块 JNI_OnLoad 对 APK 的
        // 读取重定向到 origin.apk，会导致 JNI_OnLoad/UnsatisfiedLinkError、模块无法加载。
        // 禁止将这里简化为无条件返回 true。
        return !is_npatch_module_native_caller(caller_pc);
    }

    int open_sanitized_proc_file(const char* pathname, const void* caller_pc) {
        if (pathname == nullptr || strncmp(pathname, "/proc/", 6) != 0) {
            return -1;
        }
        if (minimal_file_hook_mode) {
            if (!is_maps_path(pathname) && !is_smaps_path(pathname)) {
                return -1;
            }
            int fd = open_read_only_native(pathname);
            if (fd < 0) {
                return -1;
            }
            std::string content = read_fd_to_string(fd);
            close(fd);
            content = rewrite_apk_inode_maps_content(content);
            if (g_lib_hide_enabled) {
                content = sanitize_maps_like_content(content);
            }
            return create_memfd_from_string("npatch_apk_maps_view",
                                            content);
        }
        if (is_jiagu_or_stub_caller(caller_pc)) {
            return -1;
        }
        if (is_mem_path(pathname)) {
            return -1;
        }

        if (!is_maps_path(pathname) && !is_smaps_path(pathname)) {
            return -1;
        }

        if (!g_lib_hide_enabled) {
            return -1;
        }

        int fd = open_read_only_native(pathname);
        if (fd < 0) {
            return -1;
        }
        std::string content = read_fd_to_string(fd);
        close(fd);
        return create_memfd_from_string("npatch_proc_view", sanitize_maps_like_content(content));
    }

    static bool is_read_only_open(int flags) {
        return (flags & O_ACCMODE) == O_RDONLY;
    }

    static const char* resolve_redirect_path(const char* pathname) {
        if (pathname == nullptr || pathname[0] != '/') {
            return pathname;
        }

        {
            std::scoped_lock lock(g_path_mutex);
            // Only redirect the patched APK itself back to the original flow.
            if (!targetApkPath.empty()
                && !redirectApkPath.empty()
                && strcmp(pathname, targetApkPath.c_str()) == 0) {
                g_redirect_buffer = redirectApkPath;
                return g_redirect_buffer.c_str();
            }
        }

        int snapshot_index = find_lib_snapshot_index_by_visible_path(pathname);
        if (snapshot_index >= 0) {
            g_redirect_buffer = g_lib_snapshots[snapshot_index].path;
            return g_redirect_buffer.c_str();
        }
        return pathname;
    }

    static int call_openat(OpenAtFn backup,
                           int dirfd,
                           const char* pathname,
                           int flags,
                           mode_t mode,
                           bool has_mode) {
        if (backup == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        if (has_mode) {
            return backup(dirfd, pathname, flags, mode);
        }
        return backup(dirfd, pathname, flags);
    }

    static int hooked_openat_impl(OpenAtFn backup,
                                  const char* symbol_name,
                                  int dirfd,
                                  const char* pathname,
                                  int flags,
                                  va_list ap,
                                  const void* caller_pc) {
        const bool has_mode = needs_mode(flags);
        const mode_t mode = has_mode ? va_arg(ap, mode_t) : 0;
        const char* redirected_path = pathname;

        if (!g_openat_reentry) {
            // 某些 ROM 可能讓底層再次回到 openat，這裡先擋遞迴重入。
            g_openat_reentry = true;
            if (is_read_only_open(flags)) {
                int sanitized_fd = open_sanitized_proc_file(pathname, caller_pc);
                if (sanitized_fd >= 0) {
                    LOGD("SigBypass: Serve sanitized {} for {}", symbol_name, pathname);
                    g_openat_reentry = false;
                    return sanitized_fd;
                }
            }
            if (should_redirect_apk_contents(caller_pc)) {
                redirected_path = resolve_redirect_path(pathname);
                if (redirected_path != pathname && redirected_path != nullptr) {
                    LOGD("SigBypass: Redirecting {}('{}') -> '{}'",
                         symbol_name, pathname, redirected_path);
                }
            }
            g_openat_reentry = false;
        }
        int result = call_openat(backup, dirfd, redirected_path, flags, mode, has_mode);

        return result;
    }

    static int call_open(OpenFn backup,
                         const char* pathname,
                         int flags,
                         mode_t mode,
                         bool has_mode) {
        if (backup == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        if (has_mode) {
            return backup(pathname, flags, mode);
        }
        return backup(pathname, flags);
    }

    static int hooked_open_impl(OpenFn backup,
                                const char* symbol_name,
                                const char* pathname,
                                int flags,
                                va_list ap,
                                const void* caller_pc) {
        const bool has_mode = needs_mode(flags);
        const mode_t mode = has_mode ? va_arg(ap, mode_t) : 0;
        const char* redirected_path = pathname;

        if (!g_openat_reentry) {
            g_openat_reentry = true;
            if (is_read_only_open(flags)) {
                int sanitized_fd = open_sanitized_proc_file(pathname, caller_pc);
                if (sanitized_fd >= 0) {
                    LOGD("SigBypass: Serve sanitized {} for {}", symbol_name, pathname);
                    g_openat_reentry = false;
                    return sanitized_fd;
                }
            }
            if (should_redirect_apk_contents(caller_pc)) {
                redirected_path = resolve_redirect_path(pathname);
                if (redirected_path != pathname && redirected_path != nullptr) {
                    LOGD("SigBypass: Redirecting {}('{}') -> '{}'",
                         symbol_name, pathname, redirected_path);
                }
            }
            g_openat_reentry = false;
        }

        int result = call_open(backup, redirected_path, flags, mode, has_mode);

        return result;
    }

    static FILE* hooked_fopen_impl(FopenFn backup,
                                   const char* pathname,
                                   const char* mode,
                                   const void* caller_pc) {
        if (backup == nullptr) {
            errno = ENOSYS;
            return nullptr;
        }

        const char* redirected_path = pathname;
        if (!g_fopen_reentry) {
            g_fopen_reentry = true;
            const bool read_only = mode != nullptr && mode[0] == 'r' && strchr(mode, '+') == nullptr;
            if (read_only) {
                g_openat_reentry = true;
                int sanitized_fd = open_sanitized_proc_file(pathname, caller_pc);
                g_openat_reentry = false;
                if (sanitized_fd >= 0) {
                    FILE* fp = fdopen(sanitized_fd, mode);
                    if (fp != nullptr) {
                        LOGD("SigBypass: Serve sanitized fopen for %s", pathname);
                        g_fopen_reentry = false;
                        return fp;
                    }
                    close(sanitized_fd);
                }
            }
            if (should_redirect_apk_contents(caller_pc)) {
                redirected_path = resolve_redirect_path(pathname);
                if (redirected_path != pathname && redirected_path != nullptr) {
                    LOGD("SigBypass: Redirecting fopen('%s') -> '%s'", pathname, redirected_path);
                }
            }
            g_fopen_reentry = false;
        }

        return backup(redirected_path, mode);
    }

    static int hooked_openat(int dirfd, const char* pathname, int flags, ...) {
        va_list ap;
        va_start(ap, flags);
        const int result = hooked_openat_impl(openat_backup, "openat", dirfd, pathname, flags, ap,
                                              __builtin_return_address(0));
        va_end(ap);
        return result;
    }

    static int hooked_open(const char* pathname, int flags, ...) {
        va_list ap;
        va_start(ap, flags);
        const int result = hooked_open_impl(open_backup, "open", pathname, flags, ap,
                                            __builtin_return_address(0));
        va_end(ap);
        return result;
    }

    static int hooked_open64(const char* pathname, int flags, ...) {
        va_list ap;
        va_start(ap, flags);
        const int result = hooked_open_impl(open64_backup, "open64", pathname, flags, ap,
                                            __builtin_return_address(0));
        va_end(ap);
        return result;
    }

    static int hooked___open_2(const char* pathname, int flags) {
        const void* caller_pc = __builtin_return_address(0);
        if (!g_openat_reentry) {
            g_openat_reentry = true;
            if (is_read_only_open(flags)) {
                int sanitized_fd = open_sanitized_proc_file(pathname, caller_pc);
                if (sanitized_fd >= 0) {
                    LOGD("SigBypass: Serve sanitized __open_2 for {}", pathname);
                    g_openat_reentry = false;
                    return sanitized_fd;
                }
            }
            g_openat_reentry = false;
        }

        if (__open_2_backup == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        std::string redirected_path_storage;
        const char* redirected_path = pathname;
        if (should_redirect_apk_contents(caller_pc)) {
            redirected_path = get_visible_or_redirected_path(pathname, false, &redirected_path_storage);
        }
        int result = __open_2_backup(redirected_path, flags);

        return result;
    }

    static FILE* hooked_fopen(const char* pathname, const char* mode) {
        return hooked_fopen_impl(fopen_backup, pathname, mode, __builtin_return_address(0));
    }

    static int hooked_access(const char* pathname, int mode) {
        if (access_backup == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        if (is_dev_fuse_path(pathname)) {
            errno = ENOENT;
            return -1;
        }
        std::string redirected_path_storage;
        const char* redirected_path = get_visible_or_redirected_path(pathname, false, &redirected_path_storage);
        return access_backup(redirected_path, mode);
    }

    static ssize_t hooked_readlink(const char* pathname, char* buf, size_t bufsiz) {
        if (readlink_backup == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        if (is_dev_fuse_path(pathname)) {
            errno = ENOENT;
            return -1;
        }
        std::string visible_path;
        if (try_get_lib_snapshot_visible_path(pathname, &visible_path)) {
            size_t len = std::min(visible_path.size(), bufsiz);
            memcpy(buf, visible_path.data(), len);
            return static_cast<ssize_t>(len);
        }

        std::string redirected_path_storage;
        const char* redirected_path = get_visible_or_redirected_path(pathname, false, &redirected_path_storage);
        ssize_t rc = readlink_backup(redirected_path, buf, bufsiz);
        if (rc > 0) {
            std::string mapped(buf, static_cast<size_t>(rc));
            std::string mapped_storage;
            const char* mapped_visible = get_visible_or_redirected_path(mapped.c_str(), true, &mapped_storage);
            size_t len = std::min(strlen(mapped_visible), bufsiz);
            memcpy(buf, mapped_visible, len);
            rc = static_cast<ssize_t>(len);
        }
        return rc;
    }

    static ssize_t hooked_readlinkat(int dirfd, const char* pathname, char* buf, size_t bufsiz) {
        if (readlinkat_backup == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        ProcFdReadlinkatPath effective_path;
        prepare_proc_fd_readlinkat_path(&effective_path, dirfd, pathname,
                                        [](const char* link_path, char* resolved_path, size_t size) -> ssize_t {
                                            return syscall(__NR_readlinkat,
                                                           AT_FDCWD,
                                                           link_path,
                                                           resolved_path,
                                                           size);
                                        });
        if (is_dev_fuse_path(effective_path.path())) {
            errno = ENOENT;
            return -1;
        }
        std::string visible_path;
        if (try_get_lib_snapshot_visible_path(effective_path.path(), &visible_path)) {
            size_t len = std::min(visible_path.size(), bufsiz);
            memcpy(buf, visible_path.data(), len);
            return static_cast<ssize_t>(len);
        }

        std::string redirected_path_storage;
        const char* redirected_path = get_visible_or_redirected_path(effective_path.path(),
                                                                     false,
                                                                     &redirected_path_storage);
        ssize_t rc = readlinkat_backup(effective_path.dirfd, redirected_path, buf, bufsiz);
        if (rc > 0) {
            std::string raw(buf, static_cast<size_t>(rc));
            std::string mapped_storage;
            const char* mapped = get_visible_or_redirected_path(raw.c_str(), true, &mapped_storage);
            size_t len = std::min(strlen(mapped), bufsiz);
            memcpy(buf, mapped, len);
            rc = static_cast<ssize_t>(len);
        }
        return rc;
    }

    static char* hooked_realpath(const char* pathname, char* resolved_path) {
        if (realpath_backup == nullptr) {
            errno = ENOSYS;
            return nullptr;
        }
        if (is_dev_fuse_path(pathname)) {
            errno = ENOENT;
            return nullptr;
        }
        std::string redirected_path_storage;
        const char* redirected_path = get_visible_or_redirected_path(pathname, false, &redirected_path_storage);
        char* result = realpath_backup(redirected_path, resolved_path);
        if (result == nullptr) {
            return nullptr;
        }
        std::string visible_storage;
        const char* visible = get_visible_or_redirected_path(result, true, &visible_storage);
        if (visible != result) {
            if (resolved_path != nullptr) {
                strncpy(resolved_path, visible, PATH_MAX - 1);
                resolved_path[PATH_MAX - 1] = '\0';
                return resolved_path;
            }
            char* duplicated = strdup(visible);
            return duplicated;
        }
        return result;
    }

    static int hooked_stat(const char* pathname, struct stat* st) {
        if (stat_backup == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        if (is_dev_fuse_path(pathname)) {
            errno = ENOENT;
            return -1;
        }
        std::string redirected_path_storage;
        const char* redirected_path = get_visible_or_redirected_path(pathname, false, &redirected_path_storage);
        int rc = stat_backup(redirected_path, st);
        if (rc == 0) {
            rewrite_stat_like_result(pathname, st);
        }
        return rc;
    }

    static int hooked_lstat(const char* pathname, struct stat* st) {
        if (lstat_backup == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        if (is_dev_fuse_path(pathname)) {
            errno = ENOENT;
            return -1;
        }
        std::string redirected_path_storage;
        const char* redirected_path = get_visible_or_redirected_path(pathname, false, &redirected_path_storage);
        int rc = lstat_backup(redirected_path, st);
        if (rc == 0) {
            rewrite_stat_like_result(pathname, st);
        }
        return rc;
    }

    static int hooked_stat64(const char* pathname, struct stat64* st) {
        if (stat64_backup == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        if (is_dev_fuse_path(pathname)) {
            errno = ENOENT;
            return -1;
        }
        std::string redirected_path_storage;
        const char* redirected_path = get_visible_or_redirected_path(pathname, false, &redirected_path_storage);
        int rc = stat64_backup(redirected_path, st);
        if (rc == 0) {
            rewrite_stat_like_result(pathname, st);
        }
        return rc;
    }

    static int hooked_lstat64(const char* pathname, struct stat64* st) {
        if (lstat64_backup == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        if (is_dev_fuse_path(pathname)) {
            errno = ENOENT;
            return -1;
        }
        std::string redirected_path_storage;
        const char* redirected_path = get_visible_or_redirected_path(pathname, false, &redirected_path_storage);
        int rc = lstat64_backup(redirected_path, st);
        if (rc == 0) {
            rewrite_stat_like_result(pathname, st);
        }
        return rc;
    }

    static int hooked_statfs(const char* pathname, struct statfs* st) {
        if (statfs_backup == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        if (is_dev_fuse_path(pathname)) {
            errno = ENOENT;
            return -1;
        }
        std::string redirected_path_storage;
        const char* redirected_path = get_visible_or_redirected_path(pathname, false, &redirected_path_storage);
        return statfs_backup(redirected_path, st);
    }

    static int hooked_statx(int dirfd, const char* pathname, int flags, unsigned int mask, struct statx* stx) {
        if (statx_backup == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        if (is_dev_fuse_path(pathname)) {
            errno = ENOENT;
            return -1;
        }
        std::string redirected_path_storage;
        const char* redirected_path = get_visible_or_redirected_path(pathname, false, &redirected_path_storage);
        int rc = statx_backup(dirfd, redirected_path, flags, mask, stx);
        if (rc == 0) {
            rewrite_statx_result(pathname, stx);
        }
        return rc;
    }



    struct DlIteratePhdrCallbackContext {
        int (*callback)(struct dl_phdr_info*, size_t, void*) = nullptr;
        void* data = nullptr;
    };

    static int sanitized_dl_iterate_phdr_callback(struct dl_phdr_info* info,
                                                  size_t size,
                                                  void* data) {
        auto* context = static_cast<DlIteratePhdrCallbackContext*>(data);
        if (context == nullptr || context->callback == nullptr) {
            return 0;
        }
        if (g_lib_hide_enabled && info != nullptr && contains_sensitive_word(info->dlpi_name)) {
            return 0;
        }
        return context->callback(info, size, context->data);
    }

    static int hooked_dl_iterate_phdr(int (*callback)(struct dl_phdr_info*, size_t, void*),
                                      void* data) {
        if (dl_iterate_phdr_backup == nullptr) {
            errno = ENOSYS;
            return 0;
        }
        if (!g_lib_hide_enabled || callback == nullptr) {
            return dl_iterate_phdr_backup(callback, data);
        }

        DlIteratePhdrCallbackContext context = {
                .callback = callback,
                .data = data,
        };
        return dl_iterate_phdr_backup(sanitized_dl_iterate_phdr_callback, &context);
    }



    static int hooked_openat64(int dirfd, const char* pathname, int flags, ...) {
        va_list ap;
        va_start(ap, flags);
        const int result = hooked_openat_impl(openat64_backup, "openat64", dirfd, pathname, flags, ap,
                                              __builtin_return_address(0));
        va_end(ap);
        return result;
    }

    static bool install_openat_hook(const char* symbol_name,
                                    int (*replacement)(int, const char*, int, ...),
                                    void** target_slot,
                                    OpenAtFn* backup_slot,
                                    bool* installed_slot) {
        // 路徑可重複刷新，但 native hook 只安裝一次，避免多次 inline hook 弄亂備援鏈。
        if (*installed_slot) {
            return true;
        }

        void* symbol = dlsym(RTLD_DEFAULT, symbol_name);
        if (symbol == nullptr) {
            LOGW("SigBypass: Symbol {} not found", symbol_name);
            return false;
        }

        if (HookInline(symbol, reinterpret_cast<void*>(replacement),
                       reinterpret_cast<void**>(backup_slot)) != 0) {
            LOGE("SigBypass: Failed to hook {}", symbol_name);
            return false;
        }

        *target_slot = symbol;
        *installed_slot = true;
        LOGI("SigBypass: Hooked {}", symbol_name);
        return true;
    }

    static bool install_open_hook(const char* symbol_name,
                                  int (*replacement)(const char*, int, ...),
                                  void** target_slot,
                                  OpenFn* backup_slot,
                                  bool* installed_slot) {
        if (*installed_slot) {
            return true;
        }

        void* symbol = dlsym(RTLD_DEFAULT, symbol_name);
        if (symbol == nullptr) {
            LOGW("SigBypass: Symbol {} not found", symbol_name);
            return false;
        }

        if (HookInline(symbol, reinterpret_cast<void*>(replacement),
                       reinterpret_cast<void**>(backup_slot)) != 0) {
            LOGE("SigBypass: Failed to hook {}", symbol_name);
            return false;
        }

        *target_slot = symbol;
        *installed_slot = true;
        LOGI("SigBypass: Hooked {}", symbol_name);
        return true;
    }

    static bool install_open2_hook() {
        if (__open_2_hook_installed) {
            return true;
        }

        void* symbol = dlsym(RTLD_DEFAULT, "__open_2");
        if (symbol == nullptr) {
            LOGW("SigBypass: Symbol __open_2 not found");
            return false;
        }

        if (HookInline(symbol, reinterpret_cast<void*>(hooked___open_2),
                       reinterpret_cast<void**>(&__open_2_backup)) != 0) {
            LOGE("SigBypass: Failed to hook __open_2");
            return false;
        }

        __open_2_target = symbol;
        __open_2_hook_installed = true;
        LOGI("SigBypass: Hooked __open_2");
        return true;
    }

    static bool install_fopen_hook() {
        if (fopen_hook_installed) {
            return true;
        }

        void* symbol = dlsym(RTLD_DEFAULT, "fopen");
        if (symbol == nullptr) {
            LOGW("SigBypass: Symbol fopen not found");
            return false;
        }

        if (HookInline(symbol, reinterpret_cast<void*>(hooked_fopen),
                       reinterpret_cast<void**>(&fopen_backup)) != 0) {
            LOGE("SigBypass: Failed to hook fopen");
            return false;
        }

        fopen_target = symbol;
        fopen_hook_installed = true;
        LOGI("SigBypass: Hooked fopen");
        return true;
    }

    template <typename Fn>
    static bool install_plain_hook(const char* symbol_name,
                                   void* replacement,
                                   void** target_slot,
                                   Fn* backup_slot,
                                   bool* installed_slot) {
        if (*installed_slot) {
            return true;
        }
        void* symbol = dlsym(RTLD_DEFAULT, symbol_name);
        if (symbol == nullptr) {
            LOGW("SigBypass: Symbol {} not found", symbol_name);
            return false;
        }
        if (HookInline(symbol, replacement, reinterpret_cast<void**>(backup_slot)) != 0) {
            LOGE("SigBypass: Failed to hook {}", symbol_name);
            return false;
        }
        *target_slot = symbol;
        *installed_slot = true;
        LOGI("SigBypass: Hooked {}", symbol_name);
        return true;
    }

    static void enable_openat_hook_impl(JNIEnv* env,
                                        jstring jOrigApkPath,
                                        jstring jCacheApkPath,
                                        jstring jPkgName,
                                        bool minimal,
                                        bool hide) {

        if (jOrigApkPath == nullptr || jCacheApkPath == nullptr) {
            LOGE("Invalid arguments: paths cannot be null.");
            return;
        }

        lsplant::JUTFString strOrig(env, jOrigApkPath);
        lsplant::JUTFString strRedirect(env, jCacheApkPath);

        {
            std::scoped_lock lock(g_path_mutex);
            minimal_file_hook_mode = minimal_file_hook_mode || minimal;
            g_lib_hide_enabled = g_lib_hide_enabled || hide;
            targetApkPath = strOrig.get();
            redirectApkPath = strRedirect.get();

            if (jPkgName != nullptr) {
                lsplant::JUTFString strPkg(env, jPkgName);
                currentPackageName = strPkg.get();
            }
        }

        LOGI("Enable OpenAt Hook: {} -> {} (Pkg: {}, Hide: {})",
             targetApkPath.c_str(), redirectApkPath.c_str(), currentPackageName.c_str(), g_lib_hide_enabled);

        const bool openat_ok = install_openat_hook("openat", hooked_openat,
                                                   &openat_target, &openat_backup,
                                                   &openat_hook_installed);
        void* openat64_symbol = dlsym(RTLD_DEFAULT, "openat64");
        bool openat64_ok = true;
        if (openat64_symbol != nullptr && openat64_symbol != openat_target) {
            openat64_ok = install_openat_hook("openat64", hooked_openat64,
                                              &openat64_target, &openat64_backup,
                                              &openat64_hook_installed);
        }

        bool open_ok = true;
        bool open64_ok = true;
        bool open2_ok = true;
        bool access_ok = true;
        bool readlink_ok = true;
        bool readlinkat_ok = true;
        bool realpath_ok = true;
        bool stat_ok = true;
        bool lstat_ok = true;
        bool stat64_ok = true;
        bool lstat64_ok = true;
        bool statfs_ok = true;
        bool statx_ok = true;
        bool fopen_ok = true;
        bool dl_iterate_phdr_ok = true;
        if (!minimal_file_hook_mode) {
            open_ok = install_open_hook("open", hooked_open,
                                        &open_target, &open_backup,
                                        &open_hook_installed);
            void* open64_symbol = dlsym(RTLD_DEFAULT, "open64");
            if (open64_symbol != nullptr && open64_symbol != open_target) {
                open64_ok = install_open_hook("open64", hooked_open64,
                                              &open64_target, &open64_backup,
                                              &open64_hook_installed);
            }
            access_ok = install_plain_hook("access", reinterpret_cast<void*>(hooked_access),
                                           &access_target, &access_backup, &access_hook_installed);
            readlink_ok = install_plain_hook("readlink", reinterpret_cast<void*>(hooked_readlink),
                                             &readlink_target, &readlink_backup, &readlink_hook_installed);
            readlinkat_ok = install_plain_hook("readlinkat", reinterpret_cast<void*>(hooked_readlinkat),
                                               &readlinkat_target, &readlinkat_backup, &readlinkat_hook_installed);
            realpath_ok = install_plain_hook("realpath", reinterpret_cast<void*>(hooked_realpath),
                                             &realpath_target, &realpath_backup, &realpath_hook_installed);
            stat_ok = install_plain_hook("stat", reinterpret_cast<void*>(hooked_stat),
                                         &stat_target, &stat_backup, &stat_hook_installed);
            lstat_ok = install_plain_hook("lstat", reinterpret_cast<void*>(hooked_lstat),
                                          &lstat_target, &lstat_backup, &lstat_hook_installed);
            stat64_ok = install_plain_hook("stat64", reinterpret_cast<void*>(hooked_stat64),
                                           &stat64_target, &stat64_backup, &stat64_hook_installed);
            lstat64_ok = install_plain_hook("lstat64", reinterpret_cast<void*>(hooked_lstat64),
                                            &lstat64_target, &lstat64_backup, &lstat64_hook_installed);
            statfs_ok = install_plain_hook("statfs", reinterpret_cast<void*>(hooked_statfs),
                                           &statfs_target, &statfs_backup, &statfs_hook_installed);
            statx_ok = install_plain_hook("statx", reinterpret_cast<void*>(hooked_statx),
                                          &statx_target, &statx_backup, &statx_hook_installed);
            fopen_ok = install_fopen_hook();
            if (g_lib_hide_enabled) {
                dl_iterate_phdr_ok = install_plain_hook("dl_iterate_phdr",
                                                        reinterpret_cast<void*>(hooked_dl_iterate_phdr),
                                                        &dl_iterate_phdr_target,
                                                        &dl_iterate_phdr_backup,
                                                        &dl_iterate_phdr_hook_installed);
            }
        } else {
            // Keep 360-like protectors on the old openat-only APK redirect path,
            // but still provide a narrow maps view for fd/inode consistency checks.
            open_ok = install_open_hook("open", hooked_open,
                                        &open_target, &open_backup,
                                        &open_hook_installed);
            void* open64_symbol = dlsym(RTLD_DEFAULT, "open64");
            if (open64_symbol != nullptr && open64_symbol != open_target) {
                open64_ok = install_open_hook("open64", hooked_open64,
                                              &open64_target, &open64_backup,
                                              &open64_hook_installed);
            }
            open2_ok = install_open2_hook();
            fopen_ok = install_fopen_hook();

            if (g_lib_hide_enabled) {
                dl_iterate_phdr_ok = install_plain_hook("dl_iterate_phdr",
                                                        reinterpret_cast<void*>(hooked_dl_iterate_phdr),
                                                        &dl_iterate_phdr_target,
                                                        &dl_iterate_phdr_backup,
                                                        &dl_iterate_phdr_hook_installed);
            }
        }

        if (!openat_ok && !openat64_ok && !open_ok && !open64_ok && !open2_ok
            && !access_ok && !readlink_ok && !readlinkat_ok && !realpath_ok
            && !stat_ok && !lstat_ok && !stat64_ok && !lstat64_ok
            && !statfs_ok && !statx_ok && !fopen_ok
            && !dl_iterate_phdr_ok) {
            LOGW("SigBypass: No native file hooks were installed.");
        }
    }

    static void set_module_native_library_roots_impl(JNIEnv* env, jobjectArray jRoots) {
        std::scoped_lock lock(g_path_mutex);
        moduleNativeLibraryRoots.clear();
        if (jRoots == nullptr) return;
        const jsize count = env->GetArrayLength(jRoots);
        for (jsize i = 0; i < count; ++i) {
            auto root = static_cast<jstring>(env->GetObjectArrayElement(jRoots, i));
            if (root == nullptr) continue;
            lsplant::JUTFString root_string(env, root);
            std::string value(root_string.get());
            env->DeleteLocalRef(root);
            if (!value.empty()
                && std::find(moduleNativeLibraryRoots.begin(), moduleNativeLibraryRoots.end(), value)
                       == moduleNativeLibraryRoots.end()) {
                moduleNativeLibraryRoots.push_back(std::move(value));
            }
        }
        LOGI("SigBypass: registered {} module native library roots", moduleNativeLibraryRoots.size());
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, enableOpenatHook,
                          jstring jOrigApkPath,
                          jstring jCacheApkPath,
                          jstring jPkgName,
                          jboolean jHide) {
        enable_openat_hook_impl(env, jOrigApkPath, jCacheApkPath, jPkgName, false, jHide);
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, enableOpenatHookMinimal,
                          jstring jOrigApkPath,
                          jstring jCacheApkPath,
                          jstring jPkgName,
                          jboolean jHide) {
        enable_openat_hook_impl(env, jOrigApkPath, jCacheApkPath, jPkgName, true, jHide);
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, setModuleNativeLibraryRoots, jobjectArray jRoots) {
        set_module_native_library_roots_impl(env, jRoots);
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, disableOpenatHook) {
        LOGI("Disable OpenAt Hook requested");
        std::scoped_lock lock(g_path_mutex);
        targetApkPath.clear();
        redirectApkPath.clear();
    }

    // 註冊 JNI 方法
    static JNINativeMethod gMethods[] = {
            LSP_NATIVE_METHOD(SigBypass, enableOpenatHook, "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V"),
            LSP_NATIVE_METHOD(SigBypass, enableOpenatHookMinimal, "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V"),
            LSP_NATIVE_METHOD(SigBypass, setModuleNativeLibraryRoots, "([Ljava/lang/String;)V"),
            LSP_NATIVE_METHOD(SigBypass, disableOpenatHook, "()V")
    };

    void RegisterBypass(JNIEnv *env) { REGISTER_LSP_NATIVE_METHODS(SigBypass); }

}  // namespace lspd
