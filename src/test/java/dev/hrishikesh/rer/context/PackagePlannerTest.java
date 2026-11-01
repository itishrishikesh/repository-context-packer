package dev.hrishikesh.rer.context;

import static org.assertj.core.api.Assertions.*;

import dev.hrishikesh.rer.context.capture.CapturedSource;
import dev.hrishikesh.rer.context.planning.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PackagePlannerTest {
  CapturedSource s(String root, String path, String hash, long bytes) {
    return new CapturedSource(root, path, hash, bytes, "text", 1, 1);
  }

  @Test
  void canonicalOrderGreedyBudgetAliasesAndReorderedDeclarations() {
    var a = s("a", "big", "big", 100);
    var alias = s("b", "copy", "big", 100);
    var b = s("a", "small", "small", 5);
    var empty = s("z", "empty", "empty", 0);
    var planner = new PackagePlanner();
    var first = planner.plan(5, List.of(alias, b, empty, a), List.of());
    assertThat(first).isEqualTo(planner.plan(5, List.of(a, empty, b, alias), List.of()));
    assertThat(first.includedUniqueBytes()).isEqualTo(5);
    assertThat(first.sources())
        .extracting(IncludedSource::identity)
        .containsExactly("a/small", "z/empty");
    assertThat(first.omissions())
        .extracting(OmittedSource::reason)
        .containsExactly(OmissionReason.BUDGET_EXCEEDED, OmissionReason.DUPLICATE_CONTENT);
    assertThat(first.omissions().getFirst().remainingBytes()).isEqualTo(5);
    assertThat(first.omissions().getFirst().requiredBytes()).isEqualTo(100);
    assertThat(first.omissions().getLast().canonical()).isEqualTo("a/big");
    assertThat(first.selectedFiles()).isEqualTo(first.includedFiles() + first.omittedFiles());
  }

  @Test
  void duplicatesCostZeroAndAreRecordedOnIncludedCanonical() {
    var plan =
        new PackagePlanner()
            .plan(3, List.of(s("b", "a", "same", 3), s("a", "z", "same", 3)), List.of());
    assertThat(plan.includedUniqueBytes()).isEqualTo(3);
    assertThat(plan.sources()).hasSize(1);
    assertThat(plan.sources().getFirst().identity()).isEqualTo("a/z");
    assertThat(plan.sources().getFirst().aliases()).containsExactly("b/a");
  }
}
