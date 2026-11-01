package dev.hrishikesh.rer.context;

import dev.hrishikesh.rer.context.capture.*;
import dev.hrishikesh.rer.context.manifest.ManifestLoader;
import dev.hrishikesh.rer.context.output.*;
import dev.hrishikesh.rer.context.path.*;
import dev.hrishikesh.rer.context.planning.*;
import dev.hrishikesh.rer.context.policy.*;
import java.io.IOException;
import java.util.*;

public final class ContextPacker {
  private final StableFileReader reader;

  public ContextPacker() {
    this(new StableFileReader());
  }

  // Package-local integration seam to exercise capture failures through the public pipeline.
  ContextPacker(StableFileReader reader) {
    this.reader = reader;
  }

  public PackResult pack(PackRequest request) {
    if (request == null || request.manifest() == null || request.output() == null)
      return PackResult.failure(2, "Manifest and output are required");
    try {
      var manifest = new ManifestLoader().load(request.manifest());
      var paths = new PathValidator();
      var output = paths.output(request.output());
      var roots = paths.roots(manifest, output);
      var sources =
          manifest.sources().stream()
              .sorted(Comparator.comparing(s -> s.identity()))
              .map(s -> paths.source(s, roots.get(s.root())))
              .toList();
      var exclusions = new ExclusionPolicy(manifest, roots, output);
      var ignored = new GitIgnorePolicy();
      var omissions = new ArrayList<OmittedSource>();
      var ignoreRules = new HashMap<String, String>();
      var permitted = new ArrayList<ValidatedSource>();
      for (var source : sources) {
        if (exclusions.excludes(source)) {
          omissions.add(
              OmittedSource.policy(source.root(), source.path(), OmissionReason.EXCLUDED, null));
          continue;
        }
        var rule =
            ignored.ignoredRule(
                roots.get(source.root()).directory(), source.path(), source.identity());
        if (rule.isPresent() && !source.allowIgnored()) {
          omissions.add(
              OmittedSource.policy(
                  source.root(), source.path(), OmissionReason.GIT_IGNORED, rule.get()));
          continue;
        }
        rule.ifPresent(value -> ignoreRules.put(source.identity(), value));
        permitted.add(source);
      }
      var captured = new ArrayList<CapturedSource>();
      for (var source : permitted) {
        var content = reader.capture(source);
        if (content.isPresent())
          captured.add(content.get().withIgnoreRule(ignoreRules.get(source.identity())));
        else
          omissions.add(
              OmittedSource.policy(
                  source.root(),
                  source.path(),
                  OmissionReason.UNSUPPORTED_ENCODING,
                  ignoreRules.get(source.identity())));
      }
      var plan = new PackagePlanner().plan(manifest.byteBudget(), captured, omissions);
      String markdown = new MarkdownRenderer().render(plan);
      String json = new JsonManifestRenderer().render(plan);
      new OutputWriter().write(output, markdown, json);
      return new PackResult(
          0,
          "Created " + request.output().resolve("context.md"),
          plan.includedFiles(),
          plan.includedUniqueBytes(),
          plan.omittedFiles());
    } catch (PackException e) {
      return PackResult.failure(e.exitCode(), e.getMessage());
    } catch (IOException | SecurityException e) {
      return PackResult.failure(3, "Filesystem/output failure: " + e.getMessage());
    }
  }
}
