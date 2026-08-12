## Why

- `TeamcityCreateBuildChainCommand` sets a `JDK_VERSION` build-type parameter from the
  component-registry's `javaVersion` field (e.g. `"1.8"`, `"11"`). That's a bare version
  string — it only does anything useful if a build template downstream separately knows how
  to turn `"1.8"` into an actual JDK install path.
- TeamCity's own convention is different: agents publish `env.JDK_1_8`, `env.JDK_17_0`,
  `env.JDK_21_0`, `env.JDK_25_0`, ... as configuration parameters pointing at real JDK
  installs, and a build selects one by setting `env.JAVA_HOME` to a *reference*, e.g.
  `%env.JDK_17_0%`. Teams that already rely on that convention get nothing from `JDK_VERSION`
  today and have to hand-configure `env.JAVA_HOME` per project themselves.
- This CLI is invoked from many different teams' TeamCity pipelines, and those teams don't all
  name their agent-side JDK parameters the same way — nor is the naming scheme guaranteed to
  survive the next JDK release (a hypothetical JDK 27 might not fit the `env.JDK_{major}_0`
  pattern some team already committed to).
- Any fixed mapping *or* fixed formula baked into this tool will eventually be wrong for
  someone, so both the per-version overrides *and* the fallback formula itself need to be
  supplied by the caller, not hardcoded here.

## What Changes

Resolve and set `env.JAVA_HOME` at project creation time, alongside the existing `JDK_VERSION`
logic (which is not removed — see the deprecation note below).

**A new `--java-home-mapping` CLI option on `create-build-chain`:**
- Accepts comma/semicolon-separated entries (`SPLIT_SYMBOLS` convention already used by
  `TeamcityUpdateParameterCommand`).
- Two kinds of entry, both in the same option value:
  - a **required leading template**: a *bare* value (no `key=`), always the first segment,
    containing the placeholder `{major}`, e.g. `env.JDK_{major}_0`. There is no template baked
    into this tool — if `--java-home-mapping` is supplied at all, the caller must supply the
    formula fallback themselves, first, in this option;
  - **explicit per-major overrides**: every other segment, a `key=value` pair where the key is
    a positive integer and the value is the parameter name to use for that major, e.g.
    `8=env.JDK_1_8`.
- Example: `--java-home-mapping=env.JDK_{major}_0,8=env.JDK_1_8,11=env.JDK_11_0`.
- Validated eagerly at parse time:
  - the option, if supplied, must start with the bare template — an overrides-only value
    (no leading template) is rejected, not treated as "no formula, overrides only";
  - a bare entry anywhere other than position 0 is rejected;
  - the template must contain exactly one `{major}` placeholder;
  - an override key must be a positive integer, and no override key may repeat;
  - an override value must not already be `%`-wrapped and must not contain `{major}`;
  - neither overrides nor the template are required to start with `env.` — this tool can't
    verify a value corresponds to a real agent parameter regardless of prefix, so only
    structural mistakes (see above) are checked.
  - Invalid input fails the command immediately with a message naming the bad entry.
- The component's major version is derived from `javaVersion` by taking the segment after the
  last `.` (`"1.8"` → `8`, `"17"` → `17`), so both the legacy dotted form and the bare modern
  form resolve consistently.

**Resolution, when the component has a non-null `javaVersion` and the option was supplied:**
1. explicit override for that major, if present;
2. otherwise the leading template with `{major}` substituted.

**Fallback to the parent project, when `--java-home-mapping` is omitted entirely, or the
component has no `javaVersion`:**
- the command does **not** skip setting `env.JAVA_HOME` — it falls back to whatever
  `env.JAVA_HOME` is already configured on `parentProjectId`, read the same way the existing
  `JDK_VERSION` default is read today;
- if that's also unset, nothing is written.

**Where it's written:**
- as a **project-level** parameter on the newly created component project (`project.id`), not
  only on the compile build-type;
- every build config created under that project (compile, RC, checklist, release) inherits it
  through normal TeamCity parameter inheritance, unlike `JDK_VERSION`, which today only ever
  overrides the compile step.

**`JDK_VERSION` is marked deprecated, not removed:**
- both parameters get set side by side during a migration period so nothing currently reading
  `JDK_VERSION` breaks;
- the deprecation is recorded as a numbered decision in `design.md` (this repo has no existing
  tech-debt tracking of its own) rather than as a scattered code comment, so there's one place
  a future cleanup pass can find the removal condition.

## Affected areas

- `TeamcityCreateBuildChainCommand`
  (`src/main/kotlin/org/octopusden/octopus/automation/teamcity/TeamcityCreateBuildChainCommand.kt`):
  new CLI option, new resolution/fallback logic, one new project-level `setProjectParameter`
  call.
- A new small pure mapping helper (`JavaHomeMapping` or similar) — no existing file owns this
  logic today.
- **Behavior change for existing consumers**: any TeamCity project under this tool's
  management that already has its *own* `env.JAVA_HOME` project parameter set will have it
  silently overwritten by the parent-project fallback value the next time
  `create-build-chain` runs without `--java-home-mapping` for that component. Teams relying on
  a manually-set `env.JAVA_HOME` should set it on the *parent* project instead, or start
  passing `--java-home-mapping`, before this ships.
- No change to `JDK_VERSION` behavior itself — it keeps being set exactly as before.

## Out of scope

- Removing or ceasing to set `JDK_VERSION` — tracked as a future decision once teams have
  migrated their build templates to consume `env.JAVA_HOME`; not part of this change.
- A TeamCity-project-parameter-based override source (as opposed to the CLI flag) —
  considered and rejected in favor of the CLI flag; see `design.md`.
- Validating that a mapping value corresponds to an agent parameter that actually exists on
  any real TeamCity agent — this tool has no visibility into agent configuration.
- Requiring a specific naming prefix (e.g. `env.`) on mapping values — this tool can't verify
  it means anything real anyway; see `design.md` Decision 5.
- Applying the same treatment to any parameter other than `env.JAVA_HOME` (e.g. no generic
  "arbitrary parameter mapping" mechanism is introduced).
- Any hardcoded major-version-to-parameter-name mapping or formula living in this tool's
  code — by design, everything (including the fallback formula) is caller-supplied data now.

## Rollout note

`--java-home-mapping` is optional and additive: omitting it preserves current behavior for
`JDK_VERSION` and only changes `env.JAVA_HOME` behavior if a value is already present on the
parent project (see the behavior-change note above). Teams adopt the mapping at their own pace
by adding the flag, with its required leading template, to their pipeline's
`create-build-chain` invocation.
