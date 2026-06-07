#pragma once
#include <jni.h>

namespace lspd {

    void RegisterBypass(JNIEnv* env);
    void PrepareLibHideSnapshots();
    void RefreshLibHideSnapshots();

} // namespace lspd
