package dev.hrishikesh.rer.context.manifest;

import static dev.hrishikesh.rer.context.manifest.InputManifest.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.hrishikesh.rer.context.PackException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ManifestLoader {
  private final ObjectMapper yaml =
      new ObjectMapper(new YAMLFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));

  public InputManifest load(Path file) {
    try (var input = Files.newInputStream(file);
        var parser = yaml.createParser(input)) {
      JsonNode doc = yaml.readTree(parser);
      if (parser.nextToken() != null) invalid("multiple YAML documents are unsupported");
      object(doc, Set.of("version", "byteBudget", "roots", "sources", "exclusions"));
      if (integer(doc.get("version"), "version") != 1) invalid("unsupported version (expected 1)");
      long budget = integer(doc.get("byteBudget"), "byteBudget");
      if (budget <= 0) invalid("byteBudget must be positive");
      var roots = new ArrayList<RootDeclaration>();
      var names = new HashSet<String>();
      for (JsonNode node : array(doc.get("roots"), "roots")) {
        object(node, Set.of("name", "path"));
        String name = string(node.get("name"), "root name");
        if (!name.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*") || !names.add(name))
          invalid("invalid or duplicate root: " + name);
        Path rootPath = Path.of(string(node.get("path"), "root path"));
        roots.add(
            new RootDeclaration(
                name, file.toAbsolutePath().getParent().resolve(rootPath).normalize()));
      }
      if (roots.isEmpty()) invalid("roots must not be empty");
      var sources = new ArrayList<SourceDeclaration>();
      var identities = new HashSet<String>();
      for (JsonNode node : array(doc.get("sources"), "sources")) {
        object(node, Set.of("root", "path", "allowIgnored"));
        String root = reference(node, names);
        String path = relative(string(node.get("path"), "source path"));
        JsonNode opt = node.get("allowIgnored");
        if (opt != null && !opt.isBoolean()) invalid("allowIgnored must be a boolean");
        var source = new SourceDeclaration(root, path, opt != null && opt.booleanValue());
        if (!identities.add(source.identity())) invalid("duplicate source: " + source.identity());
        sources.add(source);
      }
      var exclusions = new ArrayList<ExclusionDeclaration>();
      if (doc.has("exclusions"))
        for (JsonNode node : array(doc.get("exclusions"), "exclusions")) {
          object(node, Set.of("root", "path"));
          exclusions.add(
              new ExclusionDeclaration(
                  reference(node, names), relative(string(node.get("path"), "exclusion path"))));
        }
      return new InputManifest(
          1, budget, List.copyOf(roots), List.copyOf(sources), List.copyOf(exclusions));
    } catch (com.fasterxml.jackson.core.JacksonException | InvalidPathException e) {
      throw new PackException(2, "Invalid manifest: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new PackException(3, "Cannot read manifest: " + e.getMessage(), e);
    }
  }

  private static String reference(JsonNode node, Set<String> names) {
    String root = string(node.get("root"), "root reference");
    if (!names.contains(root)) invalid("unknown root: " + root);
    return root;
  }

  public static String relative(String value) {
    String normalized = value.replace('\\', '/');
    if (normalized.startsWith("/")
        || normalized.matches("^[A-Za-z]:.*")
        || value.chars().anyMatch(Character::isISOControl))
      invalid("unsafe relative path: " + value);
    var parts = new ArrayList<String>();
    for (String part : normalized.split("/")) {
      if (part.equals("..")) invalid("traversal path: " + value);
      if (!part.isEmpty() && !part.equals(".")) parts.add(part);
    }
    if (parts.isEmpty()) invalid("path must select a file or subtree: " + value);
    return String.join("/", parts);
  }

  private static void object(JsonNode node, Set<String> allowed) {
    if (node == null || !node.isObject()) invalid("expected mapping");
    node.fieldNames()
        .forEachRemaining(
            key -> {
              if (!allowed.contains(key)) invalid("unknown field: " + key);
            });
  }

  private static JsonNode array(JsonNode node, String field) {
    if (node == null || !node.isArray()) invalid(field + " must be a list");
    return node;
  }

  private static long integer(JsonNode node, String field) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong())
      invalid(field + " must be an integer");
    return node.longValue();
  }

  private static String string(JsonNode node, String field) {
    if (node == null || !node.isTextual() || node.textValue().isBlank())
      invalid(field + " must be a nonempty string");
    return node.textValue();
  }

  private static void invalid(String message) {
    throw new PackException(2, "Invalid manifest: " + message);
  }
}
