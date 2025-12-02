/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2021 - 2022 LSPosed Contributors
 */

#ifndef NPATCH_OAT_FILE_MANAGER_H
#define NPATCH_OAT_FILE_MANAGER_H

#include <vector>

#include "context.h"
#include "utils/hook_helper.hpp"

using namespace lsplant;

namespace art {
class FileManager {
public:
    inline static auto RunBackgroundVerificationWithContext_ =
        ("_ZN3art14OatFileManager25RunBackgroundVerificationERKNSt3__"_sym |
         "16vectorIPKNS_7DexFileENS1_9allocatorIS5_EEEEP8_jobjectPKc"_sym)
            .hook
            ->*[]<MemBackup auto backup>(
                   FileManager *thiz, const std::vector<const void *> &dex_files,
                   jobject class_loader, const char *class_loader_context) static -> void {
        if (lspd::Context::GetInstance()->GetCurrentClassLoader() == nullptr) {
            LOGD("Disabled background verification");
            return;
        }
        backup(thiz, dex_files, class_loader, class_loader_context);
    };

    inline static auto RunBackgroundVerification_ =
        ("_ZN3art14OatFileManager25RunBackgroundVerificationERKNSt3__"_sym |
         "16vectorIPKNS_7DexFileENS1_9allocatorIS5_EEEEP8_jobject"_sym)
            .hook
            ->*
        []<MemBackup auto backup>(FileManager *thiz, const std::vector<const void *> &dex_files,
                                  jobject class_loader) static -> void {
        if (lspd::Context::GetInstance()->GetCurrentClassLoader() == nullptr) {
            LOGD("Disabled background verification");
            return;
        }
        backup(thiz, dex_files, class_loader);
    };

public:
    static void DisableBackgroundVerification(const lsplant::HookHandler &handler) {
        const int api_level = lspd::GetAndroidApiLevel();
        if (api_level >= __ANDROID_API_Q__) {
            handler(RunBackgroundVerificationWithContext_);
            handler(RunBackgroundVerification_);
        }
    }
};
}  // namespace art

#endif  // NPATCH_OAT_FILE_MANAGER_H
