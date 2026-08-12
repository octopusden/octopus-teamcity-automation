## 1. Mapping resolution logic (Decisions 4, 7, spec: major-version and override/template requirements)

- [x] 1.1 Write failing unit tests for `JavaHomeMapping.resolve` (no TeamCity/Docker infra
      needed; no built-in constants to test since there are none by design — Decision 4).
      `src/test/kotlin/utils/javahome/JavaHomeMappingTest.kt` (package `utils.javahome`),
      7 tests:
  - [x] 1.1.1 explicit override for the resolved major wins, even when a leading template is
        also present (e.g. major `17` with both `17=env.JDK_17_CUSTOM` and template
        `env.JDK_{major}_0` present resolves to `env.JDK_17_CUSTOM`)
  - [x] 1.1.2 major with no override falls back to the supplied leading template with
        `{major}` substituted (e.g. template `env.JDK_{major}_0`, major `17` → `env.JDK_17_0`)
  - [x] 1.1.3 an arbitrary unlisted major (e.g. `27`) resolves via the same supplied template
        without any code change
  - [x] 1.1.4 caller-supplied `8=env.JDK_1_8` override reproduces the historical major-8 naming
        only because the caller supplied it — confirm there is no such behavior when it's
        omitted (major `8` with only template `env.JDK_{major}_0` resolves to `env.JDK_8_0`,
        not `env.JDK_1_8`)
  - [x] 1.1.5 major-version extraction (Decision 7, own dedicated tests, not just via the
        cases above):
    - [x] 1.1.5.1 `javaVersion = "1.8"` → derived major `8`
    - [x] 1.1.5.2 `javaVersion = "8"` → derived major `8` (covered together with 1.1.5.1 in
          `an override keyed by the derived major matches both dotted and bare source forms`)
    - [x] 1.1.5.3 `javaVersion = "17"` → derived major `17` (covered via 1.1.1/1.1.2's `"17"`
          fixtures)
    - [x] 1.1.5.4 an override keyed `8` matches both `"1.8"` and `"8"` as the source
          `javaVersion`
  - [x] 1.1.6 override value with no `env.` prefix resolves and is used as-is (no prefix
        requirement — Decision 5)
- [x] 1.2 Implement `JavaHomeMapping.resolve(javaVersion: String, overrides: Map<Int, String>, template: String): String`
      as a pure object
      (`src/main/kotlin/org/octopusden/octopus/automation/teamcity/utils/javahome/JavaHomeMapping.kt`,
      package `org.octopusden.octopus.automation.teamcity.utils.javahome` — moved there from
      the top-level `teamcity` package after initial implementation, at the user's request,
      along with `JavaHomeMappingOption` and both test classes; the tests use the shorter
      `utils.javahome` package instead of mirroring the full main package).
      No hardcoded formula or exceptions anywhere in this function (Decision 4). `template` is
      non-nullable — by the time this function is called, `JavaHomeMappingOption.parse` (task 2)
      has already guaranteed a template exists whenever a mapping was supplied at all. The
      `{major}` placeholder constant (`JavaHomeMapping.PLACEHOLDER`) lives here since both
      `resolve` and `JavaHomeMappingOption.parse`'s validation need it.
- [x] 1.3 Confirm tests pass: 7/7 green, run via the JUnit Platform Console Launcher against
      `compileTestKotlin` output (`./gradlew test` requires the docker-compose TeamCity/registry
      stack for this whole module — not available in this environment — so pure-logic tests were
      run directly against the compiled classpath instead of through the `test` task; the same
      tests will run under `./gradlew test` on CI without changes).

## 2. `--java-home-mapping` parsing: mandatory leading template, positional parsing, structural-only validation (Decisions 2, 3, 5, 6, 8, spec: mapping-parsing requirements)

**Seam changed from tasks.md's original plan, agreed with the user before writing tests**: the
parsing/validation logic was extracted into a pure `JavaHomeMappingOption.parse(raw: String)`
function and unit-tested directly, instead of process-level tests spawning the built shadow jar
(`ApplicationTest.kt:647`-style). This is pure parsing logic (string in, parsed value or
`BadParameterValue` out) — a direct unit test is faster and doesn't require a jar build, per the
`implement` skill's guidance to test pure logic at its own seam. The actual clikt `option(...)`
property on `TeamcityCreateBuildChainCommand` that calls this parser is deferred to task 3,
since that's where the parsed value is first consumed — task 2 delivers the parser only.

