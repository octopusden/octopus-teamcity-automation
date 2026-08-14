# Design

## Context

- `TeamcityCreateBuildChainCommand.createBuildChain()`
  (`src/main/kotlin/org/octopusden/octopus/automation/teamcity/TeamcityCreateBuildChainCommand.kt`)
  currently sets `JDK_VERSION` on the compile build-type from
  `component.buildParameters?.javaVersion`, falling back to nothing (just skips the write) when it
  matches the parent's `JDK_VERSION` default. `env.JAVA_HOME` does not exist anywhere in this
  command today (on `main`).
- `setBuildTypeParameter` / `setProjectParameter` (lines 220–234) were the two existing
  parameter-writing helpers, at build-type and project scope respectively. Adding `defaultJavaHome`
  and `resolveJavaHome` alongside them pushed `TeamcityCreateBuildChainCommand` to 12 functions,
  past detekt's default `TooManyFunctions` threshold of 11. They are now a single
  `setParameter(configurationType: ConfigurationType, id: String, name: String, value: String)`,
  used by every call site including the pre-existing `JDK_VERSION` one — the same fix the
  `env-java-home` branch's more complex approach already applied for the same reason.
- `client.getParameter(ConfigurationType, id, name)` throws `feign.FeignException.NotFound` (not
  `null`) when the parameter doesn't exist — already handled this way for the `JDK_VERSION`
  parent-default lookup at line 107, and reused identically here.

## Worked example

Given `parentProjectId = "TeamOctopus"`:

| Invocation | Parent's `env.JAVA_HOME` | Result |
|---|---|---|
| `--default-java-home=env.JDK_17_0` | *(irrelevant)* | `env.JAVA_HOME = "%env.JDK_17_0%"` |
| no `--default-java-home` | `"%env.JDK_1_8%"` | `env.JAVA_HOME = "%env.JDK_1_8%"` (used as-is) |
| no `--default-java-home` | *(unset)* | `env.JAVA_HOME = ""` |
| `--default-java-home=%env.JDK_17_0%` (already wrapped) | *(irrelevant)* | **invalid** — command fails before creating any project |
| `--default-java-home=` (blank) | `"%env.JDK_1_8%"` | treated as omitted → `env.JAVA_HOME = "%env.JDK_1_8%"` |

## Goals / Non-Goals

**Goals:**
- Every component project this tool creates ends up with its own `env.JAVA_HOME` project
  parameter — present in TeamCity, directly editable, even if empty.
- A caller can point a single `create-build-chain` invocation at a specific agent-side JDK
  parameter without touching the parent project's configuration.
- Metarunner-driven callers that don't pass the new parameter at all keep working unchanged.

**Non-Goals:**
- No per-major-version mapping, template, or derivation from the component registry — that is the
  `env-java-home` branch's approach, superseded by this one.
- No change to `JDK_VERSION`.
- No validation that the supplied reference name resolves to a real agent parameter.

## Decisions

### 1. One flat optional value, not a mapping

- `--default-java-home` takes a single string, not a delimited list of entries. There is no
  per-major-version resolution and no template placeholder — the caller already knows, at the
  point they invoke `create-build-chain`, which JDK parameter this component's chain should
  default to (or they omit it and accept the parent's existing default).
- This intentionally does not scale to "different JDK per component major version" the way the
  `env-java-home`-branch mapping did — that flexibility is not needed for the common case, and the
  parent-project fallback already covers "most projects under this parent use JDK X."

### 2. Value is supplied bare; the tool applies `%`-wrapping

- Consistent with `%dep.<id>.BUILD_VERSION%`-style references already written elsewhere in this
  file — a TeamCity parameter reference is always `%name%` on the wire, but callers configuring
  metarunner parameters and CLI flags find a bare name (`env.JDK_17_0`) easier to author and
  reason about than an already-`%`-wrapped one.
- An already-`%`-wrapped input is rejected outright (`BadParameterValue`), rather than silently
  accepted or double-wrapped — the same defensive check the more complex approach used for its
  template/override values.

### 3. Omitting the option is a first-class case, not a degraded one

- `--default-java-home` has no `.required()`, and a blank value (`""`, whitespace) is treated
  identically to "not supplied" — this is what lets
  `metarunners/CreateTeamCityBuildChain.xml` default `%DEFAULT_JAVA_HOME%` to `""` and pass it
  through unconditionally without breaking every existing build config that doesn't set it.

### 4. Fallback is the parent project's existing `env.JAVA_HOME`, used as-is

- When the option is absent, `client.getParameter(ConfigurationType.PROJECT, parentProjectId,
  "env.JAVA_HOME")` is read and used **without** re-wrapping — whatever is already stored there
  (typically itself a `%...%` reference, or empty) is copied onto the new project verbatim.
- `FeignException.NotFound` (no such parameter on the parent) is caught and treated as absent,
  falling through to Decision 5.

### 5. `env.JAVA_HOME` is always written: option, else parent, else empty string

- `setProjectParameter(project.id, "env.JAVA_HOME", resolveJavaHome())` is called exactly once per
  `createBuildChain` run, unconditionally — mirroring `COMPONENT_NAME` and `PROJECT_VERSION`,
  which are also always set.
- Never skipped, never left absent: an empty string is still an explicit write, so every project
  this tool creates is uniformly editable in TeamCity's UI afterward.

### 6. Written at project level, inherited by every build config

- Same placement decision as the more complex approach: setting it on `project.id` means compile,
  RC, checklist, and release build configs all inherit it through normal TeamCity parameter
  inheritance, without a separate build-type-level write for each.

## Out of Scope

- Anything derived from `component.buildParameters?.javaVersion` — this change does not read that
  field at all.
- A per-major mapping or template mechanism — see Decision 1.
- Any change to `JDK_VERSION` or a deprecation note for it.

## Risks / Trade-offs

- **Silent overwrite of an existing `env.JAVA_HOME`, possibly with an empty string** — same
  trade-off the more complex approach accepted: if a component project already has its own
  hand-configured `env.JAVA_HOME`, a re-run of `create-build-chain` without `--default-java-home`
  overwrites it with the parent's value (or blanks it). Accepted for the same reason: this write
  happens at project-creation time for a project this tool itself just created; re-running against
  an existing project is not this command's documented use case.
- **One value per invocation, not per component major version** — a parent project whose
  components span multiple JDK majors cannot get an automatically-differentiated `env.JAVA_HOME`
  per component from this option alone; the caller must either pass a different
  `--default-java-home` per invocation or manage that difference outside this tool. Accepted as
  the direct consequence of simplifying away the mapping/template mechanism.
