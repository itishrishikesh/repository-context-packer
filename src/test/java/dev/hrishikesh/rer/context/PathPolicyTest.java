package dev.hrishikesh.rer.context;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PathPolicyTest {
  @TempDir Path temp;

  @Test
  void fixturesCanonicalizePlatformTemporaryDirectoryAliases() throws Exception {
    Path real = Files.createDirectory(temp.resolve("physical"));
    Path alias = Files.createSymbolicLink(temp.resolve("platform-temp-alias"), real);
    var fixture = new Fixture(alias);
    fixture.file("a", "text");
    fixture.select(100, "a");
    var result = fixture.pack();
    assertThat(result.exitCode()).as(result.message()).isZero();
    assertThat(fixture.output.getParent()).isEqualTo(real.toRealPath());
  }

  @Test
  void rejectsFileAndAncestorSymlinksAndOutputSymlinks() throws Exception {
    var f = new Fixture(temp);
    f.file("real/a", "text");
    Files.createSymbolicLink(f.repo.resolve("link"), Path.of("real"));
    Files.createSymbolicLink(f.repo.resolve("alias"), Path.of("real/a"));
    for (String path : new String[] {"link/a", "alias"}) {
      f.select(100, path);
      assertThat(f.pack().exitCode()).isEqualTo(2);
    }
    f.select(100, "real/a");
    Files.createSymbolicLink(f.output, f.repo.resolve("real"));
    assertThat(f.pack().exitCode()).isEqualTo(2);
    assertThat(
            new ContextPacker()
                .pack(new PackRequest(f.manifest, f.output.resolve("sub")))
                .exitCode())
        .isEqualTo(2);
  }

  @Test
  void missingNonRegularAndNonGitFail() throws Exception {
    var f = new Fixture(temp);
    f.file("dir/a", "a");
    for (String path : new String[] {"missing", "dir"}) {
      f.select(100, path);
      assertThat(f.pack().exitCode()).isEqualTo(3);
    }
    f.select(100, "a");
    Files.createDirectories(temp.resolve("plain"));
    Files.writeString(
        f.manifest, Files.readString(f.manifest).replace("path: repo", "path: plain"));
    assertThat(f.pack().exitCode()).isEqualTo(3);
  }

  @Test
  void exclusionsUseComponentPrefixesAndApplyAcrossOverlappingRoots() throws Exception {
    var f = new Fixture(temp);
    f.file("target/a", "SECRET");
    f.file("targeted/a", "safe");
    f.select(100, "target/a", "targeted/a");
    f.append("exclusions:\n  - root: repo\n    path: target\n");
    assertThat(f.pack().exitCode()).isZero();
    assertThat(f.markdown()).doesNotContain("SECRET").contains("safe");
    Files.writeString(
        f.manifest,
        Files.readString(f.manifest)
            .replace("sources:", "  - name: nested\n    path: repo/target\nsources:")
            .replace("  - root: repo\n    path: targeted/a", "  - root: nested\n    path: a"));
    assertThat(f.pack().exitCode()).isZero();
    assertThat(f.json()).contains("EXCLUDED");
    assertThat(f.markdown()).doesNotContain("SECRET");
  }

  @Test
  void gitMetadataAndOutputAreAlwaysExcluded() throws Exception {
    var f = new Fixture(temp);
    f.file("build/context.md", "SECRET");
    f.file("build/manifest.json", "{}");
    f.select(10000, ".git/config", "build/context.md");
    var result = new ContextPacker().pack(new PackRequest(f.manifest, f.repo.resolve("build")));
    assertThat(result.exitCode()).isZero();
    assertThat(Files.readString(f.repo.resolve("build/context.md")))
        .doesNotContain("SECRET")
        .contains("EXCLUDED");
  }

  @Test
  void rootsCannotBeInsideOutputOrOutputInsideGitMetadata() throws Exception {
    var f = new Fixture(temp);
    f.file("a", "a");
    f.select(100, "a");
    assertThat(new ContextPacker().pack(new PackRequest(f.manifest, temp)).exitCode()).isEqualTo(2);
    assertThat(
            new ContextPacker()
                .pack(new PackRequest(f.manifest, f.repo.resolve(".git/package")))
                .exitCode())
        .isEqualTo(2);
  }

  @Test
  void resolvedExclusionsApplyThroughSymlinkAlias() throws Exception {
    var f = new Fixture(temp);
    f.file("real/a", "SECRET");
    Files.createSymbolicLink(f.repo.resolve("alias"), Path.of("real"));
    f.select(100, "real/a");
    f.append("exclusions:\n  - root: repo\n    path: alias\n");
    assertThat(f.pack().exitCode()).isZero();
    assertThat(f.markdown()).doesNotContain("SECRET");
  }
}
