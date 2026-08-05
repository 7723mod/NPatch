package top.nkbe.npatch.manager

import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import io.github.libxposed.service.HookedProcess
import io.github.libxposed.service.IHotReloadCallback
import io.github.libxposed.service.IXposedService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.matrix.vector.ipc.LoadedModule
import org.matrix.vector.ipc.IProcessChannel
import top.nkbe.npatch.config.ConfigManager

/**
 * Process registry shared by NPatch's provider and bound-service IPC paths.
 *
 * Target ids are opaque and never reused. A target is always checked against the requesting
 * LoadedModule package before its process binder can be reached.
 */
object HotReloadRegistry {
    private const val TAG = "HotReloadRegistry"

    private data class ProcessKey(val uid: Int, val pid: Int)

    private class ProcessRecord(
        val key: ProcessKey,
        @Volatile var processName: String,
    ) {
        val targetsByModule = ConcurrentHashMap<String, Long>()
        @Volatile var binder: IProcessChannel? = null
        @Volatile var deathRecipient: IBinder.DeathRecipient? = null
    }

    private class TargetRecord(
        val id: Long,
        val process: ProcessKey,
        val modulePackageName: String,
        @Volatile var loadedVersionCode: Long,
        val hotReloadable: Boolean,
    ) {
        val state = AtomicInteger(HookedProcess.TARGET_STATE_UP_TO_DATE)
    }

