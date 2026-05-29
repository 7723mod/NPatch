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
#include <limits.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>
#include <cstdarg>
#include <string>
#include <cstring>
#include <memory>
#include <mutex>

using lsplant::operator""_sym;

namespace lspd {

    using OpenAtFn = int(*)(int, const char*, int, ...);
    using OpenFn = int(*)(const char*, int, ...);
    using FopenFn = FILE*(*)(const char*, const char*);

    static std::string targetApkPath;
    static std::string redirectApkPath;
    static std::string currentPackageName;
    static void *openat_target = nullptr;
    static void *openat64_target = nullptr;
    static void *open_target = nullptr;
    static void *open64_target = nullptr;
    static void *fopen_target = nullptr;
    static OpenAtFn openat_backup = nullptr;
    static OpenAtFn openat64_backup = nullptr;
    static OpenFn open_backup = nullptr;
    static OpenFn open64_backup = nullptr;
    static FopenFn fopen_backup = nullptr;
    static bool openat_hook_installed = false;
    static bool openat64_hook_installed = false;
    static bool open_hook_installed = false;
    static bool open64_hook_installed = false;
    static bool fopen_hook_installed = false;
    static bool minimal_file_hook_mode = false;
    static std::mutex g_path_mutex;
    static thread_local bool g_openat_reentry = false;
    static thread_local bool g_fopen_reentry = false;
    static thread_local std::string g_redirect_buffer;

    struct LibSnapshot {
        const char* soname;
        char path[PATH_MAX];
    };

    struct MapEntry {
        uintptr_t start = 0;
        uintptr_t end = 0;
        unsigned long offset = 0;
        char perms[5] = {0};
        char path[PATH_MAX] = {0};
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

        int snapshot_fd = static_cast<int>(syscall(__NR_memfd_create, soname, MFD_CLOEXEC));
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
        return true;
    }

