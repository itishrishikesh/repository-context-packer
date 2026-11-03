package dev.hrishikesh.rer.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class AgentAdapter {
  enum Agent {
    codex,
    claude
  }

  record Trace(
      boolean complete,
      boolean failed,
      Long inputTokens,
      Long cachedInputTokens,
      Long cacheWriteInputTokens,
      Long outputTokens,
      int commands,
      List<String> tools,
      List<String> models,
      int malformedLines) {}

  static List<String> command(Agent agent, String model) {
    var args = new ArrayList<String>();
    if (agent == Agent.codex) {
      Collections.addAll(
          args,
          "codex",
          "--ask-for-approval",
          "never",
          "exec",
          "--json",
          "--ephemeral",
          "--sandbox",
          "workspace-write",
          "--model",
          model,
          "-");
    } else {
      Collections.addAll(
          args,
          "claude",
          "--print",
          "--verbose",
          "--output-format",
          "stream-json",
          "--no-session-persistence",
          "--permission-mode",
          "acceptEdits",
          "--model",
          model);
    }
    return List.copyOf(args);
  }

  static Trace parse(Agent agent, Path file) throws IOException {
    boolean complete = false, failed = false;
    Long input = null, cached = null, written = null, output = null;
    int commands = 0, malformed = 0;
    boolean inputComplete = true,
        cachedComplete = true,
        writtenComplete = true,
        outputComplete = true;
    var tools = new TreeSet<String>();
    var models = new TreeSet<String>();
    try (var reader = Files.newBufferedReader(file)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        JsonNode node;
        try {
          node = LocalStore.JSON.readTree(line);
        } catch (IOException e) {
          malformed++;
          continue;
        }
        if (node == null) {
          malformed++;
          continue;
        }
        String type = node.path("type").asText();
        if (agent == Agent.codex) {
          if (type.equals("turn.failed") || type.equals("error")) failed = true;
          if (type.equals("turn.completed")) {
            complete = true;
            inputComplete &= valid(node.path("usage").get("input_tokens"));
            cachedComplete &= valid(node.path("usage").get("cached_input_tokens"));
            outputComplete &= valid(node.path("usage").get("output_tokens"));
            input = add(input, node.path("usage").get("input_tokens"));
            cached = add(cached, node.path("usage").get("cached_input_tokens"));
            output = add(output, node.path("usage").get("output_tokens"));
          }
          if (type.equals("item.completed")) {
            var item = node.path("item");
            String itemType = item.path("type").asText();
            if (itemType.equals("command_execution")) {
              commands++;
              tools.add("shell");
            }
            if (itemType.equals("mcp_tool_call"))
              tools.add(
                  "mcp:"
                      + item.path("server").asText("unknown")
                      + "/"
                      + item.path("tool").asText("unknown"));
          }
        } else {
          if (type.equals("result")) {
            complete = true;
            inputComplete &= valid(node.path("usage").get("input_tokens"));
            cachedComplete &= valid(node.path("usage").get("cache_read_input_tokens"));
            writtenComplete &= valid(node.path("usage").get("cache_creation_input_tokens"));
            outputComplete &= valid(node.path("usage").get("output_tokens"));
            failed |=
                node.path("is_error").asBoolean()
                    || !node.path("subtype").asText().equals("success");
            input = add(input, node.path("usage").get("input_tokens"));
            cached = add(cached, node.path("usage").get("cache_read_input_tokens"));
            written = add(written, node.path("usage").get("cache_creation_input_tokens"));
            output = add(output, node.path("usage").get("output_tokens"));
            node.path("modelUsage").fieldNames().forEachRemaining(models::add);
          }
          if (type.equals("assistant")) {
            String model = node.path("message").path("model").asText();
            if (!model.isBlank()) models.add(model);
            for (var block : node.path("message").path("content")) {
              if (block.path("type").asText().equals("tool_use")) {
                String name = block.path("name").asText();
                tools.add(name);
                if (name.equals("Bash")) commands++;
              }
            }
          }
        }
      }
    }
    return new Trace(
        complete,
        failed,
        inputComplete ? input : null,
        cachedComplete ? cached : null,
        writtenComplete ? written : null,
        outputComplete ? output : null,
        commands,
        List.copyOf(tools),
        List.copyOf(models),
        malformed);
  }

  private static boolean valid(JsonNode value) {
    return value != null
        && value.isIntegralNumber()
        && value.canConvertToLong()
        && value.longValue() >= 0;
  }

  private static Long add(Long current, JsonNode value) {
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0) return current;
    return (current == null ? 0 : current) + value.longValue();
  }
}
