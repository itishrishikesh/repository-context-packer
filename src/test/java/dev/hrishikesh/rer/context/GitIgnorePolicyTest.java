package dev.hrishikesh.rer.context;

import static org.assertj.core.api.Assertions.*;

import dev.hrishikesh.rer.context.policy.GitIgnorePolicy;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GitIgnorePolicyTest {
  @TempDir Path temp;

  @Test
  void ignoredRequiresPerSourceOptInAndExclusionsWin() throws Exception {
    var f = new Fixture(temp);
    f.file(".gitignore", "secret\nother\n");
    f.file("secret", "PRIVATE");
    f.file("other", "OTHER");
    f.select(100, "secret", "other");
    assertThat(f.pack().exitCode()).isZero();
    assertThat(f.markdown()).doesNotContain("PRIVATE", "OTHER");
    assertThat(f.json()).contains(".gitignore:1:secret");
    Files.writeString(
        f.manifest,
        Files.readString(f.manifest)
            .replace("path: 'secret'", "path: 'secret'\n    allowIgnored: true"));
    assertThat(f.pack().exitCode()).isZero();
    assertThat(f.markdown()).contains("PRIVATE", "Git ignore opt-in").doesNotContain("OTHER");
    assertThat(f.json()).contains("ignoreRule");
    f.append("exclusions:\n  - root: repo\n    path: secret\n");
    assertThat(f.pack().exitCode()).isZero();
    assertThat(f.markdown()).doesNotContain("PRIVATE");
  }

  @Test
  void trackedNestedAndNegatedRulesMatchGit() throws Exception {
    var f = new Fixture(temp);
    f.file("tracked.txt", "TRACKED");
    f.git("add", "--", "tracked.txt");
    f.file(".gitignore", "*.txt\n!keep.txt\n");
    f.file("keep.txt", "KEPT");
    f.file("nested/hidden.txt", "HIDDEN");
    f.file("nested/.gitignore", "!allowed.txt\n");
    f.file("nested/allowed.txt", "ALLOWED");
    f.select(100, "tracked.txt", "keep.txt", "nested/hidden.txt", "nested/allowed.txt");
    assertThat(f.pack().exitCode()).isZero();
    assertThat(f.markdown()).contains("TRACKED", "KEPT", "ALLOWED").doesNotContain("HIDDEN");
  }

  @Test
  void gitFailureIsNeverInterpretedAsAllowed() throws Exception {
    Path plain = Files.createDirectory(temp.resolve("plain"));
    assertThatThrownBy(() -> new GitIgnorePolicy().ignoredRule(plain, "a", "repo/a"))
        .isInstanceOfSatisfying(PackException.class, e -> assertThat(e.exitCode()).isEqualTo(3));
  }

  @Test
  void externalRuleDoesNotLeakAbsolutePath() throws Exception {
    var f = new Fixture(temp);
    Path rules = Files.writeString(temp.resolve("global-ignore"), "a\n");
    f.git("config", "core.excludesFile", rules.toString());
    f.file("a", "SECRET");
    f.select(100, "a");
    assertThat(f.pack().exitCode()).isZero();
    assertThat(f.json()).contains("external/global-ignore:1:a").doesNotContain(temp.toString());
  }
}
