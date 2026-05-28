#include "funpatch_seccomp.h"

#include "common/logging.h"
#include "core/native_api.h"
#include "native_util.h"
#include "utils/jni_helper.hpp"

#include <atomic>
#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstddef>
#include <cstring>
#include <fcntl.h>
#include <linux/audit.h>
#include <limits.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/memfd.h>
#include <linux/seccomp.h>
#include <mutex>
#include <pthread.h>
#include <signal.h>
#include <string>
#include <cstdio>
#include <cstdlib>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <ucontext.h>
#include <unistd.h>

namespace lspd {

#if defined(__aarch64__)

    struct SeccompRequest {
        long sys_no;
        long args[6];
        long result;
        std::atomic<int> state;
    };

    static pthread_t g_trusted_thread;
    static int g_req_pipe[2] = {-1, -1};
    static bool g_trusted_thread_ready = false;
    static thread_local bool g_filter_enabled = false;
    static char g_target_path[PATH_MAX] = {0};
    static char g_redirect_path[PATH_MAX] = {0};
    static char g_package_name[256] = {0};
    static std::mutex g_path_mutex;
    static thread_local std::string g_redirect_buffer;

    struct LibSnapshot {
        const char* soname;
        char path[PATH_MAX];
    };

    struct MapEntry {
        uintptr_t start = 0;
        uintptr_t end = 0;
        unsigned long offset = 0;
        char path[PATH_MAX] = {0};
        char perms[5] = {0};
    };

    static LibSnapshot g_lib_snapshots[] = {
            {"libart.so", ""},
            {"libc.so", ""},
    };

    static const char* const kSensitiveWords[] = {
            "frida",
            "rwxp",
            "zygisk",
            "lsposed",
            "edxposed",
            "xposed",
            "riru",
            "/data/local/tmp",
            "/data/adb/",
            nullptr,
    };

    static void ensure_lib_snapshots();

    static void copy_path(char* dest, const char* src) {
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

        char proc_pid_path[64];
        snprintf(proc_pid_path, sizeof(proc_pid_path), "/proc/%d/%s", getpid(), name);
        char task_self_path[64];
        snprintf(task_self_path, sizeof(task_self_path), "/proc/thread-self/%s", name);
        char self_path[64];
        snprintf(self_path, sizeof(self_path), "/proc/self/%s", name);
        return strcmp(pathname, self_path) == 0
               || strcmp(pathname, proc_pid_path) == 0
               || strcmp(pathname, task_self_path) == 0;
    }

