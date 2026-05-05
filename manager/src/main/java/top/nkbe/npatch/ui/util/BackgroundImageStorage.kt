package top.nkbe.npatch.ui.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object BackgroundImageStorage {
    private const val DIRECTORY_NAME = "backgrounds"
    private const val FILE_NAME = "background_image"

    suspend fun persistFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val targetFile = prepareFile(context)
        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Unable to open background image input stream")
        targetFile.absolutePath
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        val targetFile = prepareFile(context)
        if (targetFile.exists()) {
            targetFile.delete()
        }
    }

    private fun prepareFile(context: Context): File {
        val dir = File(context.filesDir, DIRECTORY_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, FILE_NAME)
    }
}
