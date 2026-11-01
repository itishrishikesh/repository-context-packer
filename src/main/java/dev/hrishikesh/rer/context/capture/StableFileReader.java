package dev.hrishikesh.rer.context.capture;

import dev.hrishikesh.rer.context.PackException;
import dev.hrishikesh.rer.context.path.ValidatedSource;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

public final class StableFileReader {
  // A narrow seam for deterministic metadata-change tests, shared by production reads.
  interface FileAccess {
    BasicFileAttributes attributes(Path file) throws IOException;

    byte[] read(Path file) throws IOException;
  }

  private final FileAccess access;

  public StableFileReader() {
    this(
        new FileAccess() {
          public BasicFileAttributes attributes(Path file) throws IOException {
            return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          }

          public byte[] read(Path file) throws IOException {
            return Files.readAllBytes(file);
          }
        });
  }

  StableFileReader(FileAccess access) {
    this.access = access;
  }

  public Optional<CapturedSource> capture(ValidatedSource source) {
    try {
      var before = access.attributes(source.file());
      if (!before.isRegularFile() || before.isSymbolicLink()) changed(source);
      byte[] bytes;
      try {
        bytes = access.read(source.file());
      } catch (NoSuchFileException e) {
        throw new PackException(4, source.identity() + ": source disappeared during capture", e);
      }
      BasicFileAttributes after;
      try {
        after = access.attributes(source.file());
      } catch (NoSuchFileException e) {
        throw new PackException(4, source.identity() + ": source disappeared during capture", e);
      }
      if (!after.isRegularFile()
          || before.size() != after.size()
          || before.size() != bytes.length
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !Objects.equals(before.fileKey(), after.fileKey())) changed(source);
      String text;
      try {
        text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
      } catch (CharacterCodingException e) {
        return Optional.empty();
      }
      text = text.replace("\r\n", "\n").replace('\r', '\n');
      int lines =
          text.isEmpty()
              ? 0
              : (int) text.chars().filter(c -> c == '\n').count() + (text.endsWith("\n") ? 0 : 1);
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      return Optional.of(
          new CapturedSource(
              source.root(), source.path(), hash, bytes.length, text, lines == 0 ? 0 : 1, lines));
    } catch (IOException e) {
      throw new PackException(3, source.identity() + ": capture failed: " + e.getMessage(), e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void changed(ValidatedSource source) {
    throw new PackException(4, source.identity() + ": source changed during capture");
  }
}
