package dev.hrishikesh.rer.context;

public final class PackException extends RuntimeException {
  private final int exitCode;

  public PackException(int exitCode, String message) {
    super(message);
    this.exitCode = exitCode;
  }

  public PackException(int exitCode, String message, Throwable cause) {
    super(message, cause);
    this.exitCode = exitCode;
  }

  public int exitCode() {
    return exitCode;
  }
}
