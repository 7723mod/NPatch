#include "bypass_svc.h"
#include "common/logging.h"
#include "native_util.h"
#include "core/native_api.h"
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <signal.h>
#include <ucontext.h>
#include <pthread.h>
#include <cstddef>
#include <cstring>
#include <cstdlib>
#include <fcntl.h>
#include <atomic>
#include <linux/futex.h>
#include <linux/seccomp.h>
#include <linux/filter.h>
#include <linux/audit.h>

namespace lspd {

    // --- 共用結構與變數 ---
    static bool g_is_hook_active = false;

#if defined(__aarch64__)

    struct SyscallRequest {
        long sys_no;
        long args[6];
        long result;
        std::atomic<int> state; // 0: pending, 1: completed
    };

    static pthread_t g_trusted_thread;
    static int g_req_pipe[2]; // 使用 pipe 進行 Async-Signal-Safe 通訊

    // 儲存重定向的路徑
    static char g_orig_path[PATH_MAX] = {0};
    static char g_redirect_path[PATH_MAX] = {0};

    // 封裝 futex 系統呼叫 (在 Signal Handler 中是安全的)
    static inline void futex_wait(std::atomic<int>* uaddr, int val) {
        syscall(__NR_futex, uaddr, FUTEX_WAIT_PRIVATE, val, nullptr, nullptr, 0);
    }

    static inline void futex_wake(std::atomic<int>* uaddr) {
        syscall(__NR_futex, uaddr, FUTEX_WAKE_PRIVATE, 1, nullptr, nullptr, 0);
    }

    // --- 核心功能實作 (ARM64 Only) ---

    // 1. 影子執行緒 (Trusted Thread) 循環
    static void* trusted_thread_loop(void* arg) {
        LOGD("SvcBypass: Trusted thread started (TID: %d)", gettid());
        while (true) {
            SyscallRequest* req = nullptr;

            // 從 pipe 讀取請求指標 (阻塞等待)
            ssize_t bytes_read = read(g_req_pipe[0], &req, sizeof(req));
            if (bytes_read == sizeof(req) && req != nullptr) {

                // [優化] 真正的參數修改與重定向邏輯
                if (req->sys_no == __NR_openat && g_orig_path[0] != '\0') {
                    const char* pathname = reinterpret_cast<const char*>(req->args[1]);
                    // 如果路徑相符，替換為我們重定向的 APK/資源路徑
                    if (pathname != nullptr && strcmp(pathname, g_orig_path) == 0) {
                        LOGD("SvcBypass: Redirecting openat('%s') -> '%s'", pathname, g_redirect_path);
                        req->args[1] = reinterpret_cast<long>(g_redirect_path);
                    }
                }

                // 執行真正的 syscall
                req->result = syscall(req->sys_no,
                                      req->args[0], req->args[1], req->args[2],
                                      req->args[3], req->args[4], req->args[5]);

                // 更新狀態並喚醒被中斷的執行緒
                req->state.store(1, std::memory_order_release);
                futex_wake(&req->state);
            }
        }
        return nullptr;
    }

    // 2. SIGSYS 信號處理器 (必須保持 Async-Signal-Safe)
    static void sigsys_handler(int signo, siginfo_t* info, void* context) {
        if (signo != SIGSYS) return;

        ucontext_t* ctx = reinterpret_cast<ucontext_t*>(context);
        SyscallRequest req;

        // ARM64: 從 regs 讀取 (x8=sys_no, x0-x5=args)
        req.sys_no = ctx->uc_mcontext.regs[8];
        for (int i = 0; i < 6; ++i) {
            req.args[i] = ctx->uc_mcontext.regs[i];
        }
        req.state.store(0, std::memory_order_relaxed); // 設為等待狀態

        // 透過 Pipe 將請求的記憶體位置安全地傳送給 Trusted Thread
        SyscallRequest* req_ptr = &req;
        write(g_req_pipe[1], &req_ptr, sizeof(req_ptr));

        // 使用 Futex 安全地等待 Trusted Thread 處理完成
        while (req.state.load(std::memory_order_acquire) == 0) {
            futex_wait(&req.state, 0);
        }

        // 將結果寫回 x0
        ctx->uc_mcontext.regs[0] = req.result;
    }

#endif // 結束 __aarch64__ 專用區塊


