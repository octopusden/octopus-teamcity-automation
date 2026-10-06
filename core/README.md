# build-chain

Creates a component's TeamCity build chain under a parent project from the component's
Components Registry metadata:

```
[1.0] Compile & UT [AUTO]
[2.0] Release Candidate [Manual]              only for an explicitly and externally distributed component, or when forced
[3.0] Release Checklist Validation [MANUAL]   only with a Release Candidate, and when requested
[n.0] Release [Manual]
```

The `create-build-chain` CLI command is a thin adapter over this module. The coordinates are
`org.octopusden.octopus.automation.teamcity:build-chain`; how to resolve them from GitHub Packages is in
the [repository README](../README.md#using-the-build-chain-library).

## API

Only these types are public. Everything else in the module is `internal` and may change in any release.

| Type | Role |
|---|---|
| `BuildChainCreator` | `create(request)` does the work. Takes a ready `TeamcityClient` and `ComponentsRegistryServiceClient`, and a `BuildChainConfig`. |
| `BuildChainRequest` | `parentProjectId`, `componentName`, `minorVersion`, `createChecklist` (default `true`), `createRcForce` (default `false`). Blank values and values with leading or trailing whitespace are rejected. |
| `BuildChainResult` | Ids of the created project and build configurations. `rcBuildTypeId` and `checklistBuildTypeId` are `null` when those were not created. |
| `BuildChainConfig` | The five template ids and the name of the JDK version parameter. The defaults are the ones the CLI uses. |
| `BuildChainException` | Sealed: `UnsupportedBuildSystemException`, `UnsupportedVcsTypeException`, `UnsupportedVcsRootLayoutException`. |

Logging goes through SLF4J; the caller provides the binding.

## Template contract

The created configurations rely on what the templates and the parent project provide, and set the
parameters below. A template that does not read a parameter simply ignores it.

**The parent project must have**
- the JDK version parameter (`JDK_VERSION` by default, `BuildChainConfig.jdkVersionParameter`). The
  compile configuration overrides it only when the component's Java version differs from the parent's.
- optionally `RELEASE_NOTES_REPORT_TEMPLATE_CHECKOUT_DIR`: a registry Checkout Directory equal to it is rejected.

**The templates must exist** under the ids in `BuildChainConfig`: Gradle compile (also used for
`PROVIDED` and `IN_CONTAINER`), Maven compile, Release Candidate, Release Checklist Validation, Release.

**Parameters the chain sets**

| Parameter | Where | Value |
|---|---|---|
| `COMPONENT_NAME`, `PROJECT_VERSION` | created project | component name, minor version |
| JDK version parameter | Compile | component's Java version, only when it differs from the parent's |
| `BUILD_VERSION` | RC, Checklist, Release | `%dep.<compile id>.BUILD_VERSION%` |
| `BASE_CONFIGURATION_ID` | Release | compile configuration id |
| `WORK_DIR`, `COMPONENT_CONFIG_DIR` | every configuration, when the component has a Build Working Directory | `%teamcity.build.checkoutDir%/<build working directory>` |
| `BUILD_VERSION_FORMAT_FILE` | every configuration that has a step of type `CalculateBuildVersion`, when the component has a Build Working Directory | `<build working directory>/build-version-format.properties` |

**Steps.** The Release configuration's `IncrementTeamCityBuildConfigurationParameter` step (matched by
name or type) is disabled. If it is missing, a warning is logged and the chain is still created.

**Dependencies.** Snapshot dependencies, each cancelling the build when the dependency fails: RC on
Compile, Checklist on RC, and Release on RC (on Compile when there is no RC).

**VCS roots.** One Git VCS root per registry root, named `<project id>_VCS_ROOT` and
`<project id>_VCS_ROOT_<position>`, with checkout rules from the registry's Checkout Directory and Source
Path. Authentication is fixed policy, not configurable: `authMethod=PRIVATE_KEY_DEFAULT`,
`username=git`, `userForTags=tcagent`, `ignoreKnownHosts=true`, branch spec `+:<default>`.

**Access.** The component owner and release managers get `PROJECT_ADMIN` on the created project. A user
missing from TeamCity is logged as a warning and skipped.

## Failure semantics

`create` reads and validates everything it can before it creates the project, so these leave
**nothing** in TeamCity:
- a missing parent project or a Components Registry error (the clients' own exceptions)
- any `BuildChainException`: an unsupported build system, a non-Git root, or VCS roots that cannot be
  placed (a repeated repository, several roots without a Checkout Directory, a Checkout Directory equal
  to `RELEASE_NOTES_REPORT_TEMPLATE_CHECKOUT_DIR`)

Anything that fails **after** the project is created leaves a **partial chain**. The commonest causes are
a template id from `BuildChainConfig` that does not exist, or a parent project without the JDK version
parameter. The exception is the TeamCity client's, and no `BuildChainResult` is returned, so the caller
does not get the project id. A retry then fails because a project with that name already exists under
the parent. Before retrying, find the project by name under the parent and delete it.
