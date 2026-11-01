package dev.hrishikesh.rer.context.capture;

public record CapturedSource(
    String root,
    String path,
    String sha256,
    long bytes,
    String text,
    int lineStart,
    int lineEnd,
    String ignoreRule) {
  public CapturedSource(
      String root,
      String path,
      String sha256,
      long bytes,
      String text,
      int lineStart,
      int lineEnd) {
    this(root, path, sha256, bytes, text, lineStart, lineEnd, null);
  }

  public CapturedSource withIgnoreRule(String rule) {
    return new CapturedSource(root, path, sha256, bytes, text, lineStart, lineEnd, rule);
  }

  public String identity() {
    return root + "/" + path;
  }
}
