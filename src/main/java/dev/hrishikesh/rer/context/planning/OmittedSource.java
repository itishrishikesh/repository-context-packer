package dev.hrishikesh.rer.context.planning;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OmittedSource(
    String root,
    String path,
    OmissionReason reason,
    Long bytes,
    Long requiredBytes,
    Long remainingBytes,
    String canonical,
    String rule) {
  public String identity() {
    return root + "/" + path;
  }

  public static OmittedSource policy(String root, String path, OmissionReason reason, String rule) {
    return new OmittedSource(root, path, reason, null, null, null, null, rule);
  }
}
