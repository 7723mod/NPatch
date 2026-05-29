#include "funpatch_seccomp.h"

#include "common/logging.h"
#include "core/native_api.h"
#include "native_util.h"
#include "utils/jni_helper.hpp"

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <linux/audit.h>
#include <limits.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <mutex>
#include <signal.h>
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
    static std::mutex g_path_mutex;
    static constexpr uint32_t kOpenatReplayToken = 0xABCDEF00u;

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

        // Match FPA seccomp-v2: replay the trapped openat directly from SIGSYS.
        // The extra magic arg is ignored by openat but lets our BPF filter allow
        // the replay, avoiding recursive SIGSYS delivery.
        ctx->uc_mcontext.regs[0] = syscall(__NR_openat,
                                            ctx->uc_mcontext.regs[0],
                                            redirected_path,
                                            ctx->uc_mcontext.regs[2],
                                            ctx->uc_mcontext.regs[3],
                                            ctx->uc_mcontext.regs[4],
                                            kOpenatReplayToken);
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
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_openat, 0, 3),
                BPF_STMT(BPF_LD + BPF_W + BPF_ABS,
                         offsetof(struct seccomp_data, args[5])),
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, kOpenatReplayToken, 1, 0),
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