- [x] 2.1 Write failing tests for `JavaHomeMappingOption.parse`
      (`src/test/kotlin/utils/javahome/JavaHomeMappingOptionTest.kt`, package `utils.javahome`,
      11 tests — one more than originally scoped, see 2.1.12) for:
  - [x] 2.1.1 valid mapping with a leading template + overrides
        (`env.JDK_{major}_0,8=env.JDK_1_8,11=env.JDK_11_0`) parses without error
  - [x] 2.1.2 mapping with only overrides, no leading template
        (`8=env.JDK_1_8,11=env.JDK_11_0`) throws `BadParameterValue`, with a message noting the
        leading template is required
  - [x] 2.1.3 a bare entry NOT in the first position
        (`8=env.JDK_1_8,env.JDK_{major}_0`) throws `BadParameterValue`, with a message noting
        the bare entry is only valid first
  - [x] 2.1.4 override value with no `env.` prefix (`env.JDK_{major}_0,8=JDK_1_8`) parses
        without error (Decision 5 — no prefix requirement)
  - [x] 2.1.5 non-numeric override key (`env.JDK_{major}_0,abc=env.JDK_1_8`) throws
        `BadParameterValue` naming the bad entry
  - [x] 2.1.6 already-`%`-wrapped override value (`env.JDK_{major}_0,8=%env.JDK_1_8%`) throws
        `BadParameterValue`
  - [x] 2.1.7 override value containing a `{major}` placeholder
        (`env.JDK_{major}_0,8=env.JDK_{major}_8`) throws `BadParameterValue` (placeholders only
        valid in the leading template)
  - [x] 2.1.8 duplicate override key (`env.JDK_{major}_0,8=env.JDK_1_8,8=env.JDK_8_0`) throws
        `BadParameterValue`
  - [x] 2.1.9 leading template missing the `{major}` placeholder (`env.JDK_HOME,8=env.JDK_1_8`)
        throws `BadParameterValue`
  - [x] 2.1.10 leading template with more than one `{major}` placeholder
        (`env.JDK_{major}_{major}`) throws `BadParameterValue`
  - [x] 2.1.11 option omitted entirely: N/A at this seam — `parse` is only ever called with a
        raw string once clikt has already determined the option was supplied; "option omitted"
        is a task-3 concern (the clikt property returning `null`/absent), not something
        `parse` itself can observe.
  - [x] 2.1.12 (added on review) already-`%`-wrapped **leading template**
        (`%env.JDK_{major}_0%,8=env.JDK_1_8`) throws `BadParameterValue` — spec's
        structural-validation requirement lists this for the template too (not just override
        values), and the original task list only enumerated it for overrides.
