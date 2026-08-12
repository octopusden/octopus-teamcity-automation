## Purpose

Governs how `create-build-chain` resolves and writes the `env.JAVA_HOME` TeamCity parameter for
a newly created component project, based on the component-registry's `javaVersion` and an
optional caller-supplied mapping of a required leading default template plus per-major
overrides. Does not govern the existing `JDK_VERSION` parameter, which is unchanged by this
capability. No major-version-to-parameter-name mapping or formula is built into this tool —
everything used for resolution is caller-supplied via `--java-home-mapping`.

## ADDED Requirements

### Requirement: `--java-home-mapping`, when supplied, must start with a bare leading template entry

`create-build-chain` SHALL accept an optional `--java-home-mapping` option whose value is one
or more entries separated by `,` or `;`.

- If the option is supplied, its **first entry SHALL be bare** (no `key=` prefix) — the default
  template, containing exactly one `{major}` placeholder, e.g. `env.JDK_{major}_0`.
- An option value consisting only of `key=value` override entries, with no leading bare
  template, SHALL be rejected.
- Every entry after the first SHALL be an **override**: a `key=value` pair.
- A bare entry appearing at any position other than 0 SHALL be rejected.

#### Scenario: valid mapping with a leading template and overrides is accepted

- **WHEN** `--java-home-mapping=env.JDK_{major}_0,8=env.JDK_1_8,11=env.JDK_11_0` is supplied
- **THEN** the command proceeds and all entries are available for resolution

#### Scenario: mapping with only overrides, no leading template, is rejected

- **WHEN** `--java-home-mapping=8=env.JDK_1_8,11=env.JDK_11_0` is supplied
- **THEN** the command fails before creating any TeamCity project, with a message noting the
  leading template entry is required

#### Scenario: a bare entry NOT in the first position is rejected

- **WHEN** `--java-home-mapping=8=env.JDK_1_8,env.JDK_{major}_0` is supplied
- **THEN** the command fails before creating any TeamCity project, with a message noting the
  bare entry is only valid as the first segment

### Requirement: mapping entries are validated for structural correctness, not for a naming prefix

Each entry SHALL be validated against structural rules only. No entry (override or template)
SHALL be required to start with `env.` or any other fixed prefix — this tool cannot verify a
value corresponds to a real TeamCity agent parameter regardless of prefix, so only mistakes
this tool can detect with certainty are rejected:

- an override key SHALL parse as a positive integer;
- no override key SHALL repeat within a single option value;
- an override value SHALL be non-blank, SHALL NOT already be `%`-wrapped, and SHALL NOT contain
  the `{major}` placeholder;
- the leading template SHALL be non-blank, SHALL NOT already be `%`-wrapped, and SHALL contain
  **exactly one** `{major}` occurrence.

#### Scenario: override value without an `env.` prefix is accepted

- **WHEN** `--java-home-mapping=env.JDK_{major}_0,8=JDK_1_8` is supplied
- **THEN** the command proceeds; the override for major `8` resolves to `%JDK_1_8%`

#### Scenario: non-numeric override key is rejected

- **WHEN** `--java-home-mapping=env.JDK_{major}_0,abc=env.JDK_1_8` is supplied
- **THEN** the command fails before creating any TeamCity project, with a message naming the
  invalid entry

#### Scenario: already-wrapped override value is rejected

- **WHEN** `--java-home-mapping=env.JDK_{major}_0,8=%env.JDK_1_8%` is supplied
- **THEN** the command fails before creating any TeamCity project, with a message naming the
  invalid entry

#### Scenario: override value containing a `{major}` placeholder is rejected

- **WHEN** `--java-home-mapping=env.JDK_{major}_0,8=env.JDK_{major}_8` is supplied
- **THEN** the command fails before creating any TeamCity project, with a message naming the
  invalid entry (placeholders are only valid in the leading template)

#### Scenario: duplicate override key is rejected

- **WHEN** `--java-home-mapping=env.JDK_{major}_0,8=env.JDK_1_8,8=env.JDK_8_0` is supplied
- **THEN** the command fails before creating any TeamCity project, with a message naming the
  duplicated key

#### Scenario: leading template without a `{major}` placeholder is rejected

- **WHEN** `--java-home-mapping=env.JDK_HOME,8=env.JDK_1_8` is supplied
- **THEN** the command fails before creating any TeamCity project, with a message naming the
  invalid leading entry

#### Scenario: leading template with more than one `{major}` placeholder is rejected

- **WHEN** `--java-home-mapping=env.JDK_{major}_{major}` is supplied
- **THEN** the command fails before creating any TeamCity project, with a message naming the
  invalid leading entry

### Requirement: the component's major version is derived from `javaVersion`'s trailing segment

The command SHALL derive a major-version integer from `component.buildParameters.javaVersion`
by taking the substring after the last `.` (or the whole string if there is no `.`) and parsing
it as an integer. This derived major SHALL be used for both override lookup and template
substitution.

