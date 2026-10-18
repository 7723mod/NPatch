// Raw-`svc` APK-read redirect: the EXTREME-tier answer to a packer that reads its own installed
// base.apk through a hand-written inline `svc #0` instead of calling libc. Every other sigbypass
// layer (Java IO hooks, the libc openat inline hook, the xhook GOT rewrite) only sees a call to a
// libc *symbol*; an inline syscall traps straight into the kernel with nothing to intercept. This
// instruments the `svc` instruction itself, via Dobby, and rewrites the pathname register before
// the kernel runs the syscall -- exactly as the openat hook does for the libc path.
//
// Ported from JingMatrix/LSPatch's signature-bypass "Level 3"
// The two safeguards that make it safe are carried over:
//   * SCOPE: only libraries mapped under the app's own install dir are scanned. libc/libart/the
//     linker/app_process -- and our own libnpatch/libdobby -- are never touched. Instrumenting
//     libc's futex/read/... `svc` would route every syscall in the process through the handler and
//     crash a thread parked in futex; the earlier over-broad scan did exactly that.
//   * NEAR-BRANCH: dobby_enable_near_branch_trampoline keeps every patch 4 bytes wide, so writing
//     over a tiny `svc; ret` wrapper cannot overrun into -- and corrupt -- the next function.
//
// Within scope every `svc #0` is instrumented and svcHandler discriminates at runtime on x8, so a
// data word that merely equals the `svc` encoding is harmless unless executed. opt-in, arm64 only.
#include "svc_bypass.h"

#include "common/logging.h"
#include "native_util.h"
#include "utils/jni_helper.hpp"

#include "dobby.h"
#include "sig_bypass_paths.h"

#include <cstdint>
#include <cstring>
#include <link.h>
#include <mutex>
#include <set>
#include <string>
#include <string_view>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace lspd {

