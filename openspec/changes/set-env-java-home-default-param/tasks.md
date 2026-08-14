# Tasks

## 1. `--default-java-home` option and validation (Decisions 1–3)

- [ ] 1.1 `TeamcityCreateBuildChainCommand.kt` — new nullable `defaultJavaHome: String?` option
      (`option(DEFAULT_JAVA_HOME, help = ...)`, `.convert { it.trim() }`, no `.required()`/
      `.default()`), plus the `DEFAULT_JAVA_HOME = "--default-java-home"` companion constant
      alongside `PARENT`/`COMPONENT`/`VERSION`/`CR`/`CREATE_CHECKLIST`/`CREATE_RC_FORCE`.
- [ ] 1.2 Reject an already-`%`-wrapped value at parse time (`BadParameterValue`, naming the
      value) via a `.check` or `.validate` on the option. A blank/whitespace-only value is treated
      as absent (not an error) — normalize to `null` before use.

## 2. Resolve and always write `env.JAVA_HOME` (Decisions 4–6)

- [ ] 2.1 Private `resolveJavaHome(): String` on `TeamcityCreateBuildChainCommand`:
      1. `defaultJavaHome?.takeIf { it.isNotBlank() }?.let { return "%$it%" }`
      2. else `client.getParameter(ConfigurationType.PROJECT, parentProjectId, "env.JAVA_HOME")`,
         catching `FeignException.NotFound` → `null`
      3. else `""`
- [ ] 2.2 `createBuildChain` calls `setProjectParameter(project.id, "env.JAVA_HOME",
      resolveJavaHome())` unconditionally, alongside the existing `COMPONENT_NAME` /
      `PROJECT_VERSION` project-level writes. No change to the `JDK_VERSION` block.

## 3. Metarunner passthrough

- [ ] 3.1 `metarunners/CreateTeamCityBuildChain.xml` — new `%DEFAULT_JAVA_HOME%` parameter,
      defaulting to `""`, described as optional, passed as
      `--default-java-home=%DEFAULT_JAVA_HOME%` so every existing build config using this
      metarunner keeps working unchanged.

## 4. Tests

- [ ] 4.1 Functional tests in `ApplicationTest.kt` — `executeForCreateBuildChainCommand` gains an
      optional `defaultJavaHome: String? = null` appended to the arg list only when non-null; new
      `testTeamCityCreateBuildChainForJavaHome`:
  - [ ] 4.1.1 option supplied → `env.JAVA_HOME` written as `%<value>%` on the project, inherited by
        compile/RC/checklist/release build configs
  - [ ] 4.1.2 option supplied, already `%`-wrapped → command fails before creating any project
  - [ ] 4.1.3 option omitted, parent has `env.JAVA_HOME` → parent's value used as-is (no
        re-wrapping)
  - [ ] 4.1.4 option omitted, parent has no `env.JAVA_HOME` → written with an empty string
  - [ ] 4.1.5 option supplied and blank (`""`) → treated as omitted, falls back to parent
        resolution (4.1.3/4.1.4)

## 5. Finalization

- [ ] 5.1 `./gradlew compileKotlin compileTestKotlin` clean.
- [ ] 5.2 `./gradlew detekt ktlintCheck` clean against every file touched.
- [ ] 5.3 Full suite (including the new functional tests) green on CI per the
      `build-verification` skill — this environment has no Docker daemon for the
      compose-backed `test` task.
- [ ] 5.4 Confirm every `Out of scope` item in `proposal.md` holds: no read of
      `component.buildParameters?.javaVersion`, no mapping/template mechanism, `JDK_VERSION`
      logic byte-for-byte unchanged.
