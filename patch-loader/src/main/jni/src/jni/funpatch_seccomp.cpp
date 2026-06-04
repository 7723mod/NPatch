#include "funpatch_seccomp.h"

#include "common/logging.h"
#include "core/native_api.h"
#include "native_util.h"
#include "proc_fd_path.h"
#include "utils/jni_helper.hpp"

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <linux/audit.h>
#include <limits.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <mutex>
#include <signal.h>
#include <string>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

namespace lspd {

#if defined(__aarch64__)

    static thread_local bool g_filter_enabled = false;
    static bool g_sigsys_handler_ready = false;
    static char g_target_path[PATH_MAX] = {0};
    static char g_redirect_path[PATH_MAX] = {0};
    static bool g_redirected_fds[4096] = {false};
    static std::mutex g_path_mutex;
    static constexpr uint32_t kSyscallReplayToken = 0xABCDEF00u;

    static void copy_path(char* dest, const char* src) {
        if (src == nullptr) {
            dest[0] = '\0';
            return;
        }
        strncpy(dest, src, PATH_MAX - 1);
        dest[PATH_MAX - 1] = '\0';
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

    static bool path_matches_target_fd(int fd) {
        if (fd < 0 || g_target_path[0] == '\0') {
            return false;
        }

        char fd_path[64];
        char real_path[PATH_MAX];
        int path_len = snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
        if (path_len <= 0 || path_len >= static_cast<int>(sizeof(fd_path))) {
            return false;
        }

        long result = syscall(__NR_readlinkat,
                              AT_FDCWD,
                              fd_path,
                              real_path,
                              sizeof(real_path) - 1,
                              0,
                              kSyscallReplayToken);
        if (result < 0 || result >= static_cast<long>(sizeof(real_path))) {
            return false;
        }
        real_path[result] = '\0';
        return path_matches_target_locked(real_path);
    }

    static bool is_read_only_open(int flags) {
        return (flags & O_ACCMODE) == O_RDONLY
               && (flags & O_CREAT) == 0
               && (flags & O_TRUNC) == 0;
    }

    static bool parse_decimal_fd(const char* text, int* out_fd) {
        if (text == nullptr || out_fd == nullptr || *text == '\0') {
            return false;
        }

        int fd = 0;
        for (const char* p = text; *p != '\0'; ++p) {
            if (*p < '0' || *p > '9') {
                return false;
            }
            fd = fd * 10 + (*p - '0');
            if (fd >= static_cast<int>(std::size(g_redirected_fds))) {
                return false;
            }
        }

        *out_fd = fd;
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

    static bool emulate_redirected_readlinkat(int dirfd, const char* pathname, char* buffer, size_t buffer_size,
                                              ssize_t* out_result) {
        if (pathname == nullptr || buffer == nullptr || out_result == nullptr || buffer_size == 0) {
            return false;
        }

        ProcFdReadlinkatPath effective_path;
        prepare_proc_fd_readlinkat_path(&effective_path, dirfd, pathname,
                                        [](const char* link_path, char* resolved_path, size_t size) -> ssize_t {
                                            return static_cast<ssize_t>(syscall(__NR_readlinkat,
                                                                               AT_FDCWD,
                                                                               link_path,
                                                                               resolved_path,
                                                                               size,
                                                                               0,
                                                                               kSyscallReplayToken));
                                        });

        int fd = -1;
        if (!parse_proc_fd_path(effective_path.path(), &fd)
                || fd < 0
                || fd >= static_cast<int>(std::size(g_redirected_fds))
                || !g_redirected_fds[fd]
                || g_target_path[0] == '\0') {
            return false;
        }

        size_t len = strlen(g_target_path);
        if (len > buffer_size) {
            len = buffer_size;
        }
        memcpy(buffer, g_target_path, len);
        *out_result = static_cast<ssize_t>(len);
        return true;
    }

    static void sigsys_handler(int signo, siginfo_t*, void* context) {
        if (signo != SIGSYS) return;

        auto* ctx = reinterpret_cast<ucontext_t*>(context);
        if (ctx->uc_mcontext.regs[8] == __NR_openat) {
            auto* pathname = reinterpret_cast<const char*>(ctx->uc_mcontext.regs[1]);
            bool may_redirect = is_read_only_open(static_cast<int>(ctx->uc_mcontext.regs[2]));
            long result = syscall(__NR_openat,
                                  ctx->uc_mcontext.regs[0],
                                  pathname,
                                  ctx->uc_mcontext.regs[2],
                                  ctx->uc_mcontext.regs[3],
                                  ctx->uc_mcontext.regs[4],
                                  kSyscallReplayToken);
            if (may_redirect
                    && result >= 0
                    && g_redirect_path[0] != '\0'
                    && path_matches_target_fd(static_cast<int>(result))) {
                syscall(__NR_close, result);
                result = syscall(__NR_openat,
                                 ctx->uc_mcontext.regs[0],
                                 g_redirect_path,
                                 ctx->uc_mcontext.regs[2],
                                 ctx->uc_mcontext.regs[3],
                                 ctx->uc_mcontext.regs[4],
                                 kSyscallReplayToken);
                if (result >= 0 && result < static_cast<long>(std::size(g_redirected_fds))) {
                    g_redirected_fds[result] = true;
                }
            }
            ctx->uc_mcontext.regs[0] = result;
            return;
        }

        if (ctx->uc_mcontext.regs[8] == __NR_readlinkat) {
            auto* pathname = reinterpret_cast<const char*>(ctx->uc_mcontext.regs[1]);
            auto* buffer = reinterpret_cast<char*>(ctx->uc_mcontext.regs[2]);
            ssize_t emulated_result = -1;
            if (emulate_redirected_readlinkat(ctx->uc_mcontext.regs[0], pathname, buffer, ctx->uc_mcontext.regs[3],
                                              &emulated_result)) {
                ctx->uc_mcontext.regs[0] = emulated_result;
                return;
            }

            ctx->uc_mcontext.regs[0] = syscall(__NR_readlinkat,
                                               ctx->uc_mcontext.regs[0],
                                               pathname,
                                               buffer,
                                               ctx->uc_mcontext.regs[3],
                                               ctx->uc_mcontext.regs[4],
                                               kSyscallReplayToken);
        }
    }

    static bool ensure_sigsys_handler() {
        if (g_sigsys_handler_ready) {
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

        g_sigsys_handler_ready = true;
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
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_openat, 2, 0),
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_readlinkat, 1, 0),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW),
                BPF_STMT(BPF_LD + BPF_W + BPF_ABS,
                         offsetof(struct seccomp_data, args[5])),
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, kSyscallReplayToken, 1, 0),
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
            memset(g_redirected_fds, 0, sizeof(g_redirected_fds));
            (void) pkg;
        }

        LOGI("FunPatch: redirect target set: %s -> %s", g_target_path, g_redirect_path);

        if (!ensure_sigsys_handler()) {
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
