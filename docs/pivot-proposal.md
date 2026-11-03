# Pivot proposal: regression testing for agent context

Research date: 2026-11-03. Status: recommendation, not an implemented feature or validated market claim. Existing packaging commands remain the current product.

## Recommendation

Turn `rer` into a local tool that answers: **“Did this change to our agent setup make engineering work better or worse?”**

Start with engineers maintaining repository instructions and shared skills. Compare a known configuration with a proposed change using the same engineering tasks, then report correctness, skill/tool selection, tokens, and elapsed time. Make the first static inspection work from the repository without a manifest. Add repeatable behavioral comparisons as the valuable paid-in-effort step.

This is a narrower audience than every Claude/Codex user. Someone using an agent occasionally may be better served by native commands. The product hypothesis is that people maintaining a shared setup need a repeatable, reviewable regression check, rather than another setup guide.

## What the vendors establish

These sources establish engineering patterns. They do not establish demand for this particular product or guarantee savings.

| Pattern | Primary evidence | Product implication |
| --- | --- | --- |
| Evaluate skills against tasks | OpenAI describes prompt → captured trace/artifacts → checks → comparable scores, including activation, correctness, and efficiency. [Testing Agent Skills Systematically with Evals](https://developers.openai.com/blog/eval-skills) | Turn configuration changes into experiments with observable outcomes. |
| Progressive disclosure | Codex initially exposes skill metadata, loading full instructions when selected. Large catalogs can have descriptions shortened or entries omitted. [Build skills](https://learn.chatgpt.com/docs/build-skills) | Installed skill count and total skill-body size are poor proxies for actual startup context. |
| Keep instructions specific | OpenAI warns about broad skill triggers, competing guidance, and unconditional reading requirements. [Rethinking skills and prompts](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra) | Test adjacent prompts that should *not* activate a workflow, as well as positive cases. |
| Retrieve context when needed | Anthropic advocates lightweight references and just-in-time retrieval; Claude Code already uses agentic file search. [Effective context engineering](https://www.anthropic.com/research/effective-context-engineering-for-ai-agents) | Replacing explicit YAML with a giant automatic repository dump is a weak pivot. |
| Discover tools on demand | OpenAI documents deferred tool loading in its APIs. [Tool search](https://developers.openai.com/api/docs/guides/tools-tool-search) | API features are evidence for the pattern, not proof an external CLI can control a native agent's context. |

## Native overlap changes the opportunity

Claude Code already provides instruction cleanup through `/doctor` and `/doctor prompt-audit`. Its MCP documentation describes default tool deferral with model, configuration, and deployment exceptions. A generic instruction linter or lazy MCP gateway would overlap substantially with those features. [Best practices](https://code.claude.com/docs/en/best-practices), [MCP tool search](https://code.claude.com/docs/en/mcp#scale-with-mcp-tool-search).

The proposed differentiation is a versioned comparison across Git changes and supported agent clients, with task checks and retained evidence. Native diagnostics should be a baseline or input. This research is not a comprehensive competitive survey; it does not establish that no other product offers this workflow.

## The engineering workflow

Illustrative commands below describe the proposed interface; none exists today.

```sh
# Immediate local inspection; no source-file list or YAML.
rer inspect

# Save a task and its explicit success check once.
rer case add exporter-fix --prompt-file task.md --check 'mvn -q verify'

# Compare a configuration change against the base branch.
rer compare --base main --agent codex --case exporter-fix
```

1. **Inspect:** discover repository-owned instructions, skill metadata, references, and supported configuration files. Show what changed, source locations, exact duplicates, and resolvable broken links. Distinguish facts from suggestions. Candidate semantic conflicts require review.
2. **Capture a real task:** use a supplied prompt or an explicitly selected successful run where supported. Ask the engineer to confirm what success means. Do not require selecting source files. A generated case file is reviewable and versioned; behavioral evaluation cannot honestly be zero-configuration.
3. **Compare:** run baseline and candidate in separate disposable environments using the same source fixture, prompt, checks, agent version, model, effort, permissions, and tool availability. Initially change only instructions/skills; mixing code and configuration changes confounds attribution.
4. **Report:** show the outcome first, then overhead, selected workflows, and links to supporting traces/artifacts. Export Markdown and JSON for local review and later CI.

Example report, **invented to illustrate the intended output**:

```text
Change: broadened database skill description
Task: update an exporter; no schema change requested
Correctness: base PASS / candidate PASS
Migration workflow activated: base no / candidate yes
Input tokens: base 18,400 / candidate 26,100
Finding: inspect the newly broadened skill trigger
Evidence: source diff + observed skill read + run trace
```

A skill read is evidence of loading, not proof that it caused every later action. Report associations and controlled comparisons; avoid unsupported causal claims.

## Keep the first release small

Build a local CLI with two stages:

- **Static entry point:** repository discovery and Git comparison, hashes, attribution, duplicate detection, and reference checks. This must provide a useful result without agent execution or an API key.
- **Behavioral differentiator:** one native-agent adapter, a handful of real task cases, isolated baseline/candidate runs, deterministic checks, and comparable reports. Start with Codex because OpenAI documents structured `codex exec --json` traces and usage fields in its eval example; verify the installed client's schema before implementation. Add Claude after the report model works.

Support repository-owned configuration first. Personal or organization-managed setup is a separately identified input; without it, mark effective configuration coverage as partial. Never present the repository scan as a complete account of an agent session.

Do not start with a dashboard, vector database, automatic skill rewriting, MCP proxy, live context interception, or full task orchestrator. MCP schema size and runtime response volume require observed evidence; configuration alone cannot reveal them. Running an MCP server can execute code, so static inspection should not launch servers.

## What survives from this codebase

The current implementation is a reusable evidence foundation, not a finished evaluator:

| Existing component | Reuse or change |
| --- | --- |
| `PathValidator`, exclusion and Git-ignore policies | Reuse boundary principles; add explicit discovery scopes. Git ignore is not secret detection. |
| `StableFileReader`, hashes and line references | Attribute exactly which instruction/configuration bytes were inspected. |
| Deterministic rendering and output publication | Reuse for static snapshots and reports; runtime results naturally contain variable timing and usage. |
| `ManifestLoader` and explicit `sources` | Keep legacy export compatibility; remove from the new default user path. |
| `PackagePlanner` | Whole-file allocation in lexical order does not model relevance, token usage, skill activation, or effective instruction scope. It needs a different reporting/planning layer. |
| Java 21/Picocli CLI | Keep initially; a language rewrite would not validate the product hypothesis. |

New substantial work includes client-specific configuration resolution, versioned trace adapters, controlled execution, case checks, and comparison reporting. This is more than making the manifest optional.

## Measurement and validation

Static checks and behavioral checks are different products of the same report. Static findings can be exact; savings and correctness require runs.

- Record input, cached input where exposed, output, and other usage categories independently. Do not equate bytes, estimated tokens, context occupancy, cumulative billed tokens, or subscription charges.
- Label offline estimates with their tokenizer/method and coverage. Mark unobservable values unknown.
- Prefer existing tests and observable artifacts for correctness. A successful agent exit or self-reported success is insufficient. A command log alone does not prove a verification command passed.
- Use positive and negative workflow-selection cases. Retain the source fixture, configuration fingerprints, agent/model versions, traces, and grader results.
- Repeat paired runs and vary their order. Control or disclose cache state, external tool data, and unavailable configuration. Compare each agent against its own baseline before making cross-agent claims.
- Make regressions advisory initially. Establish normal variation before enforcing efficiency thresholds in CI.

Suggested validation experiment: recruit three engineers who maintain shared setups, collect 10–20 representative tasks across their repositories, and compare current setup, native cleanup, and one proposed simplification. These sample sizes are exploratory, not statistical proof. Include simple edits where extra skills should stay inactive and complex changes where they help.

Proceed if the tool repeatedly identifies actionable regressions beyond native diagnostics and maintaining cases costs less effort than it saves. If native commands provide the same decisions, or smaller context consistently hurts task results, stop or change direction. No percentage savings claim is justified yet.

## Other pivots considered

| Option | Assessment |
| --- | --- |
| Automatic task-context packer | Closest to current implementation. Could use task text and Git changes to return bounded references/excerpts, but must outperform native search. Keep as an experiment, not the headline. |
| Context doctor / token dashboard | Useful onboarding feature; substantial native overlap and weak evidence of engineering benefit by itself. |
| MCP execution/filtering gateway | Relevant context-engineering pattern, but duplicates deferral and introduces server execution, credentials, and runtime complexity. Poor first pivot for this repository. |
| Agent context regression testing | Recommended hypothesis: directly tests whether the team's growing setup earns its cost. Requires real checks and modest one-time case setup. |

Additional Anthropic source notes: [agent-context-sources.md](research/agent-context-sources.md).
