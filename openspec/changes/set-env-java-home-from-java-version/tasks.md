## 1. Mapping resolution logic (Decisions 4, 7, spec: major-version and override/template requirements)

- [ ] 1.1 Write failing unit tests for `JavaHomeMapping.resolve` (no TeamCity/Docker infra
      needed; no built-in constants to test since there are none by design — Decision 4):
  - [ ] 1.1.1 explicit override for the resolved major wins, even when a leading template is
        also present (e.g. major `17` with both `17=env.JDK_17_CUSTOM` and template
        `env.JDK_{major}_0` present resolves to `env.JDK_17_CUSTOM`)
  - [ ] 1.1.2 major with no override falls back to the supplied leading template with
        `{major}` substituted (e.g. template `env.JDK_{major}_0`, major `17` → `env.JDK_17_0`)
  - [ ] 1.1.3 an arbitrary unlisted major (e.g. `27`) resolves via the same supplied template
        without any code change
  - [ ] 1.1.4 caller-supplied `8=env.JDK_1_8` override reproduces the historical major-8 naming
        only because the caller supplied it — confirm there is no such behavior when it's
        omitted (major `8` with only template `env.JDK_{major}_0` resolves to `env.JDK_8_0`,
        not `env.JDK_1_8`)
  - [ ] 1.1.5 major-version extraction (Decision 7, own dedicated tests, not just via the
        cases above):
    - [ ] 1.1.5.1 `javaVersion = "1.8"` → derived major `8`
    - [ ] 1.1.5.2 `javaVersion = "8"` → derived major `8`
    - [ ] 1.1.5.3 `javaVersion = "17"` → derived major `17`
    - [ ] 1.1.5.4 an override keyed `8` matches both `"1.8"` and `"8"` as the source
          `javaVersion`
  - [ ] 1.1.6 override value with no `env.` prefix resolves and is used as-is (no prefix
        requirement — Decision 5)
- [ ] 1.2 Implement `JavaHomeMapping.resolve(javaVersion: String, overrides: Map<Int, String>, template: String): String`
      as a pure object/function (design.md Context — no existing file owns this logic). No
      hardcoded formula or exceptions anywhere in this function (Decision 4). Note: `template`
      is non-nullable here — by the time this function is called, the CLI option layer (task 2)
      has already guaranteed a template exists whenever a mapping was supplied at all.
- [ ] 1.3 Confirm tests pass: `./gradlew test --tests "*JavaHomeMapping*"`.

## 2. `--java-home-mapping` CLI option: mandatory leading template, positional parsing, structural-only validation (Decisions 2, 3, 5, 6, 8, spec: mapping-parsing requirements)

- [ ] 2.1 Write failing tests (process-level, matching the existing `ApplicationTest.kt:647`
      non-zero-exit style) for:
  - [ ] 2.1.1 valid mapping with a leading template + overrides
        (`env.JDK_{major}_0,8=env.JDK_1_8,11=env.JDK_11_0`) parses without error
  - [ ] 2.1.2 mapping with only overrides, no leading template
        (`8=env.JDK_1_8,11=env.JDK_11_0`) exits non-zero, with a message noting the leading
        template is required
  - [ ] 2.1.3 a bare entry NOT in the first position
        (`8=env.JDK_1_8,env.JDK_{major}_0`) exits non-zero, with a message noting the bare
        entry is only valid first
  - [ ] 2.1.4 override value with no `env.` prefix (`env.JDK_{major}_0,8=JDK_1_8`) parses
        without error (Decision 5 — no prefix requirement)
  - [ ] 2.1.5 non-numeric override key (`env.JDK_{major}_0,abc=env.JDK_1_8`) exits non-zero
        with a message naming the bad entry
  - [ ] 2.1.6 already-`%`-wrapped override value (`env.JDK_{major}_0,8=%env.JDK_1_8%`) exits
        non-zero
  - [ ] 2.1.7 override value containing a `{major}` placeholder
        (`env.JDK_{major}_0,8=env.JDK_{major}_8`) exits non-zero (placeholders only valid in
        the leading template)
  - [ ] 2.1.8 duplicate override key (`env.JDK_{major}_0,8=env.JDK_1_8,8=env.JDK_8_0`) exits
        non-zero
  - [ ] 2.1.9 leading template missing the `{major}` placeholder (`env.JDK_HOME,8=env.JDK_1_8`)
        exits non-zero
  - [ ] 2.1.10 leading template with more than one `{major}` placeholder
        (`env.JDK_{major}_{major}`) exits non-zero
  - [ ] 2.1.11 option omitted entirely: command proceeds, mapping is absent (not "empty
        mapping with no template" — the whole option is unset)
- [ ] 2.2 Implement, as separate units:
  - [ ] 2.2.1 `TeamcityCreateBuildChainCommand.kt` companion object — add
        `JAVA_HOME_MAPPING = "--java-home-mapping"`, the placeholder token constant
        (`PLACEHOLDER = "{major}"`), and the two validation regexes (template requiring
        exactly one placeholder occurrence; override rejecting `%`-wrap and the placeholder —
        no `env.`-prefix regex, per Decision 5)
  - [ ] 2.2.2 `TeamcityCreateBuildChainCommand.kt` — add the option property parsing into a
        small holder (e.g. `data class JavaHomeMappingOption(val overrides: Map<Int, String>, val template: String)`,
        itself nullable/absent when the option isn't supplied): split on `SPLIT_SYMBOLS`
        preserving order; require the first segment to be bare and validate it as the
        template; validate every remaining segment contains `=` and parses as a numeric
        override, rejecting any later bare segment; reject duplicate override keys; fail with
        a message naming the offending entry on any violation
- [ ] 2.3 Confirm tests pass: `./gradlew test --tests "*ApplicationTest*JavaHomeMapping*"` (or
      the equivalent method names once written).

## 3. Resolve and always write `env.JAVA_HOME` in `createBuildChain` (Decisions 4, 9-10, spec: parent-fallback and always-written-at-project-level requirements)

- [ ] 3.1 Write failing functional tests in `ApplicationTest.kt` (extends
      `executeForCreateBuildChainCommand` with an optional `javaHomeMapping: String? = null`
      param, reusing `default-jdk-component` / `custom-jdk-component` fixtures from
      `TestComponents.groovy:130-154`):
  - [ ] 3.1.1 no mapping supplied, `TEST_PROJECT` (parent) has no `env.JAVA_HOME` → new
        project's `env.JAVA_HOME` is written with an **empty string**, not left unset
  - [ ] 3.1.2 no mapping supplied, `TEST_PROJECT` has `env.JAVA_HOME` pre-set → new project's
        `env.JAVA_HOME` equals the parent's value, for both the 1.8 and 11 fixtures alike
  - [ ] 3.1.3 mapping supplied with a leading template + overrides covering both fixtures'
        majors (`env.JDK_{major}_0,8=env.JDK_1_8,11=env.JDK_11_0`) → new project's
        `env.JAVA_HOME` is `%env.JDK_1_8%` for `default-jdk-component` and `%env.JDK_11_0%` for
        `custom-jdk-component`
  - [ ] 3.1.4 mapping supplied where the leading template (not an override) applies because no
        override covers the component's major → template applies (needs a fixture with an
        uncovered `javaVersion`, e.g. `"25"`, or reuse an existing one with overrides that
        exclude it)
  - [ ] 3.1.5 value is inherited by the RC/checklist/release build configs, not only compile
        (asserts the project-level write, not a build-type-level one)
  - [ ] 3.1.6 no mapping supplied, component has no `javaVersion`, and `TEST_PROJECT` has no
        `env.JAVA_HOME` either → new project's `env.JAVA_HOME` is still written, empty (both
        fallbacks exhausted, still not omitted)
