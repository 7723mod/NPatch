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
#include <unistd.h>
#include <string>
#include <cstring>
#include <memory>

using lsplant::operator""_sym;

namespace lspd {

    static std::string targetApkPath;
    static std::string redirectApkPath;
    static std::string currentPackageName;
    static void *openat_backup = nullptr;

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

        targetApkPath = strOrig.get();
        redirectApkPath = strRedirect.get();

        if (jPkgName != nullptr) {
            lsplant::JUTFString strPkg(env, jPkgName);
            currentPackageName = strPkg.get();
        }

        LOGI("Enable OpenAt Hook: %s -> %s (Pkg: %s)",
             targetApkPath.c_str(), redirectApkPath.c_str(), currentPackageName.c_str());

        // Compile-safe stub: the redirect paths are stored and can be consumed by
        // higher-level logic, but the low-level openat hook is intentionally disabled here.
        LOGW("SvcBypass: openat hook stubbed for build compatibility.");
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, disableOpenatHook) {
        LOGI("Disable OpenAt Hook requested");
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
