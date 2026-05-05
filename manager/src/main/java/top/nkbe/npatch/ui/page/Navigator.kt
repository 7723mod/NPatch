package top.nkbe.npatch.ui.page

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

/**
 * 导航辅助类，管理返回栈和结果通道。
 * 包装 SnapshotStateList<NavKey> 提供导航操作。
 */
class Navigator(
    val backStack: MutableList<NavKey>,
) {
    private val resultBus = mutableMapOf<NavKey, MutableSharedFlow<Any>>()

    private object CancelToken

    fun push(key: NavKey) {
        backStack.add(key)
    }

    /** 别名：与 DestinationsNavigator.navigate 对应 */
    fun navigate(key: NavKey) = push(key)

    fun replace(key: NavKey) {
        if (backStack.isNotEmpty()) {
            backStack[backStack.lastIndex] = key
        } else {
            backStack.add(key)
        }
    }

    fun pop() {
        if (backStack.size > 1) {
            val popped = backStack.removeLastOrNull()
            if (popped != null) {
                resultBus[popped]?.tryEmit(CancelToken)
            }
        }
    }

    /** 别名：与 DestinationsNavigator.navigateUp 对应 */
    fun navigateUp() = pop()

    /** 别名：与 ResultBackNavigator.navigateBack 对应 */
    fun navigateBack() = pop()

    fun popUntil(predicate: (NavKey) -> Boolean) {
        while (backStack.isNotEmpty() && !predicate(backStack.last())) {
            pop()
        }
    }

    /**
     * 导航并等待结果。
     * 返回 null 表示被取消（如按返回键）。
     */
    suspend inline fun <reified T : Any> navigateForResult(route: NavKey): T? {
        val result = navigateForResultInternal(route)
        return result as? T
    }

    @PublishedApi
    internal suspend fun navigateForResultInternal(route: NavKey): Any? {
        val flow = ensureChannel(route)
        push(route)
        try {
            val result = flow.first()
            if (result === CancelToken) return null
            return result
        } finally {
            resultBus.remove(route)
        }
    }

    /**
     * 设置结果并返回上一页。
     */
    fun <T : Any> setResultAndBack(value: T) {
        val currentKey = backStack.lastOrNull() ?: return
        ensureChannel(currentKey).tryEmit(value)
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    /**
     * 设置结果（不自动返回）。
     */
    fun <T : Any> setResult(key: NavKey, value: T) {
        ensureChannel(key).tryEmit(value)
    }

    private fun ensureChannel(key: NavKey): MutableSharedFlow<Any> =
        resultBus.getOrPut(key) { MutableSharedFlow(replay = 1, extraBufferCapacity = 1) }
}

/**
 * CompositionLocal 用于在 Composable 树中获取 Navigator。
 */
val LocalNavigator = compositionLocalOf<Navigator> {
    error("No Navigator provided")
}