- [ ] 3.2 Implement, as separate units:
  - [ ] 3.2.1 `TeamcityCreateBuildChainCommand.kt::createBuildChain` — add the resolution block
        directly after the existing `JDK_VERSION` block: when the option was supplied and
        `javaVersion` is present, `JavaHomeMapping.resolve(...)`; otherwise
        `client.getParameter(PROJECT, parentProjectId, "env.JAVA_HOME")`
  - [ ] 3.2.2 `TeamcityCreateBuildChainCommand.kt::createBuildChain` — call
        `setProjectParameter(project.id, "env.JAVA_HOME", resolved ?: "")` **unconditionally,
        with no guard** — every `createBuildChain` run writes this parameter exactly once,
        even when `resolved` is null/blank (Decision 9)
- [ ] 3.3 Confirm tests pass. Functional suite needs the Docker/OKD TeamCity test instance per
      this workspace's `build-verification` skill — record here whether it was run locally or
      deferred to CI.

## 4. `JDK_VERSION` deprecation note (Decision 11)

- [ ] 4.1 Add a short comment on the existing `JDK_VERSION`-setting block in
      `TeamcityCreateBuildChainCommand.kt` pointing at `design.md`'s Decision 11 for the
      removal condition (consumer migration to `env.JAVA_HOME`, not a fixed date). No
      standalone tech-debt file — this repo has no existing tech-debt-tracking convention, and
      the decision log in `design.md` is the record per the `openspec` skill's guidance not to
      duplicate it with a bespoke doc.
- [ ] 4.2 No behavior or test change here — `JDK_VERSION` continues exactly as today.

## 5. Finalization

- [ ] 5.1 Full suite green: `./gradlew build`.
- [ ] 5.2 Static analysis clean (whatever this repo runs as part of `./gradlew build` —
      confirm no new lint/detekt findings from the new files).
- [ ] 5.3 Re-confirm every `Out of scope` item in `proposal.md` actually holds:
  - `JDK_VERSION` setting logic untouched (diff-check);
  - no TeamCity-project-parameter-based override path added;
  - no generic parameter-mapping mechanism introduced beyond `env.JAVA_HOME`;
  - no `env.`-prefix (or other fixed-prefix) requirement snuck into validation;
  - grep the diff for any hardcoded `JDK_` literal or formula string living outside a test
    fixture (there should be none; everything resolution-related comes from the parsed CLI
    option).
- [ ] 5.4 Re-confirm both accepted risks in `design.md`'s Risks section are still true of the
      shipped code (silent-overwrite-on-rerun including blanking to `""`,
      parent-value-wins-when-option-omitted) — neither silently disappeared during
      implementation.
