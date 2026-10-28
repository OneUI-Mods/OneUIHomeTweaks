package dev.ryuya.oneuihometweaks;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * App-private bounded journal; the provider authenticates the launcher before accepting records.
 */
final class HomeLayoutJournal {
  private static final int LIMIT = 1024 * 1024;
  private static final int FILES = 4;

  private HomeLayoutJournal() {}

  static synchronized void append(Context context, String records) throws IOException {
    if (records == null || records.length() > 48000) throw new IOException("Invalid journal batch");
    File directory = directory(context);
    File current = new File(directory, "layout-0.jsonl");
    byte[] bytes = records.getBytes(StandardCharsets.UTF_8);
    if (current.length() + bytes.length > LIMIT) {
      Files.deleteIfExists(new File(directory, "layout-" + (FILES - 1) + ".jsonl").toPath());
      for (int i = FILES - 2; i >= 0; i--) {
        File source = new File(directory, "layout-" + i + ".jsonl");
        if (source.exists())
          Files.move(source.toPath(), new File(directory, "layout-" + (i + 1) + ".jsonl").toPath());
      }
    }
    try (FileOutputStream out = new FileOutputStream(current, true)) {
      out.write(bytes);
    }
  }

  static synchronized File export(Context context) throws IOException {
    File directory = new File(context.getCacheDir(), "diagnostics");
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Export unavailable");
    File output = new File(directory, "home-layout.jsonl");
    try (OutputStream out = new FileOutputStream(output)) {
      for (int i = FILES - 1; i >= 0; i--) {
        File input = new File(directory(context), "layout-" + i + ".jsonl");
        if (input.isFile()) Files.copy(input.toPath(), out);
      }
    }
    return output;
  }

  static synchronized long bytes(Context context) {
    long size = 0;
    File[] files = directory(context).listFiles();
    if (files != null) for (File file : files) size += file.length();
    return size;
  }

  static synchronized void clear(Context context) throws IOException {
    Files.deleteIfExists(new File(context.getCacheDir(), "diagnostics/home-layout.jsonl").toPath());
    for (int i = 0; i < FILES; i++)
      Files.deleteIfExists(new File(directory(context), "layout-" + i + ".jsonl").toPath());
  }

  private static File directory(Context context) {
    File directory = new File(context.getFilesDir(), "home-layout");
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IllegalStateException("Journal directory unavailable");
    return directory;
  }
}
