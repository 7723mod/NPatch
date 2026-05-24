#include "funpatch_seccomp.h"

#include "common/logging.h"
#include "core/native_api.h"
#include "native_util.h"
#include "utils/jni_helper.hpp"

#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstring>
#include <fcntl.h>
#include <limits.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/seccomp.h>
#include <mutex>
#include <pthread.h>
#include <signal.h>
#include <string>
#include <sys/prctl.h>
#include <sys/syscall.h>
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
    static bool g_filter_enabled = false;
    static char g_target_path[PATH_MAX] = {0};
    static char g_redirect_path[PATH_MAX] = {0};
    static std::mutex g_path_mutex;
    static thread_local std::string g_redirect_buffer;

    static void copy_path(char* dest, const char* src) {
        if (src == nullptr) {
            dest[0] = '\0';
            return;
        }
        strncpy(dest, src, PATH_MAX - 1);
        dest[PATH_MAX - 1] = '\0';
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

    static const char* resolve_redirect_path(const char* pathname) {
        if (pathname == nullptr) {
            return nullptr;
        }

        std::scoped_lock lock(g_path_mutex);
        if (g_target_path[0] == '\0'
                || g_redirect_path[0] == '\0'
                || pathname[0] != g_target_path[0]
                || strcmp(pathname, g_target_path) != 0) {
            return pathname;
        }

        g_redirect_buffer = g_redirect_path;
        return g_redirect_buffer.c_str();
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

            if (is_redirected_syscall(req->sys_no)) {
                const char* pathname = reinterpret_cast<const char*>(req->args[1]);
                const char* redirected_path = resolve_redirect_path(pathname);
                if (redirected_path != pathname && redirected_path != nullptr) {
                    LOGD("FunPatch: redirect %s('%s') -> '%s'",
                         syscall_name(req->sys_no), pathname, redirected_path);
                    req->args[1] = reinterpret_cast<long>(redirected_path);
                }
            }

            req->result = syscall(req->sys_no, req->args[0], req->args[1], req->args[2],
                                  req->args[3], req->args[4], req->args[5]);
            if (req->result == -1) {
                req->result = -errno;
            }

            req->state.store(1, std::memory_order_release);
            futex_wake(&req->state);
        }
        return nullptr;
    }

    static void sigsys_handler(int signo, siginfo_t*, void* context) {
        if (signo != SIGSYS) return;

        auto* ctx = reinterpret_cast<ucontext_t*>(context);
        SeccompRequest req;
        req.sys_no = ctx->uc_mcontext.regs[8];
        for (int i = 0; i < 6; ++i) {
            req.args[i] = ctx->uc_mcontext.regs[i];
        }
        req.state.store(0, std::memory_order_relaxed);

        SeccompRequest* req_ptr = &req;
        ssize_t written = write(g_req_pipe[1], &req_ptr, sizeof(req_ptr));
        if (written != sizeof(req_ptr)) {
            ctx->uc_mcontext.regs[0] = -EAGAIN;
            return;
        }

        while (req.state.load(std::memory_order_acquire) == 0) {
            futex_wait(&req.state, 0);
        }

        ctx->uc_mcontext.regs[0] = req.result;
    }

    static bool ensure_trusted_thread() {
        if (g_trusted_thread_ready) {
            return true;
        }

        if (pipe2(g_req_pipe, O_CLOEXEC) != 0) {
            LOGE("FunPatch: failed to create seccomp request pipe");
            return false;
        }

        int flags = fcntl(g_req_pipe[1], F_GETFL, 0);
        if (flags >= 0) {
            fcntl(g_req_pipe[1], F_SETFL, flags | O_NONBLOCK);
        }

        if (pthread_create(&g_trusted_thread, nullptr, trusted_thread_loop, nullptr) != 0) {
            LOGE("FunPatch: failed to create trusted seccomp thread");
            close(g_req_pipe[0]);
            close(g_req_pipe[1]);
            g_req_pipe[0] = -1;
            g_req_pipe[1] = -1;
            return false;
        }

        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_sigaction = sigsys_handler;
        sa.sa_flags = SA_SIGINFO | SA_NODEFER;
        if (sigaction(SIGSYS, &sa, nullptr) < 0) {
            LOGE("FunPatch: failed to register SIGSYS handler");
            close(g_req_pipe[0]);
            close(g_req_pipe[1]);
            g_req_pipe[0] = -1;
            g_req_pipe[1] = -1;
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
                BPF_STMT(BPF_LD + BPF_W + BPF_ABS, offsetof(struct seccomp_data, nr)),
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_openat, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#ifdef __NR_readlinkat
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_readlinkat, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#endif
#ifdef __NR_faccessat
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_faccessat, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#endif
#ifdef __NR_faccessat2
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_faccessat2, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#endif
#ifdef __NR_statx
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_statx, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#endif
#ifdef __NR_newfstatat
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_newfstatat, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#endif
#ifdef __NR_openat2
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_openat2, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#endif
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