- [x] 2.2 Implement, as separate units:
  - [x] 2.2.1 `JavaHomeMappingOption.kt` companion object — `OPTION_NAME = "--java-home-mapping"`
        (moved here from the originally-planned `TeamcityCreateBuildChainCommand` companion
        object, since that command isn't touched until task 3) and the placeholder constant
        `JavaHomeMapping.PLACEHOLDER = "{major}"` (task 1). No standalone regexes — validation
        is inline string checks (`startsWith('%') && endsWith('%')`, placeholder-count-via-split)
        rather than `Regex`, since each check is a single simple predicate.
  - [x] 2.2.2 `JavaHomeMappingOption.kt` — `data class JavaHomeMappingOption(val overrides: Map<Int, String>, val template: String)`
        with `companion object { fun parse(raw: String): JavaHomeMappingOption }`: splits on
        `SPLIT_SYMBOLS` preserving order; requires the first segment to be bare and validates it
        as the template (non-`%`-wrapped, exactly one placeholder); validates every remaining
        segment contains `=` and parses as a numeric override (non-`%`-wrapped, no placeholder,
        no duplicate key), rejecting any later bare segment; fails via a private `fail(message)`
        helper naming the offending entry on any violation. Refactored into `parseTemplate` /
        `parseOverride` / `isWrapped` / `fail` helper functions to keep `parse` under detekt's
        `ThrowsCount` limit and each line under the configured max length.
- [x] 2.3 Confirm tests pass: 11/11 green (18/18 total across both test files), same
      console-launcher approach as 1.3. `./gradlew detekt ktlintCheck` also green against the
      new files (`./gradlew clean compileKotlin compileTestKotlin` also verified clean).

## 3. Resolve and always write `env.JAVA_HOME` in `createBuildChain` (Decisions 4, 9-10, spec: parent-fallback and always-written-at-project-level requirements)

- [x] 3.1 Write failing functional tests in `ApplicationTest.kt` (extended
      `executeForCreateBuildChainCommand` with an optional `javaHomeMapping: String? = null`
      param, appended to the arg list only when non-null; new test
      `testTeamCityCreateBuildChainForJavaHome`, reusing `default-jdk-component` /
      `custom-jdk-component` fixtures from `TestComponents.groovy:130-156`):
  - [x] 3.1.1 no mapping supplied, `TEST_PROJECT` (parent) has no `env.JAVA_HOME` → new
        project's `env.JAVA_HOME` is written with an **empty string**, not left unset; also
        asserted on the RC/checklist/release build configs (combines with 3.1.5 in one round)
  - [x] 3.1.2 no mapping supplied, `TEST_PROJECT` has `env.JAVA_HOME` pre-set → new project's
        `env.JAVA_HOME` equals the parent's value, for both the 1.8 and 11 fixtures alike
  - [x] 3.1.3 mapping supplied with a leading template + overrides covering both fixtures'
        majors (`env.JDK_{major}_0,8=env.JDK_1_8,11=env.JDK_11_0`) → new project's
        `env.JAVA_HOME` is `""` for `default-jdk-component` (no `javaVersion` at all — falls
        back to the unset parent, not the mapping) and `%env.JDK_11_0%` for
        `custom-jdk-component`
  - [x] 3.1.4 mapping supplied where the leading template (not an override) applies because no
        override covers the component's major → reused `custom-jdk-component` (`javaVersion
        = "11"`) against a mapping with only an `8=` override, confirming `%env.JDK_11_0%` via
        the template rather than adding a new fixture
  - [x] 3.1.5 value is inherited by the RC/checklist/release build configs, not only compile
        (asserts the project-level write, not a build-type-level one) — covered in 3.1.1's round
  - [x] 3.1.6 (dropped as a separate case — identical setup to 3.1.1, since
        `default-jdk-component` already has no `javaVersion`; not duplicated)
- [x] 3.2 Implement, as separate units:
  - [x] 3.2.1 `TeamcityCreateBuildChainCommand.kt` — added `javaHomeMapping` option
        (`.convert { JavaHomeMappingOption.parse(it) }`, no `.required()`/`.default()` so it's
        `null` when the flag is absent) and the `JAVA_HOME_MAPPING` companion constant
  - [x] 3.2.2 `TeamcityCreateBuildChainCommand.kt::createBuildChain` — calls
        `setParameter(ConfigurationType.PROJECT, project.id, "env.JAVA_HOME", resolveJavaHome(...))`
        unconditionally, right after the (now commented, Decision 11) `JDK_VERSION` block.
        Wrapping (`"%$it%"`) is applied only to the mapping-resolved case — the parent-fallback
        value is used as-is, since it's already whatever was previously stored (possibly
        already wrapped, possibly not; this tool doesn't touch it)
  - [x] 3.2.2a (added on review) `JavaHomeMappingOption.resolveOrNull(javaVersion: String?): String?`
        added on `JavaHomeMappingOption` itself (not a private helper on the command class) —
        `TeamcityCreateBuildChainCommand` was already at detekt's `TooManyFunctions` limit (11),
        and a private member helper for the resolution branch also tripped
        `CyclomaticComplexMethod` on `createBuildChain`. Moving the branch onto the data class
        that already owns `overrides`/`template` fixed both without inflating either limit, and
        reads more naturally: "ask the parsed option to resolve this javaVersion." Two new unit
        tests added in `JavaHomeMappingOptionTest.kt`.
  - [x] 3.2.2b (added on review, user feedback) `resolveJavaHome` moved from a top-level private
        function back into `TeamcityCreateBuildChainCommand` as a private member (the user's
        stated preference — a helper that's conceptually part of this command reads better as
        a member than as a stray file-level function). Freed the function-count slot this
        needed, without exceeding detekt's `TooManyFunctions` limit, by merging the near-identical
        `setBuildTypeParameter`/`setProjectParameter` helpers into one
        `setParameter(configurationType, id, name, value)` (they differed only in
        `ConfigurationType` and one word of log text); all call sites updated.
  - [x] 3.2.2c (added on review, user feedback) removed the duplicate `--java-home-mapping`
        string literal. `JavaHomeMappingOption.OPTION_NAME` is gone; `parse` now takes
        `optionName: String` as a parameter (used only to prefix its error messages), so
        `TeamcityCreateBuildChainCommand.JAVA_HOME_MAPPING` is the **only** place this string
        is defined — both the clikt option registration and the `.convert { JavaHomeMappingOption.parse(it, JAVA_HOME_MAPPING) }`
        call read it from the same constant. `JavaHomeMappingOptionTest.kt` defines its own
        private `OPTION_NAME` test constant for constructing scenarios, which is expected
        (tests routinely need their own literals) and doesn't reintroduce a second production
        definition.
- [x] 3.3 Compiled clean (`./gradlew clean compileKotlin compileTestKotlin`) and
      `./gradlew detekt ktlintCheck` clean. The functional test itself was **not run locally** —
      this environment has no running Docker daemon (Colima) and starting one wasn't attempted
      without being asked, per this workspace's guidance on invasive/resource-heavy actions; per
      the `build-verification` skill, this Docker/OKD-backed suite belongs on CI, not local
      pre-commit. It will run there on `./gradlew test`.

## 4. `JDK_VERSION` deprecation note (Decision 11)

- [x] 4.1 (revised on review, user feedback) A bare code comment pointing at `design.md` was
      rejected — a comment-only record disappears once this change folder is archived, and this
      repo has no other tech-debt tracking to fall back on. Created
      `docs/tech-debt/TD-001-jdk-version-param-removal.md` instead, following the
      `rms-registered-build-params` one-file-per-item convention (Status / Context / Symptoms /
      Acceptance criteria / Related sections). The code comment on the `JDK_VERSION`-setting
      block in `TeamcityCreateBuildChainCommand.kt` now just points at it:
      `// TD-001: superseded by env.JAVA_HOME below; see docs/tech-debt/TD-001-jdk-version-param-removal.md`.
      `design.md`'s Decision 11 and `proposal.md`'s deprecation note were both updated to
      reference the TD-001 file as the authoritative removal record, rather than restating the
      removal condition inline.
- [x] 4.2 No behavior or test change — `JDK_VERSION` continues exactly as today (diff-checked:
      only the comment text changed from a design.md pointer to a TD-001 pointer; the block's
      own lines are untouched).

## 5. Finalization

- [ ] 5.1 Full suite green: `./gradlew build` — **not run**. This environment has no running
      Docker daemon (Colima), and `./gradlew build` pulls in the docker-compose-backed
      functional `test` task unconditionally for this module (there's no separate unit-test
      task). What was verified locally instead: `./gradlew clean compileKotlin
      compileTestKotlin` (clean) and the pure-logic suite (20/20 green, run via the JUnit
      Platform Console Launcher against the compiled classpath — see tasks 1.3/2.3). The
      Docker-backed functional suite, including the new `testTeamCityCreateBuildChainForJavaHome`,
      needs to run on CI per the `build-verification` skill.
- [x] 5.2 Static analysis clean: `./gradlew detekt ktlintCheck` green against every file
      touched or added in this change (`JavaHomeMapping.kt`, `JavaHomeMappingOption.kt`, both
      test files, `TeamcityCreateBuildChainCommand.kt`, `ApplicationTest.kt`). Two detekt
      findings surfaced and were fixed during implementation, not silently worked around:
      `CyclomaticComplexMethod` on `createBuildChain` (fixed by moving resolution logic to
      `JavaHomeMappingOption.resolveOrNull` and a top-level `resolveJavaHome` function) and
      `TooManyFunctions` on `TeamcityCreateBuildChainCommand` (fixed the same way, by not adding
      a new member function to that class).
- [x] 5.3 Re-confirmed every `Out of scope` item in `proposal.md` actually holds:
  - `JDK_VERSION` setting logic untouched (diff-checked — only a comment was added above it);
  - no TeamCity-project-parameter-based override path added (mapping is CLI-flag-only);
  - no generic parameter-mapping mechanism introduced beyond `env.JAVA_HOME`;
  - no `env.`-prefix (or other fixed-prefix) requirement snuck into validation (confirmed via
    the `JavaHomeMappingOptionTest` case asserting a prefix-less override is accepted);
  - grepped `src/main` for `JDK_`: the only hits are the pre-existing, unchanged `JDK_VERSION`
    parameter name and illustrative example strings in CLI `help` text
    (`env.JDK_{major}_0`, `env.JDK_1_8`) — no hardcoded formula or mapping table anywhere in
    the resolution logic itself.
- [x] 5.4 Re-confirmed both accepted risks in `design.md`'s Risks section are still true of the
      shipped code: `resolveJavaHome` (private member) feeds `setParameter(PROJECT, ...)`
      unconditionally on every `createBuildChain` run (silent-overwrite-on-rerun, including
      blanking to `""`, still applies), and the mapping is only consulted when
      `--java-home-mapping` is supplied (parent-value-wins-when-option-omitted still applies) —
      neither was silently dropped during implementation.
- [x] 5.5 (added on review) A later pass over the openspec docs found `resolveJavaHome`'s
      parent-fallback branch had lost its final `?: ""` at some point after 3.2.2b/c landed —
      `client.getParameter(...)` is a Kotlin platform type (`String!`), so this compiled without
      warning but could have returned `null` from a function declared to return non-null
      `String`, violating Decision 9/the spec's "always written, empty if nothing resolves"
      requirement. Restored the `?: ""`; recompiled and re-ran the full unit suite (20/20 green)
      and `detekt`/`ktlintCheck` (clean) to confirm. This is exactly the kind of drift an
      alignment check is for — flagged here rather than silently fixed without a record.
