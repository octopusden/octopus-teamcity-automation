# Proposal

## Why

- A separate, more elaborate approach to the same problem already exists on the `env-java-home`
  branch (`openspec/changes/set-env-java-home-from-java-version` there): it derives `env.JAVA_HOME`
  from the component-registry's `javaVersion` field via a caller-supplied `--java-home-mapping`
  option — a required leading template plus per-major-version overrides, its own placeholder
  syntax, eager structural validation, and a `JDK_VERSION` deprecation note. That's more machinery
  than the problem needs.
- What's actually wanted is simpler: every project this tool creates should end up with an
  `env.JAVA_HOME` project parameter, and the caller should be able to just say which agent-side
  JDK parameter a given `create-build-chain` invocation should point at — no per-major derivation,
  no template, no dependency on the component registry's `javaVersion` field at all.
- This change starts fresh from `main` (not from `env-java-home`) and replaces that entire
  approach with a single optional parameter.

## What Changes

**A new `--default-java-home` option on `create-build-chain`:**
- Value is a **bare TeamCity parameter reference name** (not `%`-wrapped), e.g. `env.JDK_17_0` —
  the name of a configuration parameter that already exists somewhere in the TeamCity parameter
  hierarchy (typically published by an agent), not a literal path.
- **Optional** — omitting it entirely is a supported, unremarkable case (see fallback below).
- If supplied non-blank, it is wrapped as `%<value>%` when written.
- Rejected at parse time if already `%`-wrapped — the tool does the wrapping; a caller passing an
  already-wrapped value almost certainly misunderstands the option.

**Resolution, in order:**
1. `--default-java-home`, if supplied (non-blank) → `%<value>%`.
2. Otherwise, the parent project's (`parentProjectId`) existing `env.JAVA_HOME` project
   parameter, read as-is (no re-wrapping — whatever is already stored there).
3. Otherwise (parent has no `env.JAVA_HOME` either), an **empty string**.

**Unrelated to the component registry:** unlike the `env-java-home`-branch approach, this does
not read `component.buildParameters?.javaVersion` at all. Resolution depends only on
`--default-java-home` and the parent project's existing parameter.

**Where it's written:** as a **project-level** parameter on the newly created component project
(`project.id`), unconditionally — even when the resolved value is `""` — so every build config
under that project (compile, RC, checklist, release) inherits it through normal TeamCity
parameter inheritance.

**`JDK_VERSION` is marked deprecated, not removed:** its own logic is untouched — both parameters
are set side by side during a migration period. The removal condition is tracked as
`docs/tech-debt/TD-001-jdk-version-param-removal.md` (this repo's first tech-debt record on this
branch), referenced from a short `TD-001:` comment on the `JDK_VERSION`-setting block.

## Affected areas

- `TeamcityCreateBuildChainCommand`
  (`src/main/kotlin/org/octopusden/octopus/automation/teamcity/TeamcityCreateBuildChainCommand.kt`):
  one new nullable CLI option, one new small `resolveJavaHome` private member, one new
  project-level parameter write. The two pre-existing parameter-writing helpers,
  `setBuildTypeParameter` and `setProjectParameter`, are merged into one
  `setParameter(configurationType, id, name, value)` — see `design.md` Context.
- `metarunners/CreateTeamCityBuildChain.xml`: a new `%DEFAULT_JAVA_HOME%` metarunner parameter,
  defaulting to `""`, passed through as `--default-java-home=%DEFAULT_JAVA_HOME%` so every
  existing build config using this metarunner keeps working unchanged.
- **Behavior change for existing consumers**: any TeamCity project under this tool's management
  whose component project already has its own `env.JAVA_HOME` set will have it overwritten the
  next time `create-build-chain` runs for that component without `--default-java-home` — with the
  parent's value, or blanked to `""` if the parent has none either. Teams wanting a stable default
  should set `env.JAVA_HOME` on the *parent* project, or pass `--default-java-home` explicitly.

## Out of scope

- Deriving anything from the component registry's `javaVersion` field.
- Per-major-version overrides or a `{major}`-style template — this option is a single flat value
  per invocation, not a mapping.
- Any change to `JDK_VERSION`'s own logic.
- Actually removing `JDK_VERSION` — tracked separately in `TD-001`, gated on consumer migration.
- Validating that the supplied parameter name corresponds to a real agent-side TeamCity
  parameter — this tool has no visibility into agent configuration.

## Rollout note

`--default-java-home` is optional. Omitting it is not an error: `env.JAVA_HOME` still gets
written on every new component project, either inherited from the parent project's existing
value or as an empty placeholder ready to be filled in directly in TeamCity.
