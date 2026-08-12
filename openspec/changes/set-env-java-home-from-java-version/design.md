## Context

- `TeamcityCreateBuildChainCommand.createBuildChain()`
  (`src/main/kotlin/org/octopusden/octopus/automation/teamcity/TeamcityCreateBuildChainCommand.kt:107-110`)
  already has the shape this change extends:

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
- `setBuildTypeParameter` and `setProjectParameter`
  (`TeamcityCreateBuildChainCommand.kt:220-234`) are the two existing helpers for writing
  TeamCity parameters, at build-type and project scope respectively; this change is the first
  caller of `setProjectParameter` for something other than
  `COMPONENT_NAME`/`PROJECT_VERSION`.

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

## Goals / Non-Goals

**Goals:**
- Every team using this tool can get a correct `env.JAVA_HOME` reference for their build,
  regardless of what they've named their agent-side JDK parameters.
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

- Considered reading the mapping from a TeamCity project parameter (mirroring how
  `defaultJDKVersion` is read), which would let teams edit it in the TeamCity UI without
  touching pipeline scripts.
- Rejected because the CLI flag was the user's explicit choice: it keeps the mapping visible
  and versioned next to the pipeline script that invokes this tool, rather than living as
  server-side state that's easy to forget exists.
- Lands in `TeamcityCreateBuildChainCommand`'s companion object as
  `JAVA_HOME_MAPPING = "--java-home-mapping"`.

### 2. Mapping format reuses `SPLIT_SYMBOLS`; overrides are `key=value`, the template is a bare leading entry

- Considered a JSON object string for more structure, but there is no existing
  JSON-parsing-from-CLI-string precedent anywhere in this repo, so it would be new machinery
  for a handful of entries.
- `key=value` pairs split on `SPLIT_SYMBOLS` matches the exact idiom
  `TeamcityUpdateParameterCommand` already uses for its ID-list options, so a reader of this
  file already knows the convention for the override entries.
- The template entry is deliberately *not* another `key=value` pair (e.g. not
  `default=env.JDK_{major}_0`) — it's a bare value with no key at all, and it must be the
  first segment in the option value:
  `--java-home-mapping=env.JDK_{major}_0,8=env.JDK_1_8,11=env.JDK_11_0`.
- A bare entry appearing anywhere other than position 0 (e.g.
  `--java-home-mapping=8=env.JDK_1_8,env.JDK_{major}_0`) is a validation error, not silently
  accepted or reordered — position is the only signal that distinguishes "this is the
  template" from "this is a malformed override missing its key", so the parser must be strict
  about it rather than guessing intent.

### 3. The leading template is required whenever the option is supplied

- Considered making the template optional (an overrides-only mapping would then just leave
  every uncovered major to the parent-project fallback).
- Overridden after review: a caller who bothers to pass `--java-home-mapping` almost certainly
  wants a real answer for majors they didn't think to list, not a silent, easy-to-miss fallback
  to whatever the parent project happens to have. Requiring the template up front forces that
  choice to be explicit at the call site instead of discovered later as an unexpected `%env.JDK_1_8%`
  inherited from the parent.
- `--java-home-mapping=8=env.JDK_1_8,11=env.JDK_11_0` (no leading template) is therefore
  rejected outright, not treated as "overrides only, no formula" — see the spec's mandatory-template
  requirement.
- Omitting the whole option is still fine and unaffected by this decision: no flag at all means
  no mapping, which is the existing parent-project-fallback path (Decision 6).

### 4. The fallback formula is caller-supplied data (the leading bare entry), not a constant in this codebase

- An earlier version of this design had `env.JDK_{major}_0` (plus a built-in
  `8 -> env.JDK_1_8` exception) hardcoded in `JavaHomeMapping`, applied whenever an explicit
  override was missing.
- Overridden after review: the whole point of making the mapping overridable is that no naming
  scheme this tool bakes in is guaranteed to hold for every team or every future JDK release —
  a hardcoded formula is exactly the same class of problem as a hardcoded map, just one level
  more abstract.
- Instead, `--java-home-mapping` requires the leading bare entry (Decision 3) whose value is a
  template containing the placeholder `{major}` (e.g. `env.JDK_{major}_0`), substituted at
  resolution time.
- There is now no formula and no built-in exception anywhere in the code — a team that wants
  the `8 -> env.JDK_1_8` behavior supplies it as an explicit override alongside their own
  template, exactly like any other override.
- If resolution has nothing to go on for a given major (this only happens when the whole
  option was omitted — see Decision 3), it falls through to the parent-project fallback
  (Decision 6) rather than guessing.

### 5. Override/template values are not required to look like `env.<NAME>`

- An earlier version of this design required every value (override and template) to match
  `^env\.[A-Za-z0-9_]+$`, rejecting anything not literally prefixed `env.`.
