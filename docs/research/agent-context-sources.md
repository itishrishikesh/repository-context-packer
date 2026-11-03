# Primary-source findings for an agent-workflow pivot

Research date: 2026-11-03. These are vendor documentation and vendor-reported experiments, not an independent validation of `rer` or a guarantee of savings. Product documentation changes; integrations must record the installed agent version.

## Supported concept: context engineering and progressive disclosure

Anthropic describes context engineering as selecting and maintaining the information available throughout an agent loop. Its just-in-time approach keeps lightweight references such as paths and queries, then retrieves details when needed. Claude Code already combines initial `CLAUDE.md` instructions with filesystem search. Runtime exploration has latency and missed-discovery tradeoffs; the recommendation is the simplest approach that works, rather than exhaustive preloading. [Effective context engineering for AI agents, 2025-09-29](https://www.anthropic.com/research/effective-context-engineering-for-ai-agents)

More recent guidance reports removing over 80% of Claude Code's system prompt for newer models without measurable loss on Anthropic's coding evaluations. It recommends concise repository gotchas, task-specific skills, and splitting long instructions into references. This is evidence that excessive scaffolding can be counterproductive in that particular setup, not a target reduction percentage for arbitrary repositories. The same article says native `claude doctor` helps simplify skills and `CLAUDE.md`. [The new rules of context engineering, 2026-07-24](https://claude.dev/blog/the-new-rules-of-context-engineering-for-claude-5-generation-models/)

## Important native competition

Current Claude Code best practices explicitly document:

- `/init` creates initial repository instructions.
- `/doctor` suggests removing guidance inferable from the code.
- `/doctor prompt-audit` flags older-model instructions, nonexistent referenced files, and contradictions.
- `/context` can confirm instruction loading.
- Path-filtered `.claude/rules/` restrict instructions to relevant files.
- Auto-invocable skill names and descriptions are present each session; full bodies load when selected.

Therefore a generic instruction auditor, skill linter, or context doctor substantially overlaps the existing product. A pivot needs a more specific benefit. [Claude Code best practices](https://code.claude.com/docs/en/best-practices)

MCP tool search already defers definitions in current Claude Code. Tool names and server instructions remain in initial context; full definitions are retrieved when needed. Server authors can influence discovery with concise descriptions and instructions. Some deployments, including the documented Microsoft Foundry Azure case, reject tool search and fall back to upfront definitions. Do not assume every configured MCP schema is loaded in every user's session, or market another gateway as necessary for lazy loading. [Claude Code MCP reference, tool search](https://code.claude.com/docs/en/mcp#scale-with-mcp-tool-search)

The current cost guide documents native `/usage` reports and recommendations, `/context`, MCP management, and preference for existing CLIs where practical. It recommends moving specialized instructions from `CLAUDE.md` into on-demand skills and preprocessing verbose output. Subagents can isolate context, but still incur their own usage. Accordingly, counting skills or recommending more agents does not by itself establish savings. [Manage costs effectively](https://code.claude.com/docs/en/costs)

## API concepts that should not be confused with CLI capabilities

Anthropic's advanced-tool-use release describes deferred tool discovery and programmatic tool calling on the Claude Developer Platform. It reports an illustrative 85% reduction in tool-related token usage and improved tool-selection accuracy in internal MCP evaluations. This supports deferred discovery as an architectural pattern, but does not establish the same gain for a coding session whose native client already defers schemas. [Introducing advanced tool use, 2025-11-24](https://www.anthropic.com/research/advanced-tool-use)

The API's context-management documentation distinguishes four mechanisms: deferred schema discovery, programmatic processing of intermediate results, caching repeated prefixes, and removal of old tool-result history. Caching reduces repeated-token cost without reducing context occupancy. These mechanisms address different bottlenecks. Availability in an API is not proof that an external CLI can intercept or configure a vendor coding agent's internal prompt pipeline. [Manage tool context](https://platform.claude.com/docs/en/agents-and-tools/tool-use/manage-tool-context)

Anthropic's MCP code-execution article shows loading tools as code interfaces and filtering data before it reaches model context. Its example reduces 150,000 tokens to 2,000, but that is a particular architecture/example, not a universal benchmark. A local deterministic filter for logs or test results can adopt the principle without building a general MCP execution runtime. A full runtime would add credentials, execution isolation, error handling, and compatibility responsibilities. [Code execution with MCP, 2025-11-04](https://www.anthropic.com/research/code-execution-with-mcp)

## Product implications: hypotheses to test

These recommendations are inference from the sources, not vendor-endorsed product requirements:

1. **Cross-agent CI regression control is a plausible niche.** Produce reproducible, source-attributed changes in effective repository instruction/discovery overhead across Codex and Claude adapters. Track history and reviewable patches in Git. Explicitly compare against native `/doctor prompt-audit`, `/context`, and `/usage` before claiming differentiation.
2. **Distinguish disk inventory, predicted exposure, and observed exposure.** Installed skills, discovered metadata, selected skill bodies, configured MCP servers, deferred schemas, and actual session tokens are different measurements. Label missing telemetry; never turn all file bytes into a claimed context-token bill.
3. **Treat semantic findings as suggestions.** Exact duplicates and broken local paths can be deterministic findings. Contradictory instructions depend on scope, precedence, exceptions, and user intent. Automatic deletion could remove essential team knowledge.
4. **A task-specific repository navigator is an alternative experiment.** Start from a Git diff or failing test and return a small evidence map with paths, relevant tests, and freshness hashes. Measure whether it beats the agent's existing search, since Claude already performs just-in-time retrieval.
5. **Validate outcomes, not just smaller input.** On representative tasks compare native agent versus native agent plus `rer`: successful completion, human corrections, wall time, total reported usage including preparation, cache reads/writes, and inspection effort. Fewer input tokens with more incorrect changes is failure.

The strongest evidence supports the underlying pattern. Demand, willingness to install another tool, and a durable advantage over native features remain unproven.
