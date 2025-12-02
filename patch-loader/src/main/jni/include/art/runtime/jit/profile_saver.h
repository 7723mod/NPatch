//
// Created by loves on 6/19/2021.
//

#ifndef NPATCH_PROFILE_SAVER_H
#define NPATCH_PROFILE_SAVER_H

#include "utils/hook_helper.hpp"

using namespace lsplant;

namespace art {
class ProfileSaver {
private:
    inline static auto ProcessProfilingInfo_ =
        "_ZN3art12ProfileSaver20ProcessProfilingInfoEbPt"_sym.hook->*
        []<MemBackup auto backup>(ProfileSaver *thiz, bool a, uint16_t *b) static -> bool {
        LOGD("skipped profile saving");
        return true;
    };

    inline static auto ProcessProfilingInfoWithBool_ =
        "_ZN3art12ProfileSaver20ProcessProfilingInfoEbbPt"_sym.hook->*
        []<MemBackup auto backup>(ProfileSaver *thiz, bool, bool, uint16_t *) static -> bool {
        LOGD("skipped profile saving");
        return true;
    };

    inline static auto execve_ =
        "execve"_sym.hook->*[]<Backup auto backup>(const char *pathname, const char *argv[],
                                                   char *const envp[]) static -> int {
        if (strstr(pathname, "dex2oat")) {
            size_t count = 0;
            while (argv[count++] != nullptr);
            std::unique_ptr<const char *[]> new_args = std::make_unique<const char *[]>(count + 1);
            for (size_t i = 0; i < count - 1; ++i) new_args[i] = argv[i];
            new_args[count - 1] = "--inline-max-code-units=0";
            new_args[count] = nullptr;

            LOGD("dex2oat by disable inline!");
            int ret = backup(pathname, new_args.get(), envp);
            return ret;
        }
        int ret = backup(pathname, argv, envp);
        return ret;
    };

public:
    static void DisableInline(const HookHandler &handler) {
        handler(ProcessProfilingInfo_);
        handler(ProcessProfilingInfoWithBool_);
        handler(execve_);
    }
};
}  // namespace art

#endif  // NPATCH_PROFILE_SAVER_H
