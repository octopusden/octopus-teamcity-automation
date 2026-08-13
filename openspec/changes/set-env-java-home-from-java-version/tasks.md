# Tasks

## 1. Mapping resolution logic (Decisions 4, 7)

- [x] 1.1 `JavaHomeMapping.resolve(javaVersion: String, overrides: Map<Int, String>, template: String): String`
      as a pure object
      (`src/main/kotlin/org/octopusden/octopus/automation/teamcity/utils/javahome/JavaHomeMapping.kt`).
      No hardcoded formula and no built-in per-major exceptions (Decision 4). `template` is
      non-nullable — `JavaHomeMappingOption.parse` (task 2) guarantees a template exists whenever
      a mapping was supplied at all. The `{major}` placeholder constant
      (`JavaHomeMapping.PLACEHOLDER`) lives here, since both `resolve` and `parse`'s validation
      need it.
- [x] 1.2 Unit tests, `src/test/kotlin/utils/javahome/JavaHomeMappingTest.kt` (package
      `utils.javahome`):
  - [x] 1.2.1 explicit override for the resolved major wins over a present leading template
  - [x] 1.2.2 major with no override substitutes into the leading template
  - [x] 1.2.3 an unlisted major (e.g. `27`) resolves via the same template, no code change
  - [x] 1.2.4 major `8` with only the template resolves to `env.JDK_8_0`, **not** `env.JDK_1_8` —
        there is no built-in major-8 exception
  - [x] 1.2.5 a caller-supplied `8=env.JDK_1_8` override reproduces the historical naming
  - [x] 1.2.6 major extraction (Decision 7): an override keyed `8` matches both `"1.8"` and `"8"`
  - [x] 1.2.7 an override value with no `env.` prefix is used as-is (Decision 5)
  - [x] 1.2.8 multi-segment versions: `"21.0.1"` → `21`, `"1.8.0_292"` → `8`
  - [x] 1.2.9 a value with no derivable major (`"   "`, `"<value>"`, `"17-ea"`, an `Int`
        overflow, `"0"`) resolves to `null` rather than raising

## 2. `--java-home-mapping` parsing and validation (Decisions 2, 3, 5, 6, 8)

Parsing is a pure function — string in, parsed value or `BadParameterValue` out — so it is
unit-tested at its own seam rather than through process-level tests against the shadow jar.

- [x] 2.1 `JavaHomeMappingOption.kt` —
      `data class JavaHomeMappingOption(val overrides: Map<Int, String>, val template: String)`
      with `companion object { fun parse(raw: String, optionName: String): JavaHomeMappingOption }`:
      splits on `SPLIT_SYMBOLS` preserving order; rejects any blank segment (leading, trailing,
      or consecutive separators) before selecting the template or parsing overrides; requires the
      first segment to be bare and validates it as the template (non-`%`-wrapped, exactly one
      placeholder); validates every remaining segment as a numeric override (non-blank value,
      non-`%`-wrapped, no placeholder, no duplicate key), rejecting any later bare segment; fails
      via a private `fail(message)` helper naming the offending entry. Split into `parseTemplate`
      / `parseOverride` / `isWrapped` / `fail` to stay under detekt's `ThrowsCount` and
      line-length limits. Validation is inline string checks, no standalone regexes.
- [x] 2.2 `optionName` is a parameter, not a constant here —
      `TeamcityCreateBuildChainCommand.JAVA_HOME_MAPPING` is the only production definition of
      the `--java-home-mapping` string, read by both the clikt option registration and the
      `.convert { ... }` call.
- [x] 2.3 `resolveOrNull(javaVersion: String?): String?` on `JavaHomeMappingOption` itself, not a
      helper on the command class — the data class already owns `overrides`/`template`, and
      `TeamcityCreateBuildChainCommand` is at detekt's `TooManyFunctions` limit.
- [x] 2.4 Unit tests, `src/test/kotlin/utils/javahome/JavaHomeMappingOptionTest.kt`:
  - [x] 2.4.1 `resolveOrNull` resolves a non-null `javaVersion`, returns `null` for a null one
        and for one with no derivable major
  - [x] 2.4.2 valid mapping with a leading template + overrides parses
  - [x] 2.4.3 overrides only, no leading template, throws `BadParameterValue`
  - [x] 2.4.4 a bare entry not in the first position throws
  - [x] 2.4.5 an override value with no `env.` prefix parses (Decision 5)
  - [x] 2.4.6 non-numeric override key throws, naming the bad entry
  - [x] 2.4.7 already-`%`-wrapped override value throws
  - [x] 2.4.8 blank override value throws
  - [x] 2.4.9 override value containing `{major}` throws
  - [x] 2.4.10 duplicate override key throws
  - [x] 2.4.11 leading template with zero, or more than one, `{major}` throws
  - [x] 2.4.12 already-`%`-wrapped leading template throws
  - [x] 2.4.13 a leading, trailing, or consecutive separator throws, naming the blank entry

