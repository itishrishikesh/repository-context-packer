package dev.hrishikesh.rer.context.planning;

import dev.hrishikesh.rer.context.capture.CapturedSource;
import java.util.*;

public final class PackagePlanner {
  public record Plan(
      int formatVersion,
      String status,
      long byteBudget,
      long includedUniqueBytes,
      int selectedFiles,
      int includedFiles,
      int omittedFiles,
      List<IncludedSource> sources,
      List<OmittedSource> omissions) {}

  public Plan plan(
      long budget, List<CapturedSource> captured, List<OmittedSource> policyOmissions) {
    var groups = new LinkedHashMap<String, List<CapturedSource>>();
    captured.stream()
        .sorted(Comparator.comparing(CapturedSource::identity))
        .forEach(s -> groups.computeIfAbsent(s.sha256(), key -> new ArrayList<>()).add(s));
    var included = new ArrayList<IncludedSource>();
    var omitted = new ArrayList<>(policyOmissions);
    long remaining = budget;
    for (var group : groups.values()) {
      var canonical = group.getFirst();
      var aliases = group.stream().skip(1).map(CapturedSource::identity).toList();
      if (canonical.bytes() <= remaining) {
        included.add(IncludedSource.from(canonical, aliases));
        remaining -= canonical.bytes();
      } else {
        omitted.add(
            new OmittedSource(
                canonical.root(),
                canonical.path(),
                OmissionReason.BUDGET_EXCEEDED,
                canonical.bytes(),
                canonical.bytes(),
                remaining,
                null,
                canonical.ignoreRule()));
      }
      for (var alias : group.subList(1, group.size()))
        omitted.add(
            new OmittedSource(
                alias.root(),
                alias.path(),
                OmissionReason.DUPLICATE_CONTENT,
                alias.bytes(),
                null,
                null,
                canonical.identity(),
                alias.ignoreRule()));
    }
    omitted.sort(Comparator.comparing(OmittedSource::identity));
    return new Plan(
        1,
        "COMPLETE",
        budget,
        budget - remaining,
        captured.size() + policyOmissions.size(),
        included.size(),
        omitted.size(),
        List.copyOf(included),
        List.copyOf(omitted));
  }
}