    static void ensure_lib_snapshots() {
        for (auto& snapshot : g_lib_snapshots) {
            if (snapshot.path[0] == '\0') {
                create_lib_snapshot_from_maps(snapshot.soname, snapshot.path);
            }
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

        std::string caller_path = to_lower(info.dli_fname);
        return caller_path.find("/.jiagu/") != std::string::npos
               || caller_path.find("libjiagu") != std::string::npos
               || caller_path.find("jiagu") != std::string::npos
               || caller_path.find("qihoo") != std::string::npos
               || caller_path.find("qihu") != std::string::npos
               || caller_path.find("360") != std::string::npos;
    }

    static int open_sanitized_proc_file(const char* pathname, const void* caller_pc) {
        if (pathname == nullptr) {
            return -1;
        }
        if (minimal_file_hook_mode) {
            return -1;
        }
        if (is_jiagu_or_stub_caller(caller_pc)) {
            return -1;
        }
        if (is_mem_path(pathname)) {
            return create_memfd_from_string("npatch_mem_view", "");
        }
        if (!is_maps_path(pathname) && !is_smaps_path(pathname)) {
            return -1;
        }

        int fd = open(pathname, O_RDONLY | O_CLOEXEC);
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
        if (pathname == nullptr) {
            return nullptr;
        }

        std::scoped_lock lock(g_path_mutex);
        // 只有命中補丁 APK 時才導向原包，避免一般檔案 IO 也被帶進去簽流程。
        if (targetApkPath.empty() || redirectApkPath.empty()) {
            return pathname;
        }
        if (strcmp(pathname, targetApkPath.c_str()) != 0) {
            return pathname;
        }
        g_redirect_buffer = redirectApkPath;
        return g_redirect_buffer.c_str();
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
            g_openat_reentry = true;
            if (is_read_only_open(flags)) {
                int sanitized_fd = open_sanitized_proc_file(pathname, caller_pc);
                if (sanitized_fd >= 0) {
                    LOGD("SigBypass: Serve sanitized %s for %s", symbol_name, pathname);
                    g_openat_reentry = false;
                    return sanitized_fd;
                }
            }
            redirected_path = resolve_redirect_path(pathname);
            if (redirected_path != pathname && redirected_path != nullptr) {
                LOGD("SigBypass: Redirecting %s('%s') -> '%s'",
                     symbol_name, pathname, redirected_path);
            }
            g_openat_reentry = false;
        }

        return call_openat(backup, dirfd, redirected_path, flags, mode, has_mode);
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
                    LOGD("SigBypass: Serve sanitized %s for %s", symbol_name, pathname);
                    g_openat_reentry = false;
                    return sanitized_fd;
                }
            }
            redirected_path = resolve_redirect_path(pathname);
            if (redirected_path != pathname && redirected_path != nullptr) {
                LOGD("SigBypass: Redirecting %s('%s') -> '%s'",
                     symbol_name, pathname, redirected_path);
            }
            g_openat_reentry = false;
        }

        return call_open(backup, redirected_path, flags, mode, has_mode);
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
            redirected_path = resolve_redirect_path(pathname);
            if (redirected_path != pathname && redirected_path != nullptr) {
                LOGD("SigBypass: Redirecting fopen('%s') -> '%s'", pathname, redirected_path);
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

    static FILE* hooked_fopen(const char* pathname, const char* mode) {
        return hooked_fopen_impl(fopen_backup, pathname, mode, __builtin_return_address(0));
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
            LOGW("SigBypass: Symbol %s not found", symbol_name);
            return false;
        }

        if (HookInline(symbol, reinterpret_cast<void*>(replacement),
                       reinterpret_cast<void**>(backup_slot)) != 0) {
            LOGE("SigBypass: Failed to hook %s", symbol_name);
            return false;
        }

        *target_slot = symbol;
        *installed_slot = true;
        LOGI("SigBypass: Hooked %s", symbol_name);
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
            LOGW("SigBypass: Symbol %s not found", symbol_name);
            return false;
        }

        if (HookInline(symbol, reinterpret_cast<void*>(replacement),
                       reinterpret_cast<void**>(backup_slot)) != 0) {
            LOGE("SigBypass: Failed to hook %s", symbol_name);
            return false;
        }

        *target_slot = symbol;
        *installed_slot = true;
        LOGI("SigBypass: Hooked %s", symbol_name);
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

    static void enable_openat_hook_impl(JNIEnv* env,
                                        jstring jOrigApkPath,
                                        jstring jCacheApkPath,
                                        jstring jPkgName,
                                        bool minimal) {

        if (jOrigApkPath == nullptr || jCacheApkPath == nullptr) {
            LOGE("Invalid arguments: paths cannot be null.");
            return;
        }

        lsplant::JUTFString strOrig(env, jOrigApkPath);
        lsplant::JUTFString strRedirect(env, jCacheApkPath);

        {
            std::scoped_lock lock(g_path_mutex);
            minimal_file_hook_mode = minimal_file_hook_mode || minimal;
            targetApkPath = strOrig.get();
            redirectApkPath = strRedirect.get();

            if (jPkgName != nullptr) {
                lsplant::JUTFString strPkg(env, jPkgName);
                currentPackageName = strPkg.get();
            }
        }

        LOGI("Enable OpenAt Hook: %s -> %s (Pkg: %s)",
             targetApkPath.c_str(), redirectApkPath.c_str(), currentPackageName.c_str());

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
        bool fopen_ok = true;
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
            fopen_ok = install_fopen_hook();
        }

        if (!openat_ok && !openat64_ok && !open_ok && !open64_ok && !fopen_ok) {
            LOGW("SigBypass: No native file hooks were installed.");
        }
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, enableOpenatHook,
                          jstring jOrigApkPath,
                          jstring jCacheApkPath,
                          jstring jPkgName) {
        enable_openat_hook_impl(env, jOrigApkPath, jCacheApkPath, jPkgName, false);
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, enableOpenatHookMinimal,
                          jstring jOrigApkPath,
                          jstring jCacheApkPath,
                          jstring jPkgName) {
        enable_openat_hook_impl(env, jOrigApkPath, jCacheApkPath, jPkgName, true);
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, disableOpenatHook) {
        LOGI("Disable OpenAt Hook requested");
        std::scoped_lock lock(g_path_mutex);
        targetApkPath.clear();
        redirectApkPath.clear();
    }

    // 註冊 JNI 方法
    static JNINativeMethod gMethods[] = {
            LSP_NATIVE_METHOD(SigBypass, enableOpenatHook, "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"),
            LSP_NATIVE_METHOD(SigBypass, enableOpenatHookMinimal, "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"),
            LSP_NATIVE_METHOD(SigBypass, disableOpenatHook, "()V")
    };

    void RegisterBypass(JNIEnv *env) { REGISTER_LSP_NATIVE_METHODS(SigBypass); }

}  // namespace lspd
