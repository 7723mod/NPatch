#include "bypass_svc.h"

#include "common/logging.h"
#include "core/native_api.h"
#include "native_util.h"

#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

namespace lspd {

    static bool g_is_hook_active = false;

#if defined(__aarch64__)

    struct SyscallRequest {
        long sys_no;
        long args[6];
        long result;
        std::atomic<int> state;  // 0: pending, 1: completed
    };

    static pthread_t g_trusted_thread;
    static int g_req_pipe[2] = {-1, -1};
    static bool g_filter_enabled = false;
    // Match reads of the patched APK path and redirect them to the extracted original APK.
    static char g_target_path[PATH_MAX] = {0};
    static char g_redirect_path[PATH_MAX] = {0};

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

    static void* trusted_thread_loop(void*) {
        LOGD("SvcBypass: Trusted thread started (TID: %d)", gettid());
        while (true) {
            SyscallRequest* req = nullptr;
            ssize_t bytes_read = read(g_req_pipe[0], &req, sizeof(req));
            if (bytes_read == -1 && errno == EINTR) {
                continue;
            }
            if (bytes_read != sizeof(req) || req == nullptr) {
                continue;
            }

            if (req->sys_no == __NR_openat && g_target_path[0] != '\0') {
                const char* pathname = reinterpret_cast<const char*>(req->args[1]);
                // Keep the syscall path unchanged unless it exactly targets the patched APK.
                if (pathname != nullptr && strcmp(pathname, g_target_path) == 0) {
                    LOGD("SvcBypass: Redirecting openat('%s') -> '%s'", pathname,
                         g_redirect_path);
                    req->args[1] = reinterpret_cast<long>(g_redirect_path);
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
        SyscallRequest req;
        req.sys_no = ctx->uc_mcontext.regs[8];
        for (int i = 0; i < 6; ++i) {
            req.args[i] = ctx->uc_mcontext.regs[i];
        }
        req.state.store(0, std::memory_order_relaxed);

        SyscallRequest* req_ptr = &req;
        ssize_t written = write(g_req_pipe[1], &req_ptr, sizeof(req_ptr));
        if (written != sizeof(req_ptr)) {
            // Avoid blocking forever if the trusted thread cannot receive this trapped syscall.
            ctx->uc_mcontext.regs[0] = -EAGAIN;
            return;
        }

        while (req.state.load(std::memory_order_acquire) == 0) {
            futex_wait(&req.state, 0);
        }

        ctx->uc_mcontext.regs[0] = req.result;
    }

#endif

    LSP_DEF_NATIVE_METHOD(jboolean, SvcBypass, initSvcHook) {
        if (g_is_hook_active) return JNI_TRUE;

#if defined(__aarch64__)
        if (pipe2(g_req_pipe, O_CLOEXEC) != 0) {
            LOGE("SvcBypass: Failed to create request pipe");
            return JNI_FALSE;
        }

        int flags = fcntl(g_req_pipe[1], F_GETFL, 0);
        if (flags >= 0) {
            // The SIGSYS handler must never block inside write().
            fcntl(g_req_pipe[1], F_SETFL, flags | O_NONBLOCK);
        }

        int ret = pthread_create(&g_trusted_thread, nullptr, trusted_thread_loop, nullptr);
        if (ret != 0) {
            LOGE("SvcBypass: Failed to create trusted thread");
            close(g_req_pipe[0]);
            close(g_req_pipe[1]);
            g_req_pipe[0] = -1;
            g_req_pipe[1] = -1;
            return JNI_FALSE;
        }

        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_sigaction = sigsys_handler;
        sa.sa_flags = SA_SIGINFO | SA_NODEFER;
        if (sigaction(SIGSYS, &sa, nullptr) < 0) {
            LOGE("SvcBypass: Failed to register SIGSYS handler");
            close(g_req_pipe[0]);
            close(g_req_pipe[1]);
            g_req_pipe[0] = -1;
            g_req_pipe[1] = -1;
            return JNI_FALSE;
        }

        g_is_hook_active = true;
        LOGI("SvcBypass: Initialized successfully (ARM64)");
        return JNI_TRUE;
#else
        LOGI("SvcBypass: Skipped on non-ARM64 architecture");
        return JNI_FALSE;
#endif
    }

    LSP_DEF_NATIVE_METHOD(void, SvcBypass, enableSvcRedirect,
                          jstring current_path, jstring original_path,
                          [[maybe_unused]] jstring pkg) {
        if (!g_is_hook_active) {
            LOGW("SvcBypass: Hook not initialized.");
            return;
        }

#if defined(__aarch64__)
        if (current_path == nullptr || original_path == nullptr) {
            LOGW("SvcBypass: Redirect paths cannot be null.");
            return;
        }

        const char* c_current = env->GetStringUTFChars(current_path, nullptr);
        const char* c_original = env->GetStringUTFChars(original_path, nullptr);
        if (c_current == nullptr || c_original == nullptr) {
            if (c_current != nullptr) env->ReleaseStringUTFChars(current_path, c_current);
            if (c_original != nullptr) env->ReleaseStringUTFChars(original_path, c_original);
            LOGW("SvcBypass: Failed to read redirect paths.");
            return;
        }

        copy_path(g_target_path, c_current);
        copy_path(g_redirect_path, c_original);

        env->ReleaseStringUTFChars(current_path, c_current);
        env->ReleaseStringUTFChars(original_path, c_original);

        LOGI("SvcBypass: Redirect target set: %s -> %s", g_target_path, g_redirect_path);

        if (g_filter_enabled) {
            // Seccomp filters cannot be removed, so only update paths after the first install.
            LOGI("SvcBypass: Seccomp filter already applied");
            return;
        }

        struct sock_filter filter[] = {
                BPF_STMT(BPF_LD + BPF_W + BPF_ABS, offsetof(struct seccomp_data, nr)),
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_openat, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW),
        };

        struct sock_fprog prog = {
                .len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0])),
                .filter = filter,
        };

        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) {
            LOGE("SvcBypass: prctl(NO_NEW_PRIVS) failed");
            return;
        }

        if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)) {
            LOGE("SvcBypass: prctl(SECCOMP) failed");
        } else {
            g_filter_enabled = true;
            LOGI("SvcBypass: Seccomp filter applied (ARM64)");
        }
