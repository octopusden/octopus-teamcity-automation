# TD-001: Remove the `JDK_VERSION` build-type parameter from `create-build-chain`

## Status

Open.

## Context

`TeamcityCreateBuildChainCommand.createBuildChain()` sets a `JDK_VERSION` build-type parameter
on the compile build config only, derived from the component-registry's `javaVersion` field
(e.g. `"1.8"`, `"11"`). It's a bare version string, useful only if a build template downstream
knows how to turn it into a JDK path.

The same command now also resolves and always writes an `env.JAVA_HOME` **project**-level
parameter — a proper TeamCity parameter reference (e.g. `%env.JDK_17_0%`), from the optional
`--default-java-home` CLI option or, failing that, the parent project's own `env.JAVA_HOME` —
inherited by every build config in the chain, not just compile. `JDK_VERSION` is kept only so
teams still consuming it don't break; both parameters are set side by side for now.

## Symptoms (when this will hurt)

- Every `create-build-chain` run resolves and writes two parameters for one concept ("which JDK
  to use") once all consumers have migrated to `env.JAVA_HOME`.
- A future contributor may treat `JDK_VERSION` as load-bearing and build on top of it, deepening
  the duplication this item exists to remove.

## Acceptance criteria

1. Confirm no build template still reads `%JDK_VERSION%` — all consume `%env.JAVA_HOME%`.
2. Remove the `JDK_VERSION`-setting block in
   `TeamcityCreateBuildChainCommand.kt::createBuildChain`.
3. Remove or update `ApplicationTest.kt::testTeamCityCreateBuildChainForJDKVersion`.

No specific date — this is consumer-migration-gated, not time-gated.

## Related

- `src/main/kotlin/org/octopusden/octopus/automation/teamcity/TeamcityCreateBuildChainCommand.kt` —
  both parameters are set in `createBuildChain`.
- `src/test/kotlin/ApplicationTest.kt::testTeamCityCreateBuildChainForJDKVersion` — current
  coverage of the parameter being removed.
- `src/test/kotlin/ApplicationTest.kt::testTeamCityCreateBuildChainForJavaHome` — coverage of the
  `env.JAVA_HOME` parameter this item leaves as the sole survivor.