    static bool is_task_status_path(const char* pathname) {
        if (pathname == nullptr) {
            return false;
        }
        char proc_pid_task_prefix[64];
        snprintf(proc_pid_task_prefix, sizeof(proc_pid_task_prefix), "/proc/%d/task/", getpid());
        const char* prefix = nullptr;
        if (strncmp(pathname, "/proc/self/task/", 16) == 0) {
            prefix = "/proc/self/task/";
        } else if (strncmp(pathname, proc_pid_task_prefix, strlen(proc_pid_task_prefix)) == 0) {
            prefix = proc_pid_task_prefix;
        } else {
            return false;
        }

        if (strncmp(pathname, prefix, strlen(prefix)) != 0) {
            return false;
        }
        const char* tail = pathname + strlen(prefix);
        while (*tail != '\0' && *tail != '/') {
            if (!std::isdigit(static_cast<unsigned char>(*tail))) {
                return false;
            }
            ++tail;
        }
        return strcmp(tail, "/status") == 0;
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

    static int create_memfd_from_string(const char* name, const std::string& content) {
        int fd = syscall(__NR_memfd_create, name, MFD_CLOEXEC);
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
            left -= written;
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

    static bool is_proc_fd_path(const char* pathname) {
        if (pathname == nullptr) {
            return false;
        }
        if (strncmp(pathname, "/proc/self/fd/", 14) == 0
                || strncmp(pathname, "/proc/thread-self/fd/", 21) == 0) {
            return true;
        }

        char proc_pid_fd_prefix[64];
        snprintf(proc_pid_fd_prefix, sizeof(proc_pid_fd_prefix), "/proc/%d/fd/", getpid());
        return strncmp(pathname, proc_pid_fd_prefix, strlen(proc_pid_fd_prefix)) == 0;
    }

    static bool path_matches_target_locked(const char* pathname) {
        if (pathname == nullptr || g_target_path[0] == '\0') {
            return false;
        }
        if (strcmp(pathname, g_target_path) == 0) {
            return true;
        }
        size_t target_len = strlen(g_target_path);
        return strncmp(pathname, g_target_path, target_len) == 0
               && strcmp(pathname + target_len, " (deleted)") == 0;
    }

    static bool fd_path_points_to_target_locked(const char* pathname) {
        if (!is_proc_fd_path(pathname)) {
            return false;
        }

        char link_target[PATH_MAX];
        ssize_t len = readlink(pathname, link_target, sizeof(link_target) - 1);
        if (len <= 0) {
            return false;
        }
        link_target[len] = '\0';
        return path_matches_target_locked(link_target);
    }

    static bool parse_maps_entry(const char* line, MapEntry* entry) {
        if (line == nullptr || entry == nullptr) {
            return false;
        }
        unsigned long start = 0;
        unsigned long end = 0;
        unsigned long offset = 0;
        char perms[5] = {0};
        char path[PATH_MAX] = {0};
        int fields = sscanf(line, "%lx-%lx %4s %lx %*s %*s %4095s",
                            &start, &end, perms, &offset, path);
        if (fields < 4) {
            return false;
        }
        entry->start = static_cast<uintptr_t>(start);
        entry->end = static_cast<uintptr_t>(end);
        entry->offset = offset;
        strncpy(entry->perms, perms, sizeof(entry->perms) - 1);
        entry->perms[sizeof(entry->perms) - 1] = '\0';
        if (fields >= 5) {
            copy_path(entry->path, path);
        } else {
            entry->path[0] = '\0';
        }
        return true;
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

    static std::string sanitize_status_content(const std::string& content) {
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

            if (line.rfind("Name:", 0) == 0 && contains_sensitive_word(line.c_str())) {
                line = "Name:\t" + std::string(g_package_name[0] != '\0' ? g_package_name : "android");
            }
            sanitized += line;
            if (has_newline) {
                sanitized += '\n';
            }
        }
        return sanitized;
    }

    static int open_sanitized_proc_file(const char* pathname) {
        if (!is_maps_path(pathname)
                && !is_smaps_path(pathname)
                && !is_task_status_path(pathname)
                && !is_mem_path(pathname)) {
            return -1;
        }

        if (is_mem_path(pathname)) {
            return create_memfd_from_string("npatch_mem_view", "");
        }

        int fd = open(pathname, O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            return -1;
        }
        std::string content = read_fd_to_string(fd);
        close(fd);

        std::string sanitized = is_task_status_path(pathname)
                                ? sanitize_status_content(content)
                                : sanitize_maps_like_content(content);
        return create_memfd_from_string("npatch_proc_view", sanitized);
    }

    static bool create_lib_snapshot_from_maps(const char* soname, char* out_path) {
        if (soname == nullptr || out_path == nullptr) {
            return false;
        }
        int maps_fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
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

            MapEntry entry;
            if (!parse_maps_entry(line.c_str(), &entry)
                    || entry.path[0] == '\0'
                    || strstr(entry.path, soname) == nullptr) {
                continue;
            }
            copy_path(source_path, entry.path);
            break;
        }

        if (source_path[0] == '\0') {
            return false;
        }

        int source_fd = open(source_path, O_RDONLY | O_CLOEXEC);
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

        int snapshot_fd = syscall(__NR_memfd_create, soname, MFD_CLOEXEC);
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
            left -= written;
        }
        munmap(file_data, st.st_size);
        snprintf(out_path, PATH_MAX, "/proc/self/fd/%d", snapshot_fd);
        return true;
    }

    static void ensure_lib_snapshots() {
        for (auto& snapshot : g_lib_snapshots) {
            if (snapshot.path[0] == '\0') {
                create_lib_snapshot_from_maps(snapshot.soname, snapshot.path);
            }
        }
    }

    static inline void futex_wait(std::atomic<int>* uaddr, int val) {
        syscall(__NR_futex, uaddr, FUTEX_WAIT_PRIVATE, val, nullptr, nullptr, 0);
    }

    static inline void futex_wake(std::atomic<int>* uaddr) {
        syscall(__NR_futex, uaddr, FUTEX_WAKE_PRIVATE, 1, nullptr, nullptr, 0);
    }

    static bool is_redirected_syscall(long sys_no) {
        switch (sys_no) {
            case __NR_openat:
                return true;
#ifdef __NR_readlinkat
            case __NR_readlinkat:
                return true;
#endif
#ifdef __NR_faccessat
            case __NR_faccessat:
                return true;
#endif
#ifdef __NR_faccessat2
            case __NR_faccessat2:
                return true;
#endif
#ifdef __NR_statx
            case __NR_statx:
                return true;
#endif
#ifdef __NR_newfstatat
            case __NR_newfstatat:
                return true;
#endif
#ifdef __NR_openat2
            case __NR_openat2:
                return true;
#endif
            default:
                return false;
        }
    }