#endif
    }

    LSP_DEF_NATIVE_METHOD(void, SvcBypass, disableSvcRedirect) {
        LOGW("SvcBypass: Cannot disable Seccomp filters once applied.");
    }

    LSP_DEF_NATIVE_METHOD(jboolean, SvcBypass, isSvcHookActive) {
        return g_is_hook_active ? JNI_TRUE : JNI_FALSE;
    }

    LSP_DEF_NATIVE_METHOD(jstring, SvcBypass, getDebugInfo) {
#if defined(__aarch64__)
        return env->NewStringUTF(g_filter_enabled ? "SvcBypass: Active (ARM64)"
                                                  : "SvcBypass: Initialized (ARM64)");
#else
        return env->NewStringUTF("SvcBypass: Unsupported (Non-ARM64)");
#endif
    }

    LSP_DEF_NATIVE_METHOD(jint, SvcBypass, getCurrentPid) {
        return getpid();
    }

    LSP_DEF_NATIVE_METHOD(jint, SvcBypass, getInitialPid) {
        return getpid();
    }

    LSP_DEF_NATIVE_METHOD(void, SvcBypass, logSvcHookStats) {
    }

    LSP_DEF_NATIVE_METHOD(jboolean, SvcBypass, isChildProcess) {
        return JNI_FALSE;
    }

    LSP_DEF_NATIVE_METHOD(jstring, SvcBypass, checkFd, jint fd) {
        if (fd < 0) return nullptr;
        char path[PATH_MAX];
        char link[64];
        if (snprintf(link, sizeof(link), "/proc/self/fd/%d", fd) >= static_cast<int>(sizeof(link))) {
            return nullptr;
        }

        ssize_t len = readlink(link, path, sizeof(path) - 1);
        if (len != -1) {
            path[len] = '\0';
            return env->NewStringUTF(path);
        }
        return nullptr;
    }

    LSP_DEF_NATIVE_METHOD(jint, SvcBypass, dupFd, jint fd) {
        return dup(fd);
    }

    LSP_DEF_NATIVE_METHOD(jlong, SvcBypass, getFdInode, jint fd) {
        struct stat st;
        if (fstat(fd, &st) == 0) return static_cast<jlong>(st.st_ino);
        return -1;
    }

    LSP_DEF_NATIVE_METHOD(jboolean, SvcBypass, isSystemFile, jint fd) {
        return JNI_FALSE;
    }

    LSP_DEF_NATIVE_METHOD(jint, SvcBypass, findSystemApkFd, jstring path) {
        return -1;
    }

    LSP_DEF_NATIVE_METHOD(jobjectArray, SvcBypass, getSystemApkFds) {
        return nullptr;
    }

    LSP_DEF_NATIVE_METHOD(void, SvcBypass, refreshSystemFds) {
    }

    LSP_DEF_NATIVE_METHOD(jbyteArray, SvcBypass, readCertificateFromFd, jint fd) {
        return nullptr;
    }

    LSP_DEF_NATIVE_METHOD(jbyteArray, SvcBypass, readCertificateFromPath, jstring path) {
        return nullptr;
    }

    static JNINativeMethod gMethods[] = {
            LSP_NATIVE_METHOD(SvcBypass, initSvcHook, "()Z"),
            LSP_NATIVE_METHOD(SvcBypass, enableSvcRedirect,
                              "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"),
            LSP_NATIVE_METHOD(SvcBypass, disableSvcRedirect, "()V"),
            LSP_NATIVE_METHOD(SvcBypass, isSvcHookActive, "()Z"),
            LSP_NATIVE_METHOD(SvcBypass, logSvcHookStats, "()V"),
            LSP_NATIVE_METHOD(SvcBypass, getDebugInfo, "()Ljava/lang/String;"),
            LSP_NATIVE_METHOD(SvcBypass, getCurrentPid, "()I"),
            LSP_NATIVE_METHOD(SvcBypass, getInitialPid, "()I"),
            LSP_NATIVE_METHOD(SvcBypass, isChildProcess, "()Z"),
            LSP_NATIVE_METHOD(SvcBypass, checkFd, "(I)Ljava/lang/String;"),
            LSP_NATIVE_METHOD(SvcBypass, dupFd, "(I)I"),
            LSP_NATIVE_METHOD(SvcBypass, getFdInode, "(I)J"),
            LSP_NATIVE_METHOD(SvcBypass, isSystemFile, "(I)Z"),
            LSP_NATIVE_METHOD(SvcBypass, findSystemApkFd, "(Ljava/lang/String;)I"),
            LSP_NATIVE_METHOD(SvcBypass, getSystemApkFds, "()[[Ljava/lang/String;"),
            LSP_NATIVE_METHOD(SvcBypass, refreshSystemFds, "()V"),
            LSP_NATIVE_METHOD(SvcBypass, readCertificateFromFd, "(I)[B"),
            LSP_NATIVE_METHOD(SvcBypass, readCertificateFromPath, "(Ljava/lang/String;)[B"),
    };

    void RegisterSvcBypass(JNIEnv *env) {
        REGISTER_LSP_NATIVE_METHODS(SvcBypass);
    }
}
