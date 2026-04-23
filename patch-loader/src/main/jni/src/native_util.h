#pragma once

#include <android/api-level.h>
#include <jni.h>

#include <string>
#include <string_view>

#include "core/config_bridge.h"
#include "core/context.h"
#include "core/native_api.h"
#include "elf/elf_image.h"
#include "elf/symbol_cache.h"
#include "utils/hook_helper.hpp"
#include "utils/jni_helper.hpp"

namespace lspd {

using Context = vector::native::Context;
using ConfigBridge = vector::native::ConfigBridge;

[[gnu::always_inline]] inline bool RegisterNativeMethodsInternal(
    JNIEnv *env,
    std::string_view class_name,
    const JNINativeMethod *methods,
    jint method_count) {
    auto clazz = Context::GetInstance()->FindClassFromCurrentLoader(env, class_name.data());
    if (clazz.get() == nullptr) {
        LOGF("Couldn't find class: {}", class_name.data());
        return false;
    }
    return JNI_RegisterNatives(env, clazz, methods, method_count);
}

inline int HookInline(void *func, void *replace, void **backup) {
    return vector::native::HookInline(func, replace, backup);
}

inline int UnhookInline(void *func) {
    return vector::native::UnhookInline(func);
}

inline const vector::native::ElfImage *GetArt(bool release = false) {
    const auto *image = vector::native::ElfSymbolCache::GetArt();
    if (release && image != nullptr) {
        vector::native::ElfSymbolCache::ClearCache(image);
    }
    return image;
}

inline int GetAndroidApiLevel() {
    return android_get_device_api_level();
}

inline std::string GetNativeBridgeSignature() {
    const auto &obfs_map = ConfigBridge::GetInstance()->obfuscation_map();
    static auto signature = obfs_map.at("org.lsposed.lspd.nativebridge.");
    return signature;
}

}  // namespace lspd

#if defined(__cplusplus)
#define _NATIVEHELPER_JNI_MACRO_CAST(to) reinterpret_cast<to>
#else
#define _NATIVEHELPER_JNI_MACRO_CAST(to) (to)
#endif

#ifndef LSP_NATIVE_METHOD
#define LSP_NATIVE_METHOD(className, functionName, signature)                               \
    {#functionName, signature,                                                              \
     _NATIVEHELPER_JNI_MACRO_CAST(void *)(                                                  \
         Java_org_lsposed_lspd_nativebridge_##className##_##functionName)}
#endif

#define JNI_START [[maybe_unused]] JNIEnv *env, [[maybe_unused]] jclass clazz

#ifndef LSP_DEF_NATIVE_METHOD
#define LSP_DEF_NATIVE_METHOD(ret, className, functionName, ...)                            \
    extern "C" ret Java_org_lsposed_lspd_nativebridge_##className##_##functionName(       \
        JNI_START, ##__VA_ARGS__)
#endif

#ifndef REGISTER_LSP_NATIVE_METHODS
#define REGISTER_LSP_NATIVE_METHODS(class_name)                                              \
    lspd::RegisterNativeMethodsInternal(                                                     \
        env, lspd::GetNativeBridgeSignature() + #class_name, gMethods,                      \
        static_cast<jint>(sizeof(gMethods) / sizeof(gMethods[0])))
#endif
