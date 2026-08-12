## Context

- `TeamcityCreateBuildChainCommand.createBuildChain()`
  (`src/main/kotlin/org/octopusden/octopus/automation/teamcity/TeamcityCreateBuildChainCommand.kt`)
  had this shape before this change:

  ```kotlin
  val defaultJDKVersion = client.getParameter(ConfigurationType.PROJECT, parentProjectId, "JDK_VERSION")
  component.buildParameters?.javaVersion?.takeIf { it != defaultJDKVersion }?.let { projectJDKVersion ->
      setBuildTypeParameter(compileConfig.id, "JDK_VERSION", projectJDKVersion)
  }
  ```
- `component.buildParameters` is `BuildParametersDTO` from `components-registry-service-core`,
  whose only Java-related field is `javaVersion: String?` — a plain string like `"1.8"` or
  `"11"`. There is no separate "java" field; the new `env.JAVA_HOME` logic reuses this same
  field.
- `SPLIT_SYMBOLS = "[,;]"` (`Application.kt:5`) is the existing convention for CLI options that
  accept multiple delimited values, already used by `TeamcityUpdateParameterCommand` for
  comma/semicolon-separated ID lists.
- `setBuildTypeParameter` and `setProjectParameter` were the two pre-existing helpers for
  writing TeamCity parameters, at build-type and project scope respectively. This change was
  the first caller needing `setProjectParameter` for something other than
  `COMPONENT_NAME`/`PROJECT_VERSION` — during implementation the two were merged into a single
  `setParameter(configurationType: ConfigurationType, id: String, name: String, value: String)`
  (they differed only in `ConfigurationType` and one word of log text), freeing a function slot
  needed to keep `resolveJavaHome` (Decision 9) as a class member without tripping detekt's
  `TooManyFunctions` limit. All call sites, including the pre-existing `JDK_VERSION` one, use
  the merged helper now.

## Worked example

