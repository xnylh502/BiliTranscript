package com.example.bilitranscript

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.io.OutputStream

/**
 * 保存文案为TXT文件到下载目录
 */
object TranscriptSaver {

    fun save(
        context: Context,
        title: String,
        content: String,
        extension: String = "txt",
        mime: String = "text/plain"
    ): Boolean {
        val filename = "文案_${sanitizeFilename(title)}_${System.currentTimeMillis()}.$extension"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, filename, content, mime)
            } else {
                saveLegacy(filename, content)
            }
            Toast.makeText(context, "已保存到下载: $filename", Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun saveViaMediaStore(context: Context, filename: String, content: String, mime: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("无法创建文件")

        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw Exception("无法写入文件")

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(filename: String, content: String) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, filename)
        file.writeText(content, Charsets.UTF_8)
    }

    private fun sanitizeFilename(title: String): String {
        val invalid = arrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        var result = title
        for (c in invalid) {
            result = result.replace(c.toString(), "_")
        }
        return result.take(30)
    }
}