    private val processes = ConcurrentHashMap<ProcessKey, ProcessRecord>()
    private val targets = ConcurrentHashMap<Long, TargetRecord>()
    private val nextTargetId = AtomicLong(1)
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "NPatch-HotReload").apply { isDaemon = true }
    }

    fun recordModules(
        uid: Int,
        pid: Int,
        processName: String,
        modules: List<LoadedModule>,
    ) {
        val key = ProcessKey(uid, pid)
        val process = processes.compute(key) { _, current ->
            (current ?: ProcessRecord(key, processName)).also {
                if (processName.isNotBlank()) it.processName = processName
            }
        }!!
        modules.forEach { module ->
            val targetId = process.targetsByModule.computeIfAbsent(module.packageName) {
                val id = nextTargetId.getAndIncrement()
                targets[id] =
                    TargetRecord(
                        id = id,
                        process = key,
                        modulePackageName = module.packageName,
                        loadedVersionCode = module.versionCode,
                        hotReloadable =
                            (module.code?.targetApiVersion ?: 0) >= IXposedService.API_102 &&
                                module.code?.moduleClassNames?.size == 1 &&
                                module.code?.moduleLibraryNames?.isEmpty() == true,
                    )
                id
            }
            targets[targetId]?.let { target ->
                if (target.loadedVersionCode == 0L && module.versionCode != 0L) {
                    target.loadedVersionCode = module.versionCode
                }
            }
        }
    }

    fun register(
        uid: Int,
        pid: Int,
        processName: String,
        target: IProcessChannel,
    ) {
        val key = ProcessKey(uid, pid)
        val process = processes.computeIfAbsent(key) { ProcessRecord(key, processName) }
        if (processName.isNotBlank()) process.processName = processName

        val binder = target.asBinder()
        val recipient = IBinder.DeathRecipient { removeProcess(key) }
        synchronized(process) {
            process.binder?.asBinder()?.let { old ->
                process.deathRecipient?.let { runCatching { old.unlinkToDeath(it, 0) } }
            }
            try {
                binder.linkToDeath(recipient, 0)
                process.binder = target
                process.deathRecipient = recipient
            } catch (error: DeadObjectException) {
                removeProcess(key)
                throw error
            }
        }
        Log.d(TAG, "Registered ${process.processName} (uid=$uid, pid=$pid)")
    }

    fun getRunningTargets(modulePackageName: String): List<HookedProcess> {
        val installedVersion =
            runBlocking { ConfigManager.getInstalledModuleVersion(modulePackageName) }
        return targets.values
            .asSequence()
            .filter { it.modulePackageName == modulePackageName }
            .sortedBy { it.id }
            .mapNotNull { target ->
                val process = processes[target.process] ?: return@mapNotNull null
                HookedProcess().apply {
                    targetId = target.id
                    uid = target.process.uid
                    pid = target.process.pid
                    processName = process.processName
                    state = reportedState(target, installedVersion)
                    loadedVersionCode = target.loadedVersionCode
                }
            }
            .toList()
    }

    fun autoHotReload(module: LoadedModule) {
        if (module.code?.autoHotReload != true || module.versionCode == 0L) return
        targets.values
            .asSequence()
            .filter {
                it.modulePackageName == module.packageName &&
                    it.hotReloadable &&
                    it.loadedVersionCode != 0L &&
                    it.loadedVersionCode != module.versionCode
            }
            .forEach { target ->
                runCatching { hotReload(module.packageName, target.id, null, null) }
                    .onFailure {
                        Log.w(
                            TAG,
                            "Cannot automatically reload ${module.packageName} in target ${target.id}",
                            it,
                        )
                    }
            }
    }

    fun hotReload(
        modulePackageName: String,
        targetId: Long,
        extras: Bundle?,
        callback: IHotReloadCallback?,
    ) {
        val target =
            targets[targetId]?.takeIf { it.modulePackageName == modulePackageName }
                ?: throw SecurityException(
                    "Target $targetId is not a target of $modulePackageName"
                )
        if (!target.hotReloadable) {
            report(
                callback,
                IXposedService.HOT_RELOAD_UNSUPPORTED,
                "LoadedModule must target API 102 with one Java entry and no native entrypoints",
            )
            return
        }
        if (!beginHotReload(target)) {
            report(
                callback,
                IXposedService.HOT_RELOAD_IN_PROGRESS,
                "A reload is already running",
            )
            return
        }
        executor.execute { runHotReload(target, extras, callback) }
    }

    private fun runHotReload(
        target: TargetRecord,
        extras: Bundle?,
        callback: IHotReloadCallback?,
    ) {
        var status = IXposedService.HOT_RELOAD_FAILED
        var message: String? = "Hot reload did not run"
        var loadedVersion: Long? = null
        try {
            val process = processes[target.process]
            val binder = process?.binder
            if (binder == null) {
                status = IXposedService.HOT_RELOAD_UNSUPPORTED
                message = "Target process has no hot reload entry point"
                return
            }
            if (!binder.asBinder().isBinderAlive) {
                status = IXposedService.HOT_RELOAD_PROCESS_DIED
                message = "Target process is gone"
                return
            }
            val loadedModule = runBlocking { ConfigManager.getModuleFile(target.modulePackageName) }
            if (loadedModule == null) {
                status = IXposedService.HOT_RELOAD_UNSUPPORTED
                message = "No installed generation of ${target.modulePackageName} to load"
                return
            }
            
            val latch = java.util.concurrent.CountDownLatch(1)
            var finalOutcome: org.matrix.vector.ipc.HotReloadOutcome? = null
            
            binder.hotReload(target.modulePackageName, extras, loadedModule, object : org.matrix.vector.ipc.IHotReloadOutcomeReceiver.Stub() {
                override fun onOutcome(outcome: org.matrix.vector.ipc.HotReloadOutcome) {
                    finalOutcome = outcome
                    latch.countDown()
                }
            })
            
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            val outcome = finalOutcome
            
            if (outcome != null) {
                status = outcome.status
                if (status == IXposedService.HOT_RELOAD_SUCCEEDED) {
                    loadedVersion = loadedModule.versionCode
                }
                message =
                    outcome.message
                        ?: if (status == IXposedService.HOT_RELOAD_FAILED && !outcome.refused) {
                            "Hot reload failed without a diagnostic message"
                        } else {
                            null
                        }
            } else {
                status = IXposedService.HOT_RELOAD_FAILED
                message = "Hot reload timed out"
            }
        } catch (_: DeadObjectException) {
            status = IXposedService.HOT_RELOAD_PROCESS_DIED
            message = "Target process died during hot reload"
        } catch (throwable: Throwable) {
            status = IXposedService.HOT_RELOAD_FAILED
            message = "${throwable.javaClass.name}: ${throwable.message ?: "no message"}"
            Log.e(TAG, "Hot reload of ${target.modulePackageName} failed", throwable)
        } finally {
            loadedVersion?.let { target.loadedVersionCode = it }
            target.state.set(stateFor(status))
            report(callback, status, message)
        }
    }

    private fun beginHotReload(target: TargetRecord): Boolean {
        while (true) {
            val current = target.state.get()
            if (current == HookedProcess.TARGET_STATE_RELOADING) return false
            if (target.state.compareAndSet(current, HookedProcess.TARGET_STATE_RELOADING)) {
                return true
            }
        }
    }

    private fun stateFor(status: Int): Int =
        when (status) {
            IXposedService.HOT_RELOAD_SUCCEEDED -> HookedProcess.TARGET_STATE_UP_TO_DATE
            IXposedService.HOT_RELOAD_FAILED -> HookedProcess.TARGET_STATE_FAILED
            else -> HookedProcess.TARGET_STATE_UP_TO_DATE
        }

    private fun reportedState(target: TargetRecord, installedVersion: Long?): Int {
        val state = target.state.get()
        if (state != HookedProcess.TARGET_STATE_UP_TO_DATE) return state
        if (target.loadedVersionCode == 0L || installedVersion == null) return state
        return if (target.loadedVersionCode == installedVersion) {
            state
        } else {
            HookedProcess.TARGET_STATE_STALE
        }
    }

    private fun removeProcess(key: ProcessKey) {
        val process = processes.remove(key) ?: return
        process.targetsByModule.values.forEach(targets::remove)
    }

    private fun report(
        callback: IHotReloadCallback?,
        status: Int,
        message: String?,
    ) {
        runCatching { callback?.onHotReloadResult(status, message) }
            .onFailure { Log.w(TAG, "Cannot deliver hot reload result", it) }
    }
}
