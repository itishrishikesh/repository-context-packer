package dev.hrishikesh.rer.context;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hrishikesh.rer.context.capture.TestReaders;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class OutputTest {
  @TempDir Path temp;

  @Test
  void fullArtifactsAreDeterministicTraceableAndSafelyFenced() throws Exception {
    var f = new Fixture(temp);
    f.file("a.md", "héllo\r\n````\r\nend");
    f.file("copy.md", "héllo\r\n````\r\nend");
    f.file("big", "x".repeat(200));
    Files.write(f.repo.resolve("binary"), new byte[] {(byte) 0xff});
    f.file("hidden", "EXCLUDED_SECRET");
    f.select(100, "copy.md", "a.md", "big", "binary", "hidden");
    f.append("exclusions:\n  - root: repo\n    path: hidden\n");
    assertThat(f.pack().exitCode()).isZero();
    String md = f.markdown(), json = f.json();
    assertThat(md)
        .contains(
            "`````\nhéllo\n````\nend\n`````",
            "repo/a.md#L1-L3",
            "untrusted data",
            "BUDGET_EXCEEDED")
        .doesNotContain("EXCLUDED_SECRET", "\r");
    assertThat(json)
        .contains("DUPLICATE_CONTENT", "UNSUPPORTED_ENCODING", "EXCLUDED", "repo/copy.md")
        .doesNotContain("EXCLUDED_SECRET", temp.toString(), "héllo", "\r");
    var tree = new ObjectMapper().readTree(json);
    assertThat(tree.get("status").asText()).isEqualTo("COMPLETE");
    assertThat(tree.get("sources").get(0).get("lineEnd").asInt()).isEqualTo(3);
    assertThat(f.pack().exitCode()).isZero();
    assertThat(f.markdown()).isEqualTo(md);
    assertThat(f.json()).isEqualTo(json);
  }

  @Test
  void equivalentRepositoriesUnderDifferentAbsoluteParentsMatch() throws Exception {
    String previous = null;
    for (String name : new String[] {"one", "two"}) {
      var f = new Fixture(Files.createDirectory(temp.resolve(name)));
      f.file("a", "same");
      f.select(100, "a");
      assertThat(f.pack().exitCode()).isZero();
      String combined = f.json() + f.markdown();
      if (previous != null) assertThat(combined).isEqualTo(previous);
      previous = combined;
    }
  }

  @Test
  void changedSourceAndInvalidRequestsPreservePreviousPackage() throws Exception {
    var f = new Fixture(temp);
    f.file("a", "original");
    f.select(100, "a");
    assertThat(f.pack().exitCode()).isZero();
    String md = f.markdown(), json = f.json();
    var result =
        new ContextPacker(TestReaders.changing("lastModifiedTime"))
            .pack(new PackRequest(f.manifest, f.output));
    assertThat(result.exitCode()).isEqualTo(4);
    assertThat(result.message()).contains("repo/a");
    assertThat(f.markdown()).isEqualTo(md);
    assertThat(f.json()).isEqualTo(json);
    f.select(100, "../outside");
    assertThat(f.pack().exitCode()).isEqualTo(2);
    assertThat(f.markdown()).isEqualTo(md);
    assertThat(f.json()).isEqualTo(json);
  }

  @Test
  void excludedAndIgnoredContentDoesNotReachReader() throws Exception {
    var f = new Fixture(temp);
    f.file(".gitignore", "ignored\n");
    f.file("ignored", "PRIVATE");
    f.file("excluded", "PRIVATE");
    f.select(100, "ignored", "excluded");
    f.append("exclusions:\n  - root: repo\n    path: excluded\n");
    var result =
        new ContextPacker(TestReaders.changing("size")).pack(new PackRequest(f.manifest, f.output));
    assertThat(result.exitCode()).isZero();
    assertThat(result.includedFiles()).isZero();
  }

  @Test
  void markdownMetadataCannotInjectMarkup() throws Exception {
    var f = new Fixture(temp);
    f.file("<img>`_[x].txt", "safe");
    f.select(100, "<img>`_[x].txt");
    assertThat(f.pack().exitCode()).isZero();
    assertThat(f.markdown()).doesNotContain("<img>", "`_[x]").contains("&#60;img&#62;");
  }
}
