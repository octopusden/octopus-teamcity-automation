# Java home resolution

## Purpose

Governs how `create-build-chain` resolves and writes the `env.JAVA_HOME` TeamCity project
parameter for a newly created component project, from an optional caller-supplied
`--default-java-home` reference name and, failing that, the parent project's own `env.JAVA_HOME`.
Does not govern `JDK_VERSION`, which is unchanged. Resolution does not depend on the component
registry's `javaVersion` field.

## ADDED Requirements

### Requirement: `--default-java-home` accepts a bare TeamCity parameter reference name

`create-build-chain` SHALL accept an optional `--default-java-home` option whose value, when
supplied non-blank, SHALL be a bare TeamCity parameter name (not `%`-wrapped).

- An already-`%`-wrapped value SHALL be rejected before any TeamCity project is created.
- A blank or absent value SHALL be treated as "not supplied" (see the fallback requirement below).

#### Scenario: bare reference name is accepted

- **WHEN** `--default-java-home=env.JDK_17_0` is supplied
- **THEN** the command proceeds, and `env.JAVA_HOME` resolves to `%env.JDK_17_0%`

#### Scenario: already-wrapped value is rejected

- **WHEN** `--default-java-home=%env.JDK_17_0%` is supplied
- **THEN** the command fails before creating any TeamCity project, with a message naming the
  invalid value

#### Scenario: blank value is treated as omitted

- **WHEN** `--default-java-home=` (blank) is supplied
- **THEN** the command proceeds as if the option were absent entirely

### Requirement: `--default-java-home`, when supplied non-blank, resolves `env.JAVA_HOME` directly

When `--default-java-home` is supplied non-blank, the command SHALL resolve `env.JAVA_HOME` to
that value wrapped as `%<value>%`, regardless of the parent project's own `env.JAVA_HOME` or
anything in the component registry.

#### Scenario: option takes precedence over the parent project's own value

- **WHEN** `--default-java-home=env.JDK_21_0` is supplied and `parentProjectId` has
  `env.JAVA_HOME` set to `%env.JDK_1_8%`
- **THEN** the new component project's `env.JAVA_HOME` is set to `%env.JDK_21_0%`

### Requirement: `--default-java-home` omitted falls back to the parent project's `env.JAVA_HOME`, used as-is

When `--default-java-home` is not supplied (or blank), the command SHALL read the existing
`env.JAVA_HOME` project parameter from `parentProjectId` and use it **without** re-wrapping.

#### Scenario: no option supplied, parent has a value

- **WHEN** `--default-java-home` is not supplied and `parentProjectId` has `env.JAVA_HOME` set to
  `%env.JDK_1_8%`
- **THEN** the new component project's `env.JAVA_HOME` is set to `%env.JDK_1_8%`

### Requirement: `env.JAVA_HOME` is always written at project level, empty if nothing resolves

The command SHALL write `env.JAVA_HOME` as a project-level parameter on the newly created
component project (not build-type-level) unconditionally. If neither `--default-java-home` nor
the parent project's `env.JAVA_HOME` yields a value, the command SHALL write `env.JAVA_HOME` with
an **empty string**, not skip the write.

#### Scenario: no option supplied, parent has no value either

- **WHEN** `--default-java-home` is not supplied and `parentProjectId` has no `env.JAVA_HOME` set
- **THEN** `env.JAVA_HOME` is still written for the new component project, with an empty value

#### Scenario: value inherited by every build configuration in the chain

- **WHEN** `env.JAVA_HOME` resolves to `%env.JDK_17_0%` for a component whose build chain includes
  compile, RC, checklist, and release build configurations
- **THEN** all four build configurations resolve `env.JAVA_HOME` to `%env.JDK_17_0%` through
  TeamCity's normal project-to-build-type parameter inheritance, with no build-type-level override
  written explicitly
