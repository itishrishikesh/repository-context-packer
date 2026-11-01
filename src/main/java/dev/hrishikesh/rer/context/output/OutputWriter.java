package dev.hrishikesh.rer.context.output;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Single-writer, same-parent directory replacement with rollback on publication failure. */
public final class OutputWriter {
  @FunctionalInterface
  interface Mover {
    void move(Path from, Path to) throws IOException;
  }

  @FunctionalInterface
  interface Renderer {
    void render(Path staging) throws IOException;
  }

  private final Mover mover;

  public OutputWriter() {
    this(OutputWriter::moveDirectory);
  }

  OutputWriter(Mover mover) {
    this.mover = mover;
  }

  public void write(Path output, String markdown, String json) throws IOException {
    write(
        output,
        staging -> {
          Files.writeString(staging.resolve("context.md"), markdown, StandardCharsets.UTF_8);
          Files.writeString(staging.resolve("manifest.json"), json, StandardCharsets.UTF_8);
        });
  }

  void write(Path output, Renderer renderer) throws IOException {
    Files.createDirectories(output.getParent());
    validateReplacement(output);
    Path staging = Files.createTempDirectory(output.getParent(), ".rer-stage-");
    Path backup = null;
    boolean published = false, restored = false;
    try {
      renderer.render(staging);
      if (Files.exists(output)) {
        backup = Files.createTempDirectory(output.getParent(), ".rer-backup-");
        Files.delete(backup);
        mover.move(output, backup);
      }
      try {
        mover.move(staging, output);
        published = true;
      } catch (IOException failure) {
        if (backup != null && Files.exists(backup)) {
          try {
            mover.move(backup, output);
            restored = true;
          } catch (IOException restore) {
            failure.addSuppressed(restore);
            throw new IOException(
                "Publication and rollback failed; previous package retained at " + backup, failure);
          }
        }
        throw failure;
      }
    } finally {
      deleteTree(staging);
      // Retain a backup if restoration itself fails; never delete the last good package.
      if (backup != null && (published || restored)) deleteTree(backup);
    }
  }

  private static void validateReplacement(Path output) throws IOException {
    if (!Files.exists(output)) return;
    try (var entries = Files.list(output)) {
      for (Path entry : entries.toList()) {
        if (!Set.of("context.md", "manifest.json").contains(entry.getFileName().toString())
            || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
          throw new IOException(
              "Output directory contains unrelated entries; choose a dedicated package directory");
      }
    }
  }

  private static void moveDirectory(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(from, to);
    }
  }

  private static void deleteTree(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    try (var stream = Files.walk(path)) {
      for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) Files.delete(item);
    }
  }
}