#if defined(__aarch64__)

    namespace {

        // Set once (under g_svc_mutex) before any instrumentation and never reassigned, so the
        // c_str()/size() svcHandler reads on arbitrary threads stay valid for the process lifetime.
        std::string g_target_path;     // the installed base.apk whose reads we catch
        std::string g_redirect_path;   // the original signed apk to serve instead
        std::string g_app_dir_prefix;  // install dir of g_target_path -- the scan scope

        std::mutex g_svc_mutex;              // guards g_instrumented and one-time dlopen hooking
        std::set<uintptr_t> g_instrumented;  // svc sites already handed to Dobby, to dedup rescans

        constexpr uint32_t kSvc0 = 0xd4000001u;  // `svc #0` on arm64

        // The NDK sysroot may predate openat2; its arm64 number is fixed at 437 (asm-generic).
#ifndef __NR_openat2
#define __NR_openat2 437
#endif

        bool isPathSyscall(long nr) {
            switch (nr) {
                case __NR_openat:
                case __NR_openat2:
                    return true;
                default:
                    return false;
            }
        }

        // Exact match, or the same path plus a trailing " (deleted)" (a once-unlinked apk) -- the
        // same comparison bypass_sig.cpp/seccompv.cpp use (see sig_bypass_paths.h). A bounded
        // compare only: the handler must issue no syscall, or it would re-enter through its own
        // instrumentation.
        bool path_matches_target(const char* path) {
            if (g_target_path.empty()) return false;
            return PathMatchesTarget(path, g_target_path.c_str(), g_target_path.size());
        }

        // Runs just before an instrumented `svc` executes. Every in-scope svc is instrumented, so
        // this is the sole discriminator: unless it is openat/openat2 opening our apk, do nothing.
        // No logging here: this fires on every openat/openat2 in scope, not just redirected ones in
        // practice (any app code that opens files at all keeps re-entering this check), so a log
        // line per call would mean real I/O (liblog) on a hot path for no diagnostic benefit beyond
        // the one-time "armed" log in enable_svc_redirect_impl.
        void svcHandler(void* /*address*/, DobbyRegisterContext* ctx) {
            long nr = static_cast<long>(ctx->general.regs.x8);
            if (!isPathSyscall(nr)) return;
            // arm64 has only the *at syscalls; all carry the pathname in x1 (dirfd in x0).
            auto* path = reinterpret_cast<const char*>(ctx->general.regs.x1);
            if (!path_matches_target(path)) return;
            ctx->general.regs.x1 = reinterpret_cast<uint64_t>(g_redirect_path.c_str());
        }

        // Some ROMs (e.g. Honor MagicOS 9) map .text execute-only (XOM: `--x` with no read
        // permission). Reading such a page to scan for `svc #0` faults with SEGV_ACCERR and, since
        // the scan does a plain load, kills the process (see issue #156). Widen the covering pages to
        // PROT_READ | PROT_EXEC first; these are our own process's app-library mappings, so the
        // mprotect is permitted. Execution is preserved, and on the common r-x case this is a no-op.
        // Returns false when the range could not be made readable, so the caller skips reading it.
        bool ensure_range_readable(uintptr_t base, size_t len) {
            static const uintptr_t page_size =
                    static_cast<uintptr_t>(sysconf(_SC_PAGESIZE) > 0 ? sysconf(_SC_PAGESIZE) : 4096);
            const uintptr_t page_mask = page_size - 1;
            const uintptr_t start = base & ~page_mask;
            const uintptr_t end = (base + len + page_mask) & ~page_mask;
            if (mprotect(reinterpret_cast<void*>(start), end - start, PROT_READ | PROT_EXEC) != 0) {
                LOGW("SvcBypass: cannot make [{:#x},{:#x}) readable, skipping scan", start, end);
                return false;
            }
            return true;
        }

        // Instrument every `svc #0` in [base, base+len). Caller must hold g_svc_mutex.
        int instrument_range(uintptr_t base, size_t len) {
            if (len < sizeof(uint32_t) || !ensure_range_readable(base, len)) return 0;
            auto* words = reinterpret_cast<const uint32_t*>(base);
            size_t count = len / sizeof(uint32_t);
            int done = 0;
            for (size_t i = 0; i < count; ++i) {
                if (words[i] != kSvc0) continue;
                uintptr_t addr = base + i * sizeof(uint32_t);
                if (!g_instrumented.insert(addr).second) continue;  // already done in an earlier scan
                if (DobbyInstrument(reinterpret_cast<void*>(addr), &svcHandler) != 0) {
                    LOGW("SvcBypass: DobbyInstrument failed at {:#x}", addr);
                    g_instrumented.erase(addr);
                } else {
                    ++done;
                }
            }
            return done;
        }

        // dl_iterate_phdr visitor: scan the executable part of each PT_LOAD of an app-owned library.
        int phdr_callback(dl_phdr_info* info, size_t /*size*/, void* /*data*/) {
            const char* name = info->dlpi_name;
            if (name == nullptr || name[0] == '\0') return 0;  // app_process / anonymous mapping
            std::string_view n{name};

            // Unlike JM/LSPatch -- which runs the app from an origin-apk copy in cache and so also
            // scans that cache dir -- NPatch runs from the normal installed base.apk and only
            // redirects *reads* to origin.apk, so the app's own code is always under the install
            // dir. A library outside it is a system lib (libc, ART, the linker) and left alone.
            if (g_app_dir_prefix.empty() ||
                n.compare(0, g_app_dir_prefix.size(), g_app_dir_prefix) != 0) {
                return 0;
            }
            // Our own injected libs sit under the install dir too; scanning them would instrument
            // the very syscalls the handler and Dobby themselves run on.
            if (n.find("libnpatch") != std::string_view::npos ||
                n.find("libdobby") != std::string_view::npos) {
                return 0;
            }
            // Documented gap, same as JM: a packer .so decrypted to and dlopen'd from the DATA dir
            // (/data/data/<pkg>/...), or anonymous/JIT svc code, is not reported by dl_iterate_phdr.

            int done = 0;
            for (int i = 0; i < info->dlpi_phnum; ++i) {
                const ElfW(Phdr)& ph = info->dlpi_phdr[i];
                if (ph.p_type != PT_LOAD || !(ph.p_flags & PF_X) || ph.p_filesz == 0) continue;
                done += instrument_range(static_cast<uintptr_t>(info->dlpi_addr + ph.p_vaddr),
                                         ph.p_filesz);
            }
            if (done > 0) LOGD("SvcBypass: instrumented {} site(s) in {}", done, name);
            return 0;
        }

        void scan_app_libs() {
            std::lock_guard<std::mutex> lock(g_svc_mutex);
            if (g_app_dir_prefix.empty()) return;
            dl_iterate_phdr(&phdr_callback, nullptr);
        }

        // The packer .so is decrypted and dlopen'd after we arm, so a one-shot scan misses it;
        // rescan after every load. We hook the linker's INTERNAL loader entries, not the public
        // libdl dlopen/android_dlopen_ext: the public wrappers capture their caller via
        // __builtin_return_address(0) to pick the caller's linker namespace, so hooking them makes
        // the load resolve in the wrong namespace and any namespace-scoped load fail. The __loader_*
        // forms take caller_addr explicitly, so forwarding it unchanged keeps namespaces intact.
        using LoaderDlopenExtFn = void* (*)(const char*, int, const void*, const void*);
        using LoaderDlopenFn = void* (*)(const char*, int, const void*);

        LoaderDlopenExtFn g_orig_loader_dlopen_ext = nullptr;
        LoaderDlopenFn g_orig_loader_dlopen = nullptr;

        void* hooked_loader_dlopen_ext(const char* filename, int flags, const void* extinfo,
                                       const void* caller_addr) {
            void* h = g_orig_loader_dlopen_ext(filename, flags, extinfo, caller_addr);
            if (h != nullptr) scan_app_libs();
            return h;
        }

        void* hooked_loader_dlopen(const char* filename, int flags, const void* caller_addr) {
            void* h = g_orig_loader_dlopen(filename, flags, caller_addr);
            if (h != nullptr) scan_app_libs();
            return h;
        }

        bool g_dlopen_hooks_installed = false;
        void install_dlopen_hooks_once() {
            if (g_dlopen_hooks_installed) return;
            g_dlopen_hooks_installed = true;

            // __loader_* are exported by the linker image, not libdl, so resolve them from linker64.
            // A miss only disables the rescan for that entry (some packer coverage lost); the load
            // itself is untouched.
            vector::native::ElfImage linker("linker64");
            if (auto* ext = linker.getSymbAddress<void*>("__loader_android_dlopen_ext")) {
                if (HookInline(ext, reinterpret_cast<void*>(hooked_loader_dlopen_ext),
                               reinterpret_cast<void**>(&g_orig_loader_dlopen_ext)) != 0) {
                    LOGW("SvcBypass: failed to hook __loader_android_dlopen_ext");
                }
            } else {
                LOGW("SvcBypass: __loader_android_dlopen_ext not resolved; rescan skipped for it");
            }
            if (auto* plain = linker.getSymbAddress<void*>("__loader_dlopen")) {
                if (HookInline(plain, reinterpret_cast<void*>(hooked_loader_dlopen),
                               reinterpret_cast<void**>(&g_orig_loader_dlopen)) != 0) {
                    LOGW("SvcBypass: failed to hook __loader_dlopen");
                }
            } else {
                LOGW("SvcBypass: __loader_dlopen not resolved; rescan skipped for it");
            }
        }

        bool enable_svc_redirect_impl(const char* target, const char* redirect) {
            if (target == nullptr || redirect == nullptr || target[0] == '\0' || redirect[0] == '\0') {
                LOGW("SvcBypass: redirect paths cannot be null/empty");
                return false;
            }
            {
                std::lock_guard<std::mutex> lock(g_svc_mutex);
                g_target_path = target;
                g_redirect_path = redirect;
                std::string_view t{g_target_path};
                auto slash = t.find_last_of('/');
                g_app_dir_prefix = slash == std::string_view::npos
                                       ? std::string{}
                                       : std::string{t.substr(0, slash)};
                LOGD("SvcBypass: scope {} ; {} -> {}", g_app_dir_prefix, g_target_path,
                     g_redirect_path);
            }
            if (g_app_dir_prefix.empty()) {
                LOGW("SvcBypass: could not derive install dir from target path; not arming");
                return false;
            }

            // Keep every origin patch 4 bytes so a `svc; ret` wrapper is not overrun. Enabled once,
            // before any DobbyInstrument/HookInline; degrades to the normal trampoline if no near
            // cave is free, so it cannot hard-fail our other Dobby hooks.
            static std::once_flag near_branch_once;
            std::call_once(near_branch_once, dobby_enable_near_branch_trampoline);

            install_dlopen_hooks_once();  // catch the packer lib loaded after this point
            scan_app_libs();              // and anything already resident
            LOGI("SvcBypass: armed, {} -> {}", g_target_path, g_redirect_path);
            return true;
        }

    } // namespace

#endif // __aarch64__

    LSP_DEF_NATIVE_METHOD(jboolean, SigBypass, enableSvcRedirect,
                          jstring jTargetPath, jstring jRedirectPath, jstring jPkgName) {
#if defined(__aarch64__)
        if (jTargetPath == nullptr || jRedirectPath == nullptr) {
            LOGW("SvcBypass: redirect paths cannot be null");
            return JNI_FALSE;
        }
        lsplant::JUTFString target(env, jTargetPath);
        lsplant::JUTFString redirect(env, jRedirectPath);
        (void) jPkgName;
        return enable_svc_redirect_impl(target.get(), redirect.get()) ? JNI_TRUE : JNI_FALSE;
#else
        (void) jTargetPath;
        (void) jRedirectPath;
        (void) jPkgName;
        LOGI("SvcBypass: raw-svc apk-read redirect is implemented on arm64 only");
        return JNI_FALSE;
#endif
    }

    static JNINativeMethod gMethods[] = {
            LSP_NATIVE_METHOD(SigBypass, enableSvcRedirect,
                              "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z"),
    };

    void RegisterSvcBypass(JNIEnv* env) {
        REGISTER_LSP_NATIVE_METHODS(SigBypass);
    }

} // namespace lspd