Given `parentProjectId = "TeamOctopus"` with an existing project parameter
`env.JAVA_HOME = "%env.JDK_1_8%"` (the team's org-wide default), and a component whose registry
entry has `javaVersion = "17"`:

| Invocation | Resolution | Result |
|---|---|---|
| no `--java-home-mapping` | mapping empty → fall back to parent | `env.JAVA_HOME = "%env.JDK_1_8%"` (inherited default, ignores the component's actual `javaVersion`) |
| `--java-home-mapping=8=env.JDK_1_8,11=env.JDK_11_0` (overrides only, no leading template) | **invalid** — a leading template is required whenever the option is supplied at all | command fails before creating any project |
| `--java-home-mapping=env.JDK_{major}_0,8=env.JDK_1_8,11=env.JDK_11_0` | major `17` not an explicit key → leading template substituted | `env.JAVA_HOME = "%env.JDK_17_0%"` |
| `--java-home-mapping=env.JDK_{major}_0,17=env.JDK_17_CUSTOM` | major `17` explicit override wins over the template | `env.JAVA_HOME = "%env.JDK_17_CUSTOM%"` |
| `--java-home-mapping=8=env.JDK_1_8,env.JDK_{major}_0` | **invalid** — a bare entry is only recognized in position 0 | command fails before creating any project |

Takeaways:
- Row 1: absent the flag entirely, `env.JAVA_HOME` reflects the *parent project's* existing
  value, not the component's own `javaVersion` — mirroring how `JDK_VERSION`'s fallback already
  works today (`defaultJDKVersion` is also read from the parent, not derived from the component
  when absent).
- Row 2: once the option is supplied, it must always start with the template — there is no
  "overrides-only" form (see Decision 3).
- Row 5: a bare entry is a hard failure wherever it isn't in position 0, not a silent fallback
  or reordering (see Decision 2).
- **Not shown in the table**: if `parentProjectId` had no `env.JAVA_HOME` at all, row 1's result 
  would be `env.JAVA_HOME = ""` — still explicitly written on the new project, just empty, 
  never *un*written (see Decision 9).

## Goals / Non-Goals

**Goals:**
- Every team using this tool can get a correct `env.JAVA_HOME` reference for their build,
  regardless of what they've named their agent-side JDK parameters.
- Every component project this tool creates ends up with its own `env.JAVA_HOME` project
  parameter — present and directly editable in TeamCity, even if its value is empty — rather
  than one that's silently absent for some projects and present for others.
- JDK majors not yet known to this tool (e.g. a future 27) work automatically once a team has
  supplied a leading template in `--java-home-mapping`, without a code change here — the
  formula itself is caller data, not a constant in this codebase.
- No behavior change to `JDK_VERSION` — existing consumers of it are unaffected.

**Non-Goals:**
- This tool does not become the source of truth for which JDKs exist on which agents — it only
  ever writes a *reference* (`%env.JDK_x_y%`), never a literal path.
- No generic "arbitrary TeamCity parameter mapping" feature — this is scoped to
  `env.JAVA_HOME` specifically.
- No attempt to auto-discover or validate agent parameter names against a live TeamCity server.
- No enforcement that a parameter name look like a TeamCity environment-variable reference
  (e.g. no required `env.` prefix) — see Decision 5.

## Decisions

### 1. Override source is a CLI flag, not a TeamCity project parameter

- `--java-home-mapping` lives on the CLI invocation, keeping it visible and versioned next to
  the pipeline script that calls this tool, rather than as server-side TeamCity state.
- Lands in `TeamcityCreateBuildChainCommand`'s companion object as
  `JAVA_HOME_MAPPING = "--java-home-mapping"`.

### 2. Mapping format reuses `SPLIT_SYMBOLS`; overrides are `key=value`, the template is a bare leading entry

- Entries are comma/semicolon-separated (`SPLIT_SYMBOLS`), matching
  `TeamcityUpdateParameterCommand`'s existing ID-list option convention.
- The template entry is a bare value with no key (not `default=env.JDK_{major}_0`), and it
  must be the first segment: `--java-home-mapping=env.JDK_{major}_0,8=env.JDK_1_8,11=env.JDK_11_0`.
- A bare entry at any position other than 0 (e.g.
  `--java-home-mapping=8=env.JDK_1_8,env.JDK_{major}_0`) is a validation error — position is
  the only signal that distinguishes the template from a malformed override.

### 3. The leading template is required whenever the option is supplied

- `--java-home-mapping=8=env.JDK_1_8,11=env.JDK_11_0` (no leading template) is rejected
  outright — there is no "overrides only, no formula" form.
- Omitting the whole option is unaffected: no flag at all means no mapping, which is the
  parent-project-fallback path (Decision 9).

### 4. The fallback formula is caller-supplied data (the leading bare entry), not a constant in this codebase

- `--java-home-mapping` requires the leading bare entry (Decision 3), whose value is a template
  containing the placeholder `{major}` (e.g. `env.JDK_{major}_0`), substituted at resolution
  time.
- There is no formula and no built-in exception anywhere in the code — a team that wants an
  `8 -> env.JDK_1_8` mapping supplies it as an explicit override alongside their own template,
  exactly like any other override.
- If resolution has nothing to go on for a given major (only possible when the whole option
  was omitted — Decision 3), it falls through to the parent-project fallback (Decision 9).

### 5. Override/template values are not required to look like `env.<NAME>`

- No entry is required to start with `env.` or any other fixed prefix — this tool can't verify
  a value corresponds to a real TeamCity agent parameter regardless of prefix.
- What's validated instead, because these are structural mistakes this tool *can* detect with
  certainty:
  - a value must be non-blank;
  - an override value must not already be `%`-wrapped (this tool wraps the value itself);
  - an override value must not contain the `{major}` placeholder (only the template
    substitutes it);
  - the template must contain **exactly one** `{major}` occurrence.

### 6. Placeholder syntax is `{major}`, not the codebase's existing `{}` SLF4J-style convention

- This file already uses `{}` as a placeholder in log messages (e.g.
  `log.warn("Skip disable build step '{}' not found for build type {}", ...)`), but that
  convention is for messages a developer reads in a log, not a string a caller writes into a
  CLI flag.
- `{major}` is self-documenting for a public-facing option — a caller reading
  `--java-home-mapping=env.JDK_{major}_0,...` for the first time doesn't need this design doc
  to understand what gets substituted.

### 7. Major version is derived from `javaVersion`'s trailing segment

- `javaVersion` values from the registry come in two shapes seen in existing fixtures:
  dotted legacy form (`"1.8"`) and bare modern form (`"11"`, `"17"`, `"21"`, `"25"`).
- Both shapes resolve to the same major-version integer used for override lookup and template
  substitution: take the substring after the last `.`, or the whole string if there is no `.`,
  then parse as an integer.
- Examples: `"1.8"` → `8`; `"8"` → `8`; `"11"` → `11`; `"17"` → `17`.
- This is why `8` needs no built-in exception (Decision 4) — `"1.8"` and an override keyed
  `8=...` already refer to the same major through this extraction rule; the *naming* exception
  (`env.JDK_1_8` instead of `env.JDK_8_0`) is what still needs an explicit caller-supplied
  override, not the version-matching itself.

### 8. Eager validation of every mapping entry, not lazy failure at `setParameter` time

- A malformed entry — the option supplied without a leading template, a bare entry anywhere
  other than position 0, a non-numeric override key, an already-`%`-wrapped value, a value
  containing `{major}` where it shouldn't, a template missing (or containing more than one)
  `{major}` placeholder, or a duplicate override key — is rejected at option-parse time with a
  message naming the specific bad entry, before any TeamCity project is created.

### 9. `env.JAVA_HOME` is always written: mapping resolution, else the parent's value, else an empty string

- `setParameter(ConfigurationType.PROJECT, project.id, "env.JAVA_HOME", resolveJavaHome(...))`
  is called exactly once per `createBuildChain` run, with no guard. `resolveJavaHome` is a
  private member of `TeamcityCreateBuildChainCommand` returning, in order: the mapping's
  resolution wrapped as `%...%` (Decision 4), the parent's existing `env.JAVA_HOME`
  (`client.getParameter(PROJECT, parentProjectId, "env.JAVA_HOME")` — the same lookup pattern
  `JDK_VERSION`'s default already uses, used as-is, not re-wrapped), or `""`.
- `client.getParameter` throws `FeignException.NotFound` rather than returning `null` when a
  parameter doesn't exist on a project. No project has `env.JAVA_HOME` until a team sets one, so
  the parent-fallback lookup catches `FeignException.NotFound` and treats it as an empty value.
- Scope: every project this tool creates ends up with the `env.JAVA_HOME` parameter present,
  ready to be filled in directly in TeamCity if nothing else supplied a value. See Risks below
  for the trade-off this implies for an already-working parent-level setup.

### 10. Written at project level, not build-type level, and unconditionally

- `JDK_VERSION` is only ever set on the compile build-type (`compileConfig.id`), and only when
  it differs from the project default. `env.JAVA_HOME` is instead set on the newly created
  component project (`project.id`), so it's inherited by every build config under that project
  (compile, RC, checklist, release) — not just the compile step, since release/RC builds can
  also need a JDK on the agent.
- Unlike `JDK_VERSION`'s conditional write, `env.JAVA_HOME` is written every time, with no
  "only if different" or "only if non-empty" guard (Decision 9).

### 11. `JDK_VERSION` is deprecated but not removed in this change

- Both parameters are set side by side.
- The removal condition is tracked as
  [`docs/tech-debt/TD-001-jdk-version-param-removal.md`](../../../docs/tech-debt/TD-001-jdk-version-param-removal.md)
  (this repo's first tech-debt record) rather than only here — a `TD-NNN` file survives this
  change folder being archived, so the removal condition stays discoverable long after
  OCTOPUS-2473 itself is history. The code points at the same file via a `TD-001:` comment on
  the `JDK_VERSION`-setting block.
- No specific date — this is consumer-migration-gated, not time-gated.

## Out of Scope

- Any change to how `JDK_VERSION` itself is computed or set (Decision 11) — deferred until
  consumer migration is confirmed.
- Reading the mapping from a TeamCity project parameter (Decision 1) — CLI flag only.
- A hardcoded fallback formula or built-in per-major exceptions (Decision 4) — the leading bare
  template entry is the only formula mechanism, and it is entirely caller-supplied.
- Enforcing a required naming prefix (e.g. `env.`) on override or template values (Decision 5).

## Risks / Trade-offs

- **Silent overwrite of an existing `env.JAVA_HOME`, possibly with an empty string.**
  - If a team already hand-configured `env.JAVA_HOME` on a component's project directly (not
    on the parent), the next `create-build-chain` run without `--java-home-mapping` for that
    component will read the *parent's* value (or fall through to `""`) and overwrite the
    child project's existing value — including blanking it out entirely if the parent has
    nothing configured either.
  - Accepted because this write always happens at project-creation time for a
    project this tool itself just created — there's no established prior art of a human
    hand-editing a freshly-created child project's parameters before this tool finishes, so
    the realistic risk is a *re-run* of `create-build-chain` against an existing project,
    which is not this command's documented use case today.
  - Called out explicitly in `proposal.md`'s Affected areas so it's visible to reviewers
    rather than discovered later.
- **Parent-value-wins-over-actual-`javaVersion` when the option is omitted** (Decision 9).
  - A component with `javaVersion = "17"` under a parent whose `env.JAVA_HOME` defaults to JDK
    8 gets JDK 8's reference, not JDK 17's, unless the caller passes `--java-home-mapping` at
    all (once passed, Decision 3 guarantees a resolution via the required template or an
    explicit override).
  - This mirrors `JDK_VERSION`'s existing fallback semantics exactly, so it's consistent with
    prior behavior in this file, but it means the new parameter alone doesn't guarantee
    "correct JDK for this component" — passing the mapping does.
  - Worth flagging to a new user of this flag: not passing `--java-home-mapping` at all
    silently falls back to the parent for every component, which can look like "the feature
    isn't doing anything" if not understood.
