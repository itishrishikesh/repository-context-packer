package dev.hrishikesh.rer.context.capture;

import static org.assertj.core.api.Assertions.*;

import dev.hrishikesh.rer.context.*;
import dev.hrishikesh.rer.context.path.ValidatedSource;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StableFileReaderTest {
  @TempDir Path temp;

  private ValidatedSource source() {
    return new ValidatedSource("repo", "a", temp.resolve("a"), false);
  }

  @Test
  void hashesRawBytesAndEditsChangeHash() throws Exception {
    byte[] raw = "héllo\r\nworld\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(source().file(), raw);
    var first = new StableFileReader().capture(source()).orElseThrow();
    assertThat(first.bytes()).isEqualTo(raw.length);
    assertThat(first.text()).isEqualTo("héllo\nworld\n");
    assertThat(first.sha256())
        .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
    Files.writeString(source().file(), "changed");
    assertThat(new StableFileReader().capture(source()).orElseThrow().sha256())
        .isNotEqualTo(first.sha256());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "a", "a\n", "a\r\n", "a\rb", "a\nb", "\n", "\n\n"})
  void logicalLineCounts(String text) throws Exception {
    Files.writeString(source().file(), text);
    var captured = new StableFileReader().capture(source()).orElseThrow();
    int expected = text.isEmpty() ? 0 : text.equals("\n\n") || text.contains("b") ? 2 : 1;
    assertThat(captured.lineStart()).isEqualTo(expected == 0 ? 0 : 1);
    assertThat(captured.lineEnd()).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"size", "lastModifiedTime", "fileKey"})
  void detectsControlledChanges(String attribute) throws Exception {
    Files.writeString(source().file(), "a");
    assertThatThrownBy(() -> TestReaders.changing(attribute).capture(source()))
        .isInstanceOfSatisfying(
            PackException.class,
            e -> {
              assertThat(e.exitCode()).isEqualTo(4);
              assertThat(e.getMessage()).contains("repo/a");
            });
  }

  @Test
  void readFailureIsReportedWithSource() throws Exception {
    Files.writeString(source().file(), "a");
    var reader =
        new StableFileReader(
            new StableFileReader.FileAccess() {
              public java.nio.file.attribute.BasicFileAttributes attributes(Path file)
                  throws java.io.IOException {
                return Files.readAttributes(
                    file, java.nio.file.attribute.BasicFileAttributes.class);
              }

              public byte[] read(Path file) throws java.io.IOException {
                throw new java.io.IOException("controlled read failure");
              }
            });
    assertThatThrownBy(() -> reader.capture(source()))
        .isInstanceOfSatisfying(
            PackException.class,
            e -> {
              assertThat(e.exitCode()).isEqualTo(3);
              assertThat(e.getMessage()).contains("repo/a", "controlled read failure");
            });
  }

  @Test
  void disappearedAfterReadIsChangedSource() throws Exception {
    Files.writeString(source().file(), "a");
    var reader =
        new StableFileReader(
            new StableFileReader.FileAccess() {
              public java.nio.file.attribute.BasicFileAttributes attributes(Path file)
                  throws java.io.IOException {
                return Files.readAttributes(
                    file, java.nio.file.attribute.BasicFileAttributes.class);
              }

              public byte[] read(Path file) throws java.io.IOException {
                byte[] bytes = Files.readAllBytes(file);
                Files.delete(file);
                return bytes;
              }
            });
    assertThatThrownBy(() -> reader.capture(source()))
        .isInstanceOfSatisfying(PackException.class, e -> assertThat(e.exitCode()).isEqualTo(4));
  }

  @Test
  void invalidUtf8IsOmittedAndReadFailureIsHardError() throws Exception {
    Files.write(source().file(), new byte[] {(byte) 0xc3, 0x28});
    assertThat(new StableFileReader().capture(source())).isEmpty();
    Files.delete(source().file());
    assertThatThrownBy(() -> new StableFileReader().capture(source()))
        .isInstanceOfSatisfying(PackException.class, e -> assertThat(e.exitCode()).isEqualTo(3));
  }
}