- Overridden after review: this tool has no way to verify a value actually corresponds to a
  real TeamCity agent parameter regardless of prefix, so a prefix check only catches one
  specific typo shape while rejecting otherwise-valid names a team might already be using for
  a different kind of parameter. Requiring `env.` added a rule this tool can't meaningfully
  enforce.
- What's still validated, because these are structural mistakes this tool *can* detect with
  certainty:
  - a value must be non-blank;
  - an override value must not already be `%`-wrapped (a caller who writes `8=%env.JDK_1_8%`
    almost certainly means `8=env.JDK_1_8` — this tool wraps the value itself);
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
- Both shapes SHALL resolve to the same major-version integer used for override lookup and
  template substitution: take the substring after the last `.`, or the whole string if there
  is no `.`, then parse as an integer.
- Examples: `"1.8"` → `8`; `"8"` → `8`; `"11"` → `11`; `"17"` → `17`.
- This is why `8` needs no built-in exception (Decision 4) — `"1.8"` and a hypothetical
  override keyed `8=...` already refer to the same major through this extraction rule; the
  *naming* exception (`env.JDK_1_8` instead of `env.JDK_8_0`) is what still needs an explicit
  caller-supplied override, not the version-matching itself.

### 8. Eager validation of every mapping entry, not lazy failure at `setParameter` time

- A malformed entry — the option supplied without a leading template, a bare entry anywhere
  other than position 0, a non-numeric override key, an already-`%`-wrapped value, a value
  containing `{major}` where it shouldn't, a template missing (or containing more than one)
  `{major}` placeholder, or a duplicate override key — is rejected at option-parse time with a
  message naming the specific bad entry.
- Rejected the alternative of accepting anything and letting a bad reference silently write
  garbage into TeamCity (e.g. `%%env.JDK_1_8%%` or an unresolvable parameter) — that failure
  mode would only surface much later, as a broken build, far from the CLI invocation that
  caused it.

### 9. Fallback to the parent project's existing `env.JAVA_HOME`, not "skip"

- An earlier version of this design had the feature skip entirely (no `env.JAVA_HOME` written)
  whenever the flag was absent or the component had no `javaVersion`, making it strictly
  opt-in with zero behavior change for anyone not using the flag.
- Overridden after review: skipping meant a team that already has `env.JAVA_HOME` configured
  at the parent-project level (their existing convention, predating this tool) would see it
  silently absent on every new child project this tool creates, forcing manual re-entry per
  component.
- Falling back to `client.getParameter(PROJECT, parentProjectId, "env.JAVA_HOME")` — the exact
  same lookup pattern `JDK_VERSION`'s default already uses — means an already-working
  parent-level setup keeps working with zero flag usage, at the cost of the sharp edge
  documented in the worked example above (parent value wins over the component's actual
  `javaVersion` when the option is omitted). This trade-off is accepted; see Risks below.

### 10. Written at project level, not build-type level

- `JDK_VERSION` is only ever set on the compile build-type (`compileConfig.id`).
  `env.JAVA_HOME` is instead set on the newly created component project (`project.id`) via
  `setProjectParameter`, so it's inherited by every build config under that project (compile,
  RC, checklist, release) — not just the compile step, since release/RC builds can also need a
  JDK on the agent.
- This is a deliberate behavioral difference from `JDK_VERSION`'s narrower scope, not an
  inconsistency.

### 11. `JDK_VERSION` is deprecated but not removed in this change

- Both parameters are set side by side.
- This repo has no existing tech-debt-tracking convention of its own (unlike `octopus-base`'s
  single-table register or
  `octopus-components-management-portal-wt/rms-registered-build-params`'s per-item files);
  rather than inventing one, the removal condition is recorded here as a decision so a future
  change can find it:
  - **Remove the `JDK_VERSION`-setting block once all teams currently depending on it have
    migrated their build templates to consume `%env.JAVA_HOME%` instead of the literal
    `%JDK_VERSION%` value.**
  - No specific date — this is consumer-migration-gated, not time-gated.

## Out of Scope

- Any change to how `JDK_VERSION` itself is computed or set (Decision 11) — deferred until
  consumer migration is confirmed.
- Reading the mapping from a TeamCity project parameter (Decision 1) — CLI flag only.
- A hardcoded fallback formula or built-in per-major exceptions (Decision 4) — the leading bare
  template entry is the only formula mechanism, and it is entirely caller-supplied.
- Enforcing a required naming prefix (e.g. `env.`) on override or template values (Decision 5).

## Risks / Trade-offs

- **Silent overwrite of an existing `env.JAVA_HOME`.**
  - If a team already hand-configured `env.JAVA_HOME` on a component's project directly (not
    on the parent), the next `create-build-chain` run without `--java-home-mapping` for that
    component will read the *parent's* value (or nothing) and overwrite the child project's
    existing value.
  - Accepted because `setProjectParameter` always writes at project-creation time for a
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