    // -------------------------------------------------------------------------
    // JNI 接口層 (處理架構差異)
    // -------------------------------------------------------------------------

    LSP_DEF_NATIVE_METHOD(jboolean, SvcBypass, initSvcHook) {
        if (g_is_hook_active) return JNI_TRUE;

#if defined(__aarch64__)
        // 建立通訊用的 Pipe (設定 O_CLOEXEC 防止被子行程繼承)
        if (pipe2(g_req_pipe, O_CLOEXEC) != 0) {
            LOGE("SvcBypass: Failed to create request pipe");
            return JNI_FALSE;
        }

        int ret = pthread_create(&g_trusted_thread, nullptr, trusted_thread_loop, nullptr);
        if (ret != 0) {
            LOGE("SvcBypass: Failed to create trusted thread");
            return JNI_FALSE;
        }

        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_sigaction = sigsys_handler;
        sa.sa_flags = SA_SIGINFO | SA_NODEFER;
        if (sigaction(SIGSYS, &sa, nullptr) < 0) {
            LOGE("SvcBypass: Failed to register SIGSYS handler");
            return JNI_FALSE;
        }

        g_is_hook_active = true;
        LOGI("SvcBypass: Initialized successfully (ARM64)");
        return JNI_TRUE;
#else
        // x86/x86_64: 僅標記為激活，但不做實際 Hook
        g_is_hook_active = true;
        LOGI("SvcBypass: Skipped on non-ARM64 architecture");
        return JNI_TRUE;
#endif
    }

    LSP_DEF_NATIVE_METHOD(void, SvcBypass, enableSvcRedirect,
                          jstring path, jstring orig, jstring pkg) {
        if (!g_is_hook_active) {
            LOGW("SvcBypass: Hook not initialized.");
            return;
        }

#if defined(__aarch64__)
        // 將 Java 傳進來的路徑儲存到 C 層字串中，供 Trusted Thread 使用
        if (orig != nullptr && path != nullptr) {
            const char* c_orig = env->GetStringUTFChars(orig, nullptr);
            const char* c_path = env->GetStringUTFChars(path, nullptr);

            strncpy(g_orig_path, c_orig, PATH_MAX - 1);
            strncpy(g_redirect_path, c_path, PATH_MAX - 1);

            env->ReleaseStringUTFChars(orig, c_orig);
            env->ReleaseStringUTFChars(path, c_path);
        }

        // Seccomp BPF Filter：攔截 openat 系統呼叫
        struct sock_filter filter[] = {
                BPF_STMT(BPF_LD + BPF_W + BPF_ABS, (offsetof(struct seccomp_data, nr))),
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_openat, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW),
        };

        struct sock_fprog prog = {
                .len = (unsigned short)(sizeof(filter) / sizeof(filter[0])),
                .filter = filter,
        };

        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) {
            LOGE("SvcBypass: prctl(NO_NEW_PRIVS) failed");
            return;
        }

        if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)) {
            LOGE("SvcBypass: prctl(SECCOMP) failed");
        } else {
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
        return env->NewStringUTF("SvcBypass: Active (ARM64)");
#else
        return env->NewStringUTF("SvcBypass: Stub (Non-ARM64)");
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
        if (snprintf(link, sizeof(link), "/proc/self/fd/%d", fd) >= (int)sizeof(link)) {
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
        if (fstat(fd, &st) == 0) return (jlong)st.st_ino;
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
            LSP_NATIVE_METHOD(SvcBypass, enableSvcRedirect, "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"),
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