## 3. Resolve and always write `env.JAVA_HOME` in `createBuildChain` (Decisions 4, 9, 10)

- [x] 3.1 `TeamcityCreateBuildChainCommand.kt` — `javaHomeMappingRaw` option (plain `String?`, no
      `.required()`/`.default()`) and a lazily-parsed `javaHomeMapping: JavaHomeMappingOption?`
      that treats a blank/absent raw value the same way (`null`), plus the `JAVA_HOME_MAPPING`
      companion constant. `metarunners/CreateTeamCityBuildChain.xml` passes
      `--java-home-mapping=%JAVA_HOME_MAPPING%` with `JAVA_HOME_MAPPING` defaulting to `""` and
      an "optional" description — the blank-is-absent handling is what keeps every existing
      build config using this metarunner working unchanged.
- [x] 3.2 `createBuildChain` calls
      `setParameter(ConfigurationType.PROJECT, project.id, "env.JAVA_HOME", resolveJavaHome(...))`
      unconditionally, alongside the other project-level writes (`COMPONENT_NAME`,
      `PROJECT_VERSION`). `%...%` wrapping is applied only to the mapping-resolved case — the
      parent-fallback value is used as-is, since it is already whatever was previously stored.
- [x] 3.3 `resolveJavaHome` is a private member of the command. It warns, naming the value, when
      a mapping was supplied but a non-null `javaVersion` yielded no major (Decision 7), then
      falls back. Its parent-fallback lookup
      catches `FeignException.NotFound` (`client.getParameter` raises rather than returning
      `null` for an absent parameter) and treats it as empty, ending in `?: ""` — Decision 9's
      "always written, empty if nothing resolves".
- [x] 3.4 Functional tests in `ApplicationTest.kt` —
      `executeForCreateBuildChainCommand` gained an optional `javaHomeMapping: String? = null`
      appended to the arg list only when non-null; new `testTeamCityCreateBuildChainForJavaHome`
      reuses the `default-jdk-component` / `custom-jdk-component` fixtures
      (`TestComponents.groovy:130-156`):
  - [x] 3.4.1 no mapping, parent has no `env.JAVA_HOME` → written with an empty string, not left
        unset; also asserted on the RC/checklist/release build configs (Decision 10 inheritance)
  - [x] 3.4.2 no mapping, parent has `env.JAVA_HOME` → the parent's value, for both fixtures
  - [x] 3.4.3 mapping with overrides covering both majors → `%env.JDK_1_8%` for major 8,
        `%env.JDK_11_0%` for major 11
  - [x] 3.4.4 mapping whose overrides do not cover the component's major → the leading template
        applies

## 4. `JDK_VERSION` deprecation note (Decision 11)

- [x] 4.1 `docs/tech-debt/TD-001-jdk-version-param-removal.md` records the removal condition
      (Status / Context / Symptoms / Acceptance criteria / Related), following the one-file-per-item
      convention. A comment on the `JDK_VERSION`-setting block in
      `TeamcityCreateBuildChainCommand.kt` points at it. `design.md` Decision 11 and
      `proposal.md`'s deprecation note reference the same file rather than restating the
      condition inline.
- [x] 4.2 No behavior or test change — `JDK_VERSION` continues exactly as today.

## 5. Finalization

- [ ] 5.1 Full suite green: `./gradlew build` — **not run locally**. This environment has no
      running Docker daemon, and `build` pulls in the docker-compose-backed `test` task
      unconditionally (there is no separate unit-test task). Verified locally instead:
      `./gradlew compileKotlin compileTestKotlin` clean, and the pure-logic suite green via
      `./gradlew test --tests "utils.javahome.*"` with the compose tasks excluded. The
      Docker-backed functional suite, including `testTeamCityCreateBuildChainForJavaHome`, runs
      on CI per the `build-verification` skill.
- [x] 5.2 Static analysis clean: `./gradlew detekt ktlintCheck` green against every file touched
      or added.
- [x] 5.3 Every `Out of scope` item in `proposal.md` holds:
  - `JDK_VERSION` setting logic untouched (only a comment was added above it);
  - no TeamCity-project-parameter-based override path — the mapping is CLI-flag-only;
  - no generic parameter-mapping mechanism beyond `env.JAVA_HOME`;
  - no fixed-prefix requirement in validation (asserted by a test);
  - `src/main` contains no hardcoded formula or mapping table — the only `JDK_` hits are the
    unchanged `JDK_VERSION` parameter name and example strings in CLI `help` text.
- [x] 5.4 Both accepted risks in `design.md` still hold of the shipped code: the unconditional
      project-level write (silent overwrite on re-run, including blanking to `""`), and the
      mapping being consulted only when `--java-home-mapping` is supplied.
