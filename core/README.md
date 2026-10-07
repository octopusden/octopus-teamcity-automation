# octopus-teamcity-automation-core

The logic behind every `octopus-teamcity-automation` CLI command, without the CLI. Each command parses
its options and calls one class here:

| CLI command | Class | Package |
|---|---|---|
| `create-build-chain` | `BuildChainCreator` | `buildchain` |
| `replace-vcs-root` | `VcsRootReplacer` | `vcsroot` |
| `update-parameter set` / `increment` | `ParameterUpdater` | `parameter` |
| `upload-metarunners` | `MetarunnerUploader` | `metarunner` |
| `get-build-agent-req` | `AgentRequirementsReport` | `agent` |
| `post-github-status` | `CommitStatusPublisher` | `github` |

Packages are under `org.octopusden.octopus.automation.teamcity`. The coordinates are
`org.octopusden.octopus.automation.teamcity:octopus-teamcity-automation-core`; how to resolve them from GitHub
Packages is in the [repository README](../README.md#using-the-octopus-teamcity-automation-core-library).

## API

Only the types below are public. Everything else in the module is `internal` and may change in any
release. The TeamCity classes take a ready `TeamcityClient`; the caller owns it. Logging goes through
SLF4J; the caller provides the binding.

| Package | Public types |
|---|---|
| `buildchain` | `BuildChainCreator`, `BuildChainRequest`, `BuildChainResult`, `BuildChainConfig`, `BuildChainException` (sealed): `UnsupportedBuildSystemException`, `UnsupportedVcsTypeException`, `UnsupportedVcsRootLayoutException` |
| `vcsroot` | `VcsRootReplacer` |
| `parameter` | `ParameterUpdater`, `ParameterTargets` |
| `metarunner` | `MetarunnerUploader` |
| `agent` | `AgentRequirementsReport`, `AgentRequirementRow` |
| `github` | `CommitStatusPublisher`, `CommitStatus`, `CommitState` |

## Build chain

`BuildChainCreator(teamcityClient, componentsRegistryClient, config).create(request)` creates a
component's build chain under a parent project from its Components Registry metadata:

```
[1.0] Compile & UT [AUTO]
[2.0] Release Candidate [Manual]              only for an explicitly and externally distributed component, or when forced
[3.0] Release Checklist Validation [MANUAL]   only with a Release Candidate, and when requested
[n.0] Release [Manual]
```

- `BuildChainRequest`: `parentProjectId`, `componentName`, `minorVersion`, `createChecklist` (default
  `true`), `createRcForce` (default `false`). Blank values and values with leading or trailing
  whitespace are rejected.
- `BuildChainResult`: ids of the created project and build configurations; `rcBuildTypeId` and
  `checklistBuildTypeId` are `null` when those were not created.
- `BuildChainConfig`: the five template ids and the name of the JDK version parameter; the defaults are
  the ones the CLI uses.

### Template contract

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

### Failure semantics

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

## VCS root replacement

`VcsRootReplacer(client).replace(oldVcsRoot, newVcsRoot, dryRun)` moves every build configuration that
uses the old Git repository onto the new one:
- attaches a Git VCS root for the new URL, reusing one in the same project with the same URL and branch
  (the build configuration's `VCS_BRANCH`, default `refs/heads/master`), and keeps the checkout rules
- moves VCS labeling features bound to the old roots, then detaches the old roots
- rewrites the URL (and the push URL, where set) of Git VCS roots that still point at the old repository

Both URLs must pass `VcsRootReplacer.isValidGitUrl`: lowercase `ssh://user@host/path.git`,
`user@host:path.git` or `https://host/path.git`. With `dryRun` nothing changes in TeamCity and the same
report is logged. A new VCS root uses fixed settings (private key authentication, user `git`, branch
spec `+:refs/heads/*`, submodules ignored, untracked files cleaned on branch change).

## Parameters

`ParameterUpdater(client)` works on a `ParameterTargets(name, projectIds, buildTypeIds)`; at least one
project or build configuration is required.
- `set(targets, value)` sets the value everywhere.
- `increment(targets, current = "")` increments the last numeric component of each value (`1.2` →
  `1.3`, `1.2-7` → `1.2-8`). With `current`, only values whose components `current` starts with are
  incremented (`current = 1.2.7` increments `1.2`, not `1.3`). A value that cannot be read or incremented
  is skipped with a warning; the call does not fail.

## Metarunners

`MetarunnerUploader(client).upload(projectId, zip)` uploads every `.xml` entry of the zip stream as a
metarunner of the project, named after its file name. The caller opens and closes the stream.

## Agent requirements

`AgentRequirementsReport(client).collect(includeArchived = false)` returns one `AgentRequirementRow` per
agent requirement of every build configuration: project, build configuration, requirement type, the
requirement's `property-name` and `property-value`, and whether it is disabled, paused or archived.
Build configurations of archived projects are left out unless `includeArchived`. Rendering is the
caller's: the CLI writes these rows as `;`-separated CSV.

## GitHub commit statuses

`CommitStatusPublisher(token, apiUrl, connectTimeout, readTimeout).post(status)` posts a
`CommitStatus(owner, repo, commit, state, context, description)` to
`POST /repos/{owner}/{repo}/statuses/{sha}`. `context` defaults to `TeamCity / build` and must match the
check a branch protection rule requires; an empty description is omitted. The API URL defaults to
`https://api.github.com`, and both timeouts to 10 seconds. This is the one part of the library that
talks to GitHub instead of TeamCity; it is here because the TeamCity metarunners use it.
