package dev.ryuya.oneuihometweaks

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized

/*
 * App-private bounded journal; the provider authenticates the launcher before accepting records.
 */
object HomeLayoutJournal {
    private val LIMIT: Int = 1024 * 1024
    private const val FILES: Int = 4

    @JvmStatic
    @Synchronized
    @Throws(IOException::class)
    fun append(context: Context, records: String?) {
        if (records == null || records.length > 48000) throw IOException("Invalid journal batch")
        val directory = HomeLayoutJournal.directory(context)
        val current = File(directory, "layout-0.jsonl")
        val bytes = records.toByteArray(StandardCharsets.UTF_8)
        if (current.length() + bytes.size > HomeLayoutJournal.LIMIT) {
            Files.deleteIfExists(
                File(directory, "layout-" + (HomeLayoutJournal.FILES - 1) + ".jsonl").toPath()
            )
            for (i in HomeLayoutJournal.FILES - 2 downTo 0) {
                val source = File(directory, "layout-" + i + ".jsonl")
                if (source.exists())
                    Files.move(
                        source.toPath(),
                        File(directory, "layout-" + (i + 1) + ".jsonl").toPath(),
                    )
            }
        }
        FileOutputStream(current, true).use { out ->
            out.write(bytes)
        }
    }

    @JvmStatic
    @Synchronized
    @Throws(IOException::class)
    fun export(context: Context): File {
        val directory = File(context.getCacheDir(), "diagnostics")
        if (!directory.isDirectory() && !directory.mkdirs()) throw IOException("Export unavailable")
        val output = File(directory, "home-layout.jsonl")
        FileOutputStream(output).use { out ->
            for (i in HomeLayoutJournal.FILES - 1 downTo 0) {
                val input = File(HomeLayoutJournal.directory(context), "layout-" + i + ".jsonl")
                if (input.isFile()) Files.copy(input.toPath(), out)
            }
        }
        return output
    }

    @JvmStatic
    @Synchronized
    fun bytes(context: Context): Long {
        var size: Long = 0
        val files = HomeLayoutJournal.directory(context).listFiles()
        if (files != null) for (file in files) size += file.length()
        return size
    }

    @JvmStatic
    @Synchronized
    @Throws(IOException::class)
    fun clear(context: Context) {
        Files.deleteIfExists(File(context.getCacheDir(), "diagnostics/home-layout.jsonl").toPath())
        for (i in 0 until HomeLayoutJournal.FILES) Files.deleteIfExists(
            File(HomeLayoutJournal.directory(context), "layout-" + i + ".jsonl").toPath()
        )
    }

    @JvmStatic
    private fun directory(context: Context): File {
        val directory = File(context.getFilesDir(), "home-layout")
        check(!(!directory.isDirectory() && !directory.mkdirs())) {
            "Journal directory unavailable"
        }
        return directory
    }
}
