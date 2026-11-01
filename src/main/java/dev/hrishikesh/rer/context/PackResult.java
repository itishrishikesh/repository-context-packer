package dev.hrishikesh.rer.context;

public record PackResult(
    int exitCode, String message, int includedFiles, long includedUniqueBytes, int omittedFiles) {
  public static PackResult failure(int code, String message) {
    return new PackResult(code, message, 0, 0, 0);
  }
}