#### Scenario: dotted legacy form resolves to its trailing segment

- **WHEN** `javaVersion` is `"1.8"`
- **THEN** the derived major version is `8`

#### Scenario: bare modern form resolves to itself

- **WHEN** `javaVersion` is `"17"`
- **THEN** the derived major version is `17`

#### Scenario: an override keyed by the derived major matches regardless of the source form

- **WHEN** `--java-home-mapping=env.JDK_{major}_0,8=env.JDK_1_8` is supplied and the
  component's `javaVersion` is `"1.8"`
- **THEN** `env.JAVA_HOME` is resolved to `%env.JDK_1_8%` (the override for major `8` matches)

### Requirement: explicit override entry resolves the component's major version

When `--java-home-mapping` is supplied and the component has a non-null `javaVersion`, the
command SHALL resolve the component's derived major version against the supplied override
entries first, before considering the leading template.

#### Scenario: mapped major version is used

- **WHEN** `--java-home-mapping=env.JDK_{major}_0,11=env.JDK_11_0` is supplied and the
  component's `javaVersion` is `"11"`
- **THEN** `env.JAVA_HOME` is resolved to `%env.JDK_11_0%`

#### Scenario: explicit override wins over the leading template for the same major

- **WHEN** `--java-home-mapping=env.JDK_{major}_0,17=env.JDK_17_CUSTOM` is supplied and the
  component's `javaVersion` is `"17"`
- **THEN** `env.JAVA_HOME` is resolved to `%env.JDK_17_CUSTOM%`, not `%env.JDK_17_0%`

### Requirement: unmapped major version substitutes into the caller-supplied leading template

When the component has a non-null `javaVersion` and the resolved major version has no explicit
override, the command SHALL substitute the major version into the leading template's `{major}`
placeholder.

#### Scenario: unmapped major version uses the supplied leading template

- **WHEN** `--java-home-mapping=env.JDK_{major}_0,11=env.JDK_11_0` is supplied and the
  component's `javaVersion` is `"17"`
- **THEN** `env.JAVA_HOME` is resolved to `%env.JDK_17_0%`

#### Scenario: a future, previously-unlisted major resolves via the same template without a code change

- **WHEN** `--java-home-mapping=env.JDK_{major}_0,11=env.JDK_11_0` is supplied and the
  component's `javaVersion` is `"27"`
- **THEN** `env.JAVA_HOME` is resolved to `%env.JDK_27_0%`

#### Scenario: caller-supplied override reproduces the historical major-8 naming when they choose to

- **WHEN** `--java-home-mapping=env.JDK_{major}_0,8=env.JDK_1_8` is supplied and the
  component's `javaVersion` is `"1.8"`
- **THEN** `env.JAVA_HOME` is resolved to `%env.JDK_1_8%` via the explicit override, not
  `%env.JDK_8_0%` via the template — this tool does not special-case major 8 on its own; the
  caller must supply the override if they want it

### Requirement: `--java-home-mapping` omitted, or the component has no `javaVersion`, falls back to the parent project's `env.JAVA_HOME`

When `--java-home-mapping` is not supplied at all, or the component has no `javaVersion`, the
command SHALL NOT skip setting `env.JAVA_HOME`. Instead it SHALL read the existing
`env.JAVA_HOME` project parameter from `parentProjectId` and use that value, if present.

#### Scenario: no mapping supplied, parent has a default

- **WHEN** `--java-home-mapping` is not supplied and `parentProjectId` has `env.JAVA_HOME` set
  to `%env.JDK_1_8%`
- **THEN** the new component project's `env.JAVA_HOME` is set to `%env.JDK_1_8%`, regardless of
  the component's own `javaVersion`

#### Scenario: no mapping supplied, parent has no default

- **WHEN** `--java-home-mapping` is not supplied and `parentProjectId` has no `env.JAVA_HOME`
  set
- **THEN** no `env.JAVA_HOME` parameter is written for the new component project

#### Scenario: mapping supplied but component has no `javaVersion`

- **WHEN** `--java-home-mapping=env.JDK_{major}_0,11=env.JDK_11_0` is supplied and the
  component's `javaVersion` is null
- **THEN** the parent-project fallback is used, exactly as when the mapping is absent

### Requirement: resolved `env.JAVA_HOME` is written at project level

The command SHALL write the resolved `env.JAVA_HOME` value as a project-level parameter on the
newly created component project (not a build-type-level parameter), so it is inherited by every
build configuration created under that project.

#### Scenario: value inherited by non-compile build configurations

- **WHEN** `env.JAVA_HOME` resolves to `%env.JDK_17_0%` for a component whose build chain
  includes compile, RC, checklist, and release build configurations
- **THEN** all four build configurations resolve `env.JAVA_HOME` to `%env.JDK_17_0%` through
  TeamCity's normal project-to-build-type parameter inheritance, without any build-type-level
  override being written explicitly
