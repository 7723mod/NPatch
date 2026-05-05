package top.nkbe.npatch.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.runBlocking
import top.nkbe.npatch.config.ConfigManager

class ConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "top.nkbe.npatch.manager.provider.config"
        const val TAG = "ConfigProvider"
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val targetPackage = uri.getQueryParameter("package")
        if (targetPackage.isNullOrEmpty()) return null

        val modulesList = runBlocking {
            try {
                // 修正：直接使用 ConfigManager 來獲取該 APP 啟用的模組列表
                ConfigManager.getModulesForApp(targetPackage).map { it.pkgName }
            } catch (e: Exception) {
                Log.e(TAG, "Database query failed", e)
                emptyList<String>()
            }
        }

        // 返回 Cursor 給被修補的 APP
        val cursor = MatrixCursor(arrayOf("packageName"))
        modulesList.forEach { cursor.addRow(arrayOf(it)) }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
