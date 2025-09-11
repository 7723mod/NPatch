//
// Created by VIP on 2021/4/25.
// Update by HSSkyBoy on 2025/9/11
//

#include "bypass_sig.h"

#include "../src/native_api.h"
#include "elf_util.h"
#include "logging.h"
#include "native_util.h"
#include "patch_loader.h"
#include "utils/hook_helper.hpp"
#include "utils/jni_helper.hpp"

using lsplant::operator""_sym;

namespace lspd {

    std::string apkPath;
    std::string redirectPath;

    inline static constexpr const char* kLibCName = "libc.so";

// 修改回傳型別以匹配 kImg 的實際型別
    std::unique_ptr<SandHook::ElfImg> &GetC(bool release = false) {
        static auto kImg = std::make_unique<SandHook::ElfImg>(kLibCName);
        if (release) {
            kImg.reset();
            kImg = nullptr;
        }
        return kImg;
    }

    inline static auto __openat_ =
            "__openat"_sym.hook->*[]<lsplant::Backup auto backup>(int fd, const char *pathname, int flag,
                                                                  int mode) static -> int {
                if (pathname && strcmp(pathname, apkPath.c_str()) == 0) {
                    return backup(fd, redirectPath.c_str(), flag, mode);
                }
                return backup(fd, pathname, flag, mode);
            };

    static bool HookOpenat(const lsplant::HookHandler &handler) { return handler(__openat_); }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, enableOpenatHook, jstring origApkPath,
                          jstring cacheApkPath) {
        if (origApkPath == nullptr || cacheApkPath == nullptr) {
            LOGE("Invalid arguments: original or cache path is null.");
            return;
        }

        lsplant::JUTFString str1(env, origApkPath);
        lsplant::JUTFString str2(env, cacheApkPath);

        apkPath = str1.get();
        redirectPath = str2.get();

        auto r = HookOpenat(lsplant::InitInfo{
                .inline_hooker =
                [](auto t, auto r) {
                    void *bk = nullptr;
                    return HookInline(t, r, &bk) == 0 ? bk : nullptr;
                },
                .art_symbol_resolver = [](auto symbol) {
                    return GetC()->getSymbAddress(symbol);
                },
        });
        if (!r) {
            LOGE("Hook __openat fail");
        }
        // 无论 Hook 成功与否，都确保清除 libc.so 的 ElfImg
        GetC(true);
    }

    static JNINativeMethod gMethods[] = {
            LSP_NATIVE_METHOD(SigBypass, enableOpenatHook, "(Ljava/lang/String;Ljava/lang/String;)V")};

    void RegisterBypass(JNIEnv *env) { REGISTER_LSP_NATIVE_METHODS(SigBypass); }

}  // namespace lspd
