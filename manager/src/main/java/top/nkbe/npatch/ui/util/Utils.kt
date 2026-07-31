package top.nkbe.npatch.ui.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState

val LazyListState.lastVisibleItemIndex
    get() = layoutInfo.visibleItemsInfo.lastOrNull()?.index

val LazyListState.lastItemIndex
    get() = layoutInfo.totalItemsCount.let { if (it == 0) null else it }

val LazyListState.isScrolledToEnd
    get() = lastVisibleItemIndex == lastItemIndex

fun checkIsApkFixedByLSP(context: Context, packageName: String): Boolean {
    return try {
        val app =
            context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        (app.metaData?.containsKey("npatch") != true)
    } catch (_: PackageManager.NameNotFoundException) {
        Log.e("NPatch", "Package not found: $packageName")
        false
    } catch (e: Exception) {
        Log.e("NPatch", "Unexpected error in checkIsApkFixedByLSP", e)
        false
    }
}
