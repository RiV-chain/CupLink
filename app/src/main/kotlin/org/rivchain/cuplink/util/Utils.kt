package org.rivchain.cuplink.util

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import org.rivchain.cuplink.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.regex.Pattern
import java.util.zip.CRC32

internal object Utils {

    fun getAndroidVersionFromApi(): Long {
        val apiLevel = Build.VERSION.SDK_INT
        val apiToVersionMap = mapOf(
            21 to 5L,
            22 to 5L,
            23 to 6L,
            24 to 7L,
            25 to 7L,
            26 to 8L,
            27 to 8L,
            28 to 9L,
            29 to 10L,
            30 to 11L,
            31 to 12L,
            32 to 12L, // 12L
            33 to 13L,
            34 to 14L
        )

        return apiToVersionMap[apiLevel] ?: -1L // -1 indicates an unknown API level
    }

    fun byteArrayToCRC32Int(bytes: ByteArray): Int {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value.toInt()
    }

    fun getThreadInfo(): String {
        val thread =  Thread.currentThread()
        return "@[name=${thread.name}, id=${thread.id}]"
    }

    fun assertIsTrue(condition: Boolean) {
        if (!condition) {
            throw AssertionError("Expected condition to be true")
        }
    }

    fun checkIsOnMainThread() {
        if (Thread.currentThread() != Looper.getMainLooper().thread) {
            throw IllegalStateException("Code must run on the main thread!")
        }
    }

    fun checkIsNotOnMainThread() {
        if (Thread.currentThread() == Looper.getMainLooper().thread) {
            throw IllegalStateException("Code must not run on the main thread!")
        }
    }

    fun printStackTrace() {
        try {
            throw Exception("printStackTrace() called")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hasPermission(context: Context, permission: String): Boolean {
        return (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }

    private val NAME_PATTERN = Pattern.compile("[\\w]|[\\w][\\w _-]{0,22}[\\w]")

    // Check for a name that has no funny unicode characters
    // and to not let them look to much like other names.
    fun isValidName(name: String?): Boolean {
        if (name.isNullOrEmpty()) {
            return false
        }
        return NAME_PATTERN.matcher(name).matches()
    }

    fun byteArrayToHexString(bytes: ByteArray?): String {
        if (bytes == null) {
            return ""
        }

        return bytes.joinToString(separator = "") {
            eachByte -> "%02x".format(eachByte)
        }
    }

    fun hexStringToByteArray(str: String?): ByteArray? {
        if (str == null || (str.length % 2) != 0 || !str.all { it in '0'..'9' || it in 'a'..'f' || it in 'A' .. 'F' }) {
            return null
        }

        return str.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    // write file to external storage
    fun writeExternalFile(filepath: String, data: ByteArray?) {
        val file = File(filepath)
        if (file.exists() && file.isFile) {
            if (!file.delete()) {
                throw IOException("Failed to delete existing file: $filepath")
            }
        }
        file.createNewFile()
        val fos = FileOutputStream(file)
        fos.write(data)
        fos.close()
    }

    // read file from external storage
    fun readExternalFile(filepath: String): ByteArray {
        val file = File(filepath)
        if (!file.exists() || !file.isFile) {
            throw IOException("File does not exist: $filepath")
        }
        val fis = FileInputStream(file)
        var nRead: Int
        val data = ByteArray(16384)
        val buffer = ByteArrayOutputStream()
        while (fis.read(data, 0, data.size).also { nRead = it } != -1) {
            buffer.write(data, 0, nRead)
        }
        fis.close()
        return buffer.toByteArray()
    }

     fun readResourceFile(context: Context, id: Int): String {
        val inputStream = context.resources.openRawResource(id)
        val byteArrayOutputStream = ByteArrayOutputStream()
        var i: Int
        try {
            i = inputStream.read()
            while (i != -1) {
                byteArrayOutputStream.write(i)
                i = inputStream.read()
            }
            inputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return byteArrayOutputStream.toString()
    }

   fun getExternalFileSize(ctx: Context, uri: Uri?): Long {
        val cursor = ctx.contentResolver.query(uri!!, null, null, null, null)
        cursor!!.moveToFirst()
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index >= 0) {
            val size = cursor.getLong(index)
            cursor.close()
            return size
        } else {
            cursor.close()
            return -1
        }
    }

    fun readExternalFile(ctx: Context, uri: Uri): ByteArray {
        val size = getExternalFileSize(ctx, uri).toInt()
        val isstream = ctx.contentResolver.openInputStream(uri)
        val buffer = ByteArrayOutputStream()
        var nRead = 0
        val dataArray = ByteArray(size)
        while (isstream != null && isstream.read(dataArray, 0, dataArray.size).also { nRead = it } != -1) {
            buffer.write(dataArray, 0, nRead)
        }
        isstream?.close()
        return dataArray
    }

    private fun writeExternalFile(ctx: Context, uri: Uri, dataArray: ByteArray) {
        ctx.contentResolver.openOutputStream(uri)?.use { fos ->
            fos.write(dataArray)
        } ?: throw IOException("Failed to open output stream for URI: $uri")
    }

    fun exportDatabase(context: Context, uri: Uri, dbData: ByteArray?) {
        if (dbData != null && dbData.isNotEmpty()) {
            writeExternalFile(context, uri, dbData)
        } else {
            throw Exception(context.getString(R.string.failed_to_export_database))
        }
    }

    // write file to external storage
    fun writeInternalFile(filePath: String, dataArray: ByteArray) {
        val file = File(filePath)
        if (file.exists() && file.isFile) {
            if (!file.delete()) {
                throw IOException("Failed to delete existing file: $filePath")
            }
        }
        file.createNewFile()
        val fos = FileOutputStream(file)
        fos.write(dataArray)
        fos.close()
    }

    // read file from external storage
    fun readInternalFile(filePath: String): ByteArray {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            throw IOException("File does not exist: $filePath")
        }
        val fis = FileInputStream(file)
        var nRead: Int
        val dataArray = ByteArray(16384)
        val buffer = ByteArrayOutputStream()
        while (fis.read(dataArray, 0, dataArray.size).also { nRead = it } != -1) {
            buffer.write(dataArray, 0, nRead)
        }
        fis.close()
        return buffer.toByteArray()
    }
}
