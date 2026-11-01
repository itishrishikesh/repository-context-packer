package dev.hrishikesh.rer.context.planning;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.hrishikesh.rer.context.capture.CapturedSource;
import java.util.List;

@com.fasterxml.jackson.annotation.JsonInclude(
    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record IncludedSource(
    String root,
    String path,
    String sha256,
    long bytes,
    int lineStart,
    int lineEnd,
    String encoding,
    String ignoreRule,
    List<String> aliases,
    @JsonIgnore String text) {
  public static IncludedSource from(CapturedSource source, List<String> aliases) {
    return new IncludedSource(
        source.root(),
        source.path(),
        source.sha256(),
        source.bytes(),
        source.lineStart(),
        source.lineEnd(),
        "UTF-8",
        source.ignoreRule(),
        List.copyOf(aliases),
        source.text());
  }

  public String identity() {
    return root + "/" + path;
  }
}