    static const char* syscall_name(long sys_no) {
        switch (sys_no) {
            case __NR_openat:
                return "openat";
#ifdef __NR_readlinkat
            case __NR_readlinkat:
                return "readlinkat";
#endif
#ifdef __NR_faccessat
            case __NR_faccessat:
                return "faccessat";
#endif
#ifdef __NR_faccessat2
            case __NR_faccessat2:
                return "faccessat2";
#endif
#ifdef __NR_statx
            case __NR_statx:
                return "statx";
#endif
#ifdef __NR_newfstatat
            case __NR_newfstatat:
                return "newfstatat";
#endif
#ifdef __NR_openat2
            case __NR_openat2:
                return "openat2";
#endif
            default:
                return "unknown";
        }
    }

    static bool extract_openat2_path(const SeccompRequest* req, char* out_path) {
#ifdef __NR_openat2
        if (req == nullptr || out_path == nullptr || req->sys_no != __NR_openat2) {
            return false;
        }
        const char* pathname = reinterpret_cast<const char*>(req->args[1]);
        copy_path(out_path, pathname);
        return true;
#else
        (void) req;
        (void) out_path;
        return false;
#endif
    }

    static bool get_syscall_path(const SeccompRequest* req, char* out_path) {
        if (req == nullptr || out_path == nullptr) {
            return false;
        }
        switch (req->sys_no) {
            case __NR_openat:
#ifdef __NR_readlinkat
            case __NR_readlinkat:
#endif
#ifdef __NR_faccessat
            case __NR_faccessat:
#endif
#ifdef __NR_faccessat2
            case __NR_faccessat2:
#endif
#ifdef __NR_statx
            case __NR_statx:
#endif
#ifdef __NR_newfstatat
            case __NR_newfstatat:
#endif
                copy_path(out_path, reinterpret_cast<const char*>(req->args[1]));
                return true;
            default:
                return extract_openat2_path(req, out_path);
        }
    }

    static const char* resolve_redirect_path(const char* pathname) {
        if (pathname == nullptr) {
            return nullptr;
        }

        std::scoped_lock lock(g_path_mutex);
        if (g_target_path[0] == '\0'
                || g_redirect_path[0] == '\0') {
            return pathname;
        }
        if (pathname[0] != g_target_path[0] || !path_matches_target_locked(pathname)) {
            if (!fd_path_points_to_target_locked(pathname)) {
                return pathname;
            }
        }

        g_redirect_buffer = g_redirect_path;
        return g_redirect_buffer.c_str();
    }

    static bool try_handle_openat_proc_view(SeccompRequest* req) {
        if (req == nullptr || req->sys_no != __NR_openat) {
            return false;
        }
        char pathname[PATH_MAX];
        if (!get_syscall_path(req, pathname)) {
            return false;
        }
        int fd = open_sanitized_proc_file(pathname);
        if (fd < 0) {
            return false;
        }
        LOGD("FunPatch: serve sanitized proc view for %s", pathname);
        req->result = fd;
        return true;
    }

    static bool try_handle_readlink_redirect(SeccompRequest* req) {
#ifdef __NR_readlinkat
        if (req == nullptr || req->sys_no != __NR_readlinkat) {
            return false;
        }

        const char* pathname = reinterpret_cast<const char*>(req->args[1]);
        char redirect_path[PATH_MAX];
        {
            std::scoped_lock lock(g_path_mutex);
            if (g_redirect_path[0] == '\0' || !fd_path_points_to_target_locked(pathname)) {
                return false;
            }
            copy_path(redirect_path, g_redirect_path);
        }

        auto* buffer = reinterpret_cast<char*>(req->args[2]);
        auto buffer_size = static_cast<size_t>(req->args[3]);
        if (buffer == nullptr) {
            req->result = -EFAULT;
            return true;
        }
        if (buffer_size == 0) {
            req->result = 0;
            return true;
        }

        size_t redirect_len = strlen(redirect_path);
        size_t bytes_to_copy = redirect_len < buffer_size ? redirect_len : buffer_size;
        memcpy(buffer, redirect_path, bytes_to_copy);
        req->result = static_cast<long>(bytes_to_copy);
        return true;
#else
        (void) req;
        return false;
#endif
    }

