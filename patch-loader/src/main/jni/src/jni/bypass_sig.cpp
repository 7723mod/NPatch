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
#include <fcntl.h>
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

    static std::string targetApkPath;
    static std::string redirectApkPath;
    static std::string currentPackageName;
    static void *openat_target = nullptr;
    static void *openat64_target = nullptr;
    static OpenAtFn openat_backup = nullptr;
    static OpenAtFn openat64_backup = nullptr;
    static bool openat_hook_installed = false;
    static bool openat64_hook_installed = false;
    static std::mutex g_path_mutex;
    static thread_local bool g_openat_reentry = false;
    static thread_local std::string g_redirect_buffer;

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
                                  va_list ap) {
        const bool has_mode = needs_mode(flags);
        const mode_t mode = has_mode ? va_arg(ap, mode_t) : 0;
        const char* redirected_path = pathname;

        if (!g_openat_reentry) {
            // 某些 ROM 可能讓底層再次回到 openat，這裡先擋遞迴重入。
            g_openat_reentry = true;
            redirected_path = resolve_redirect_path(pathname);
            if (redirected_path != pathname && redirected_path != nullptr) {
                LOGD("SigBypass: Redirecting %s('%s') -> '%s'",
                     symbol_name, pathname, redirected_path);
            }
            g_openat_reentry = false;
        }

        return call_openat(backup, dirfd, redirected_path, flags, mode, has_mode);
    }

    static int hooked_openat(int dirfd, const char* pathname, int flags, ...) {
        va_list ap;
        va_start(ap, flags);
        const int result = hooked_openat_impl(openat_backup, "openat", dirfd, pathname, flags, ap);
        va_end(ap);
        return result;
    }

    static int hooked_openat64(int dirfd, const char* pathname, int flags, ...) {
        va_list ap;
        va_start(ap, flags);
        const int result = hooked_openat_impl(openat64_backup, "openat64", dirfd, pathname, flags, ap);
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

    LSP_DEF_NATIVE_METHOD(void, SigBypass, enableOpenatHook,
                          jstring jOrigApkPath,
                          jstring jCacheApkPath,
                          jstring jPkgName) {

        if (jOrigApkPath == nullptr || jCacheApkPath == nullptr) {
            LOGE("Invalid arguments: paths cannot be null.");
            return;
        }

        lsplant::JUTFString strOrig(env, jOrigApkPath);
        lsplant::JUTFString strRedirect(env, jCacheApkPath);

        {
            std::scoped_lock lock(g_path_mutex);
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

        if (!openat_ok && !openat64_ok) {
            LOGW("SigBypass: No native openat hooks were installed.");
        }
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
            LSP_NATIVE_METHOD(SigBypass, disableOpenatHook, "()V")
    };

    void RegisterBypass(JNIEnv *env) { REGISTER_LSP_NATIVE_METHODS(SigBypass); }

}  // namespace lspd
