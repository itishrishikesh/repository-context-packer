package dev.hrishikesh.rer.context.output;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class OutputWriterTest {
  @TempDir Path temp;

  @Test
  void replacesExistingPackageAndCleansOwnedPaths() throws Exception {
    Path output = temp.resolve("out");
    var writer = new OutputWriter();
    writer.write(output, "old md", "old json");
    writer.write(output, "new md", "new json");
    assertThat(Files.readString(output.resolve("context.md"))).isEqualTo("new md");
    assertThat(Files.readString(output.resolve("manifest.json"))).isEqualTo("new json");
    assertClean();
  }

  @Test
  void renderFailurePreservesCompleteOldPackage() throws Exception {
    Path output = temp.resolve("out");
    var writer = new OutputWriter();
    writer.write(output, "old md", "old json");
    assertThatThrownBy(
            () ->
                writer.write(
                    output,
                    stage -> {
                      Files.writeString(stage.resolve("context.md"), "partial");
                      throw new IOException("render failure");
                    }))
        .isInstanceOf(IOException.class);
    assertOld(output);
    assertClean();
  }

  @Test
  void publicationFailureRestoresCompleteOldPackage() throws Exception {
    Path output = temp.resolve("out");
    new OutputWriter().write(output, "old md", "old json");
    var moves = new AtomicInteger();
    var writer =
        new OutputWriter(
            (from, to) -> {
              if (moves.incrementAndGet() == 2) throw new IOException("publish failure");
              Files.move(from, to);
            });
    assertThatThrownBy(() -> writer.write(output, "new md", "new json"))
        .isInstanceOf(IOException.class);
    assertOld(output);
    assertClean();
  }

  @Test
  void backupMoveFailureAndFirstPublicationFailureCleanStaging() throws Exception {
    Path output = temp.resolve("out");
    var writer =
        new OutputWriter(
            (from, to) -> {
              throw new IOException("move failure");
            });
    assertThatThrownBy(() -> writer.write(output, "new", "new")).isInstanceOf(IOException.class);
    assertThat(output).doesNotExist();
    new OutputWriter().write(output, "old md", "old json");
    assertThatThrownBy(() -> writer.write(output, "new", "new")).isInstanceOf(IOException.class);
    assertOld(output);
    assertClean();
  }

  @Test
  void failedRollbackRetainsLastGoodBackupAndReportsItsLocation() throws Exception {
    Path output = temp.resolve("out");
    new OutputWriter().write(output, "old md", "old json");
    var moves = new AtomicInteger();
    var writer =
        new OutputWriter(
            (from, to) -> {
              if (moves.incrementAndGet() > 1) throw new IOException("unavailable filesystem");
              Files.move(from, to);
            });
    assertThatThrownBy(() -> writer.write(output, "new", "new"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("previous package retained at");
    try (var entries = Files.list(temp)) {
      var backups =
          entries.filter(p -> p.getFileName().toString().startsWith(".rer-backup-")).toList();
      assertThat(backups).hasSize(1);
      assertOld(backups.getFirst());
    }
    try (var entries = Files.list(temp)) {
      assertThat(entries.filter(p -> p.getFileName().toString().startsWith(".rer-stage-")).toList())
          .isEmpty();
    }
  }

  @Test
  void rejectsUnrelatedDirectoryContents() throws Exception {
    Path output = Files.createDirectory(temp.resolve("out"));
    Path keep = Files.writeString(output.resolve("keep"), "user data");
    assertThatThrownBy(() -> new OutputWriter().write(output, "new", "new"))
        .isInstanceOf(IOException.class);
    assertThat(Files.readString(keep)).isEqualTo("user data");
    assertClean();
  }

  private void assertOld(Path output) throws Exception {
    assertThat(Files.readString(output.resolve("context.md"))).isEqualTo("old md");
    assertThat(Files.readString(output.resolve("manifest.json"))).isEqualTo("old json");
  }

  private void assertClean() throws Exception {
    try (var entries = Files.list(temp)) {
      assertThat(entries.filter(p -> p.getFileName().toString().startsWith(".rer-")).toList())
          .isEmpty();
    }
  }
}