    static void* trusted_thread_loop(void*) {
        LOGD("FunPatch: trusted seccomp thread started (tid=%d)", gettid());
        while (true) {
            SeccompRequest* req = nullptr;
            ssize_t bytes_read = read(g_req_pipe[0], &req, sizeof(req));
            if (bytes_read == -1 && errno == EINTR) {
                continue;
            }
            if (bytes_read != sizeof(req) || req == nullptr) {
                continue;
            }

            bool handled = try_handle_openat_proc_view(req);
            if (!handled) {
                handled = try_handle_readlink_redirect(req);
            }
            if (handled) {
                LOGD("FunPatch: syscall handled by sanitized view");
            } else if (is_redirected_syscall(req->sys_no)) {
                const char* pathname = reinterpret_cast<const char*>(req->args[1]);
                const char* redirected_path = resolve_redirect_path(pathname);
                if (redirected_path != pathname && redirected_path != nullptr) {
                    LOGD("FunPatch: redirect %s('%s') -> '%s'",
                         syscall_name(req->sys_no), pathname, redirected_path);
                    req->args[1] = reinterpret_cast<long>(redirected_path);
                }
            }
            if (!handled) {
                req->result = syscall(req->sys_no, req->args[0], req->args[1], req->args[2],
                                      req->args[3], req->args[4], req->args[5]);
                if (req->result == -1) {
                    req->result = -errno;
                }
            }

            req->state.store(1, std::memory_order_release);
            futex_wake(&req->state);
        }
        return nullptr;
    }

    static void sigsys_handler(int signo, siginfo_t*, void* context) {
        if (signo != SIGSYS) return;

        auto* ctx = reinterpret_cast<ucontext_t*>(context);
        if (ctx->uc_mcontext.regs[8] != __NR_openat) {
            return;
        }

        auto* pathname = reinterpret_cast<const char*>(ctx->uc_mcontext.regs[1]);
        const char* redirected_path = pathname;
        if (pathname != nullptr
                && g_target_path[0] != '\0'
                && g_redirect_path[0] != '\0'
                && path_matches_target_locked(pathname)) {
            redirected_path = g_redirect_path;
        }

        // Match FPA seccomp-v2: replay the trapped openat directly from SIGSYS
        // and put the libc-style return value back into x0.
        ctx->uc_mcontext.regs[0] = syscall(__NR_openat,
                                           ctx->uc_mcontext.regs[0],
                                           redirected_path,
                                           ctx->uc_mcontext.regs[2],
                                           ctx->uc_mcontext.regs[3],
                                           ctx->uc_mcontext.regs[4],
                                           ctx->uc_mcontext.regs[5]);
    }

    static bool ensure_trusted_thread() {
        if (g_trusted_thread_ready) {
            return true;
        }

        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_sigaction = sigsys_handler;
        sa.sa_flags = SA_SIGINFO;
        if (sigaction(SIGSYS, &sa, nullptr) < 0) {
            LOGE("FunPatch: failed to register SIGSYS handler");
            return false;
        }

        g_trusted_thread_ready = true;
        return true;
    }

    static bool install_seccomp_filter() {
        if (g_filter_enabled) {
            return true;
        }

        struct sock_filter filter[] = {
                BPF_STMT(BPF_LD + BPF_W + BPF_ABS, offsetof(struct seccomp_data, arch)),
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_KILL_PROCESS),
                BPF_STMT(BPF_LD + BPF_W + BPF_ABS, offsetof(struct seccomp_data, nr)),
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_openat, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW),
        };

        struct sock_fprog prog = {
                .len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0])),
                .filter = filter,
        };

        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
            LOGE("FunPatch: prctl(NO_NEW_PRIVS) failed");
            return false;
        }

        if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) {
            LOGE("FunPatch: prctl(SECCOMP) failed");
            return false;
        }

        g_filter_enabled = true;
        LOGI("FunPatch: seccomp v2 filter applied");
        return true;
    }

#endif

    LSP_DEF_NATIVE_METHOD(jboolean, FunPatch, enableSeccompV2Redirect,
                          jstring current_path, jstring original_path,
                          [[maybe_unused]] jstring pkg) {
#if defined(__aarch64__)
        if (current_path == nullptr || original_path == nullptr) {
            LOGW("FunPatch: redirect paths cannot be null");
            return JNI_FALSE;
        }

        lsplant::JUTFString current(env, current_path);
        lsplant::JUTFString original(env, original_path);

        {
            std::scoped_lock lock(g_path_mutex);
            copy_path(g_target_path, current.get());
            copy_path(g_redirect_path, original.get());
            if (pkg != nullptr) {
                lsplant::JUTFString package_name(env, pkg);
                copy_path(g_package_name, package_name.get());
            }
        }

        LOGI("FunPatch: redirect target set: %s -> %s", g_target_path, g_redirect_path);

        if (!ensure_trusted_thread()) {
            return JNI_FALSE;
        }
        return install_seccomp_filter() ? JNI_TRUE : JNI_FALSE;
#else
        LOGI("FunPatch: seccomp v2 skipped on non-arm64 architecture");
        return JNI_FALSE;
#endif
    }

    static JNINativeMethod gMethods[] = {
            LSP_NATIVE_METHOD(FunPatch, enableSeccompV2Redirect,
                              "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z"),
    };

    void RegisterFunPatchSeccomp(JNIEnv *env) {
        REGISTER_LSP_NATIVE_METHODS(FunPatch);
    }
} // namespace lspd
