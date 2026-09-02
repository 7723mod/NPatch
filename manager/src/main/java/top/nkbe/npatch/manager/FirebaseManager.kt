package top.nkbe.npatch.manager

import android.os.Build
import android.os.Process
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.lspApp

/**
 * Manages Firebase Crashlytics and Analytics initialization for NPatch.
 *
 * Design principles (mirroring NigramX):
 * 1. Only enabled in Release builds with the official package name.
 * 2. Respects the user-facing "Enable Crash Reporting" toggle in Configs.
 * 3. Filters out crashes originating from Xposed/LSPosed hook frames — since
 *    NPatch itself is a patching tool running on top of these frameworks, those
 *    stack traces are almost certainly third-party-induced noise, not NPatch bugs.
 * 4. Attaches key device/version metadata to every crash report so issues can
 *    be reproduced and triaged efficiently.
 */
object FirebaseManager {

    private const val TAG = "NPatch-Firebase"

    /**
     * Signatures in class/method names that indicate a crash originated inside
     * an Xposed/LSPosed hook rather than genuine NPatch code.
     */
    private val HOOK_STACK_SIGNATURES = arrayOf(
        ".xposed",
        ".lsposed",
        "libxposed",
        "xposedhelpers",
        "xposedbridge",
        "edxposedbridge",
        "xc_methodhook",
        "lsphooker_",
        "callbeforehookedmethod",
        "callafterhookedmethod",
        "invokeoriginalmethod",
    )

    /** Captured before Crashlytics wraps the default handler. */
    private var systemUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Call once in [top.nkbe.npatch.LSPApplication.onCreate], after [Configs]
     * has been loaded from SharedPreferences.
     */
    fun init() {
        systemUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        val enabled = shouldEnable()
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(enabled)
        runCatching {
            FirebaseAnalytics.getInstance(lspApp)
                .setAnalyticsCollectionEnabled(enabled)
        }
        if (enabled) {
            attachDeviceMetadata(crashlytics)
            installCrashFilter(crashlytics)
        }
    }

    /**
     * Call whenever the user toggles the crash-reporting preference so the
     * change takes effect immediately without restarting the app.
     */
    fun setCollectionEnabled(enabled: Boolean) {
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
        runCatching {
            FirebaseAnalytics.getInstance(lspApp)
                .setAnalyticsCollectionEnabled(enabled)
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Crashlytics is only enabled when:
     *  - This is a Release build (not Debug).
     *  - The app is running under the official package name (i.e. not a fork).
     *  - The user has not explicitly disabled crash reporting.
     */
    private fun shouldEnable(): Boolean =
        !BuildConfig.DEBUG
                && "top.nkbe.npatch" == BuildConfig.APPLICATION_ID
                && Configs.enableCrashReporting

    /**
     * Returns true if the given [error] should be reported to Crashlytics.
     *
     * We skip:
     *  - [OutOfMemoryError] (device resource exhaustion, not our bug).
     *  - Crashes whose stack traces pass through Xposed/LSPosed hook frames.
     */
    fun shouldReport(error: Throwable): Boolean {
        // Skip OOM anywhere in the cause chain.
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < 32) {
            if (current is OutOfMemoryError) return false
            current = current.cause
        }
        // Skip hook-induced crashes.
        current = error
        depth = 0
        while (current != null && depth++ < 32) {
            if (containsHookSignature(current.javaClass.name)) return false
            for (element in current.stackTrace) {
                if (containsHookSignature(element.className)
                    || containsHookSignature(element.methodName)
                ) return false
            }
            current = current.cause
        }
        return true
    }

    /**
     * Wraps the default [Thread.UncaughtExceptionHandler] so that:
     *  - Hook-induced crashes are routed to the *system* handler (not Crashlytics).
     *  - Genuine NPatch crashes are forwarded to the Crashlytics handler.
     */
    private fun installCrashFilter(crashlytics: FirebaseCrashlytics) {
        val crashlyticsHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (shouldReport(error)) {
                // Genuine crash — let Crashlytics handle it.
                crashlyticsHandler?.uncaughtException(thread, error) ?: run {
                    Process.killProcess(Process.myPid())
                    System.exit(10)
                }
            } else {
                // Hook-induced or OOM — bypass Crashlytics, use system handler.
                systemUncaughtExceptionHandler?.uncaughtException(thread, error) ?: run {
                    Process.killProcess(Process.myPid())
                    System.exit(10)
                }
            }
        }
    }

    /**
     * Attaches stable, reproducible device/version metadata as custom keys so
     * every crash report carries enough context to triage the issue.
     */
    private fun attachDeviceMetadata(crashlytics: FirebaseCrashlytics) {
        crashlytics.apply {
            setCustomKey("npatch_version_name", BuildConfig.VERSION_NAME)
            setCustomKey("npatch_version_code", BuildConfig.VERSION_CODE)
            setCustomKey("android_sdk", Build.VERSION.SDK_INT)
            setCustomKey("device_manufacturer", Build.MANUFACTURER)
            setCustomKey("device_model", Build.MODEL)
            setCustomKey("build_type", BuildConfig.BUILD_TYPE)
        }
    }

    private fun containsHookSignature(value: String): Boolean {
        val lower = value.lowercase()
        return HOOK_STACK_SIGNATURES.any { sig -> lower.contains(sig) }
    }
}
