package dev.hrishikesh.rer.context.capture;

import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.*;

public final class TestReaders {
  public static StableFileReader changing(String attribute) {
    return new StableFileReader(
        new StableFileReader.FileAccess() {
          int calls;

          public BasicFileAttributes attributes(Path file) throws IOException {
            var real = Files.readAttributes(file, BasicFileAttributes.class);
            if (++calls == 1) return real;
            return (BasicFileAttributes)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {BasicFileAttributes.class},
                    (proxy, method, args) -> {
                      if (method.getName().equals(attribute))
                        return switch (attribute) {
                          case "size" -> real.size() + 1;
                          case "lastModifiedTime" ->
                              FileTime.fromMillis(real.lastModifiedTime().toMillis() + 1);
                          case "fileKey" -> "replacement";
                          default -> throw new IllegalArgumentException(attribute);
                        };
                      return method.invoke(real, args);
                    });
          }

          public byte[] read(Path file) throws IOException {
            return Files.readAllBytes(file);
          }
        });
  }
}
