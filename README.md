# octopus-teamcity-automation

TeamCity automation for octopus components, in three forms over one implementation:

| Module | What it is |
|---|---|
| `core` | The `octopus-teamcity-automation-core` library: build chains, VCS root replacement, parameters, metarunners, agent requirements, GitHub commit statuses |
| `cli` | The `octopus-teamcity-automation` command line tool and its metarunners |
| `server` | `teamcity-automation-service`, a REST service over `core`, shipped as a docker image |
| `common`, `client` | The service's DTOs and Feign client, for services that call it |
| `ft` | Functional tests of the service image |

## The Report Export Tool in CSV Format

Run the Command:
```bash
java -jar octopus-teamcity-automation.jar \
  --url=https://your-teamcity-server.com \
  --user=your-username \
  --password=your-password \
  get-build-agent-req \
  --file=/path/to/output/report.csv
```
### Command Arguments:

- `-url` - URL of the TeamCity server (e.g., https://teamcity.example.com)
- `--user` - TeamCity username
- `--password` - TeamCity password
- `get-build-agent-req` - Command to export build agent requirements
- `--file` - Path to the output CSV file
- `--archived` - Optional flag to archive the report after generation(default: false)

### CSV Output Format:
The generated file (report.csv) will contain the following columns:

| Project ID|Project Name | Build Type ID | Build Type Name | Agent Requirement Type | Agent Requirement Name | Agent Requirement Value |
|---|---|---|------------------|---|---|---|

## Using the octopus-teamcity-automation-core library

Every CLI command is a thin adapter over the `octopus-teamcity-automation-core` library, which does the same
work without the CLI: build chains, VCS root replacement, parameter updates, metarunner uploads, agent
requirement reports and GitHub commit statuses. Its API, the build-chain template contract and the
failure semantics are in [core/README.md](core/README.md).

Coordinates: `org.octopusden.octopus.automation.teamcity:octopus-teamcity-automation-core:<version>`. It is
published to GitHub Packages, not Maven Central, and GitHub Packages authenticates every read, so you
need a GitHub token with the `read:packages` scope.

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/octopusden/octopus-maven-packages")
        credentials {
            username = System.getenv("GITHUB_PACKAGES_USERNAME")
            password = System.getenv("GITHUB_PACKAGES_TOKEN") // read:packages
        }
    }
}

dependencies {
    implementation("org.octopusden.octopus.automation.teamcity:octopus-teamcity-automation-core:<version>")
}
```

## teamcity-automation-service

The service creates a component's TeamCity build chain, the same chain the `create-build-chain`
command creates, through `core`; it only adds the HTTP contract, a check that nothing exists yet,
and an account of what exists after a failure.

### API

`POST /rest/api/1/build-chains`

```json
{
  "parentProjectId": "RDDepartment_Example",
  "component": "example-component",
  "minorVersion": "1.0",
  "createChecklist": true,
  "createRcForce": false
}
```

The request names no server and no account. The service creates the chain on the TeamCity server in
its configuration, as the TeamCity account in its configuration, and reads components from the
Components Registry in its configuration. The account's password is never logged or returned.

An older caller may still send `teamcity` and `componentsRegistryUrl`. They are ignored, and a warning
names the field without its value, so callers can drop them.

**The service has no inbound authentication.** Anyone who can reach it can create chains as the
service's TeamCity account, under any parent project that account can write to. It is meant for callers
inside the cluster that check the user's rights themselves, such as the Components Management Portal.

`201 Created` returns the ids of what was created; the RC and checklist ids are `null` when they were
not created:

```json
{
  "projectId": "RDDepartment_Example_ExampleComponent",
  "compileBuildTypeId": "RDDepartment_Example_ExampleComponent_10CompileUtAuto",
  "rcBuildTypeId": "RDDepartment_Example_ExampleComponent_20ReleaseCandidateManual",
  "checklistBuildTypeId": "RDDepartment_Example_ExampleComponent_30ReleaseChecklistValidationManual",
  "releaseBuildTypeId": "RDDepartment_Example_ExampleComponent_40ReleaseManual"
}
```

Errors carry `errorCode` and `errorMessage`:

| Status | `errorCode` | When |
|---|---|---|
| 400 | `VALIDATION` | A field is missing, blank or has surrounding whitespace |
| 403 | `FORBIDDEN` | The service's TeamCity account lacks a permission under the parent project; the message names the project |
| 404 | `NOT_FOUND` | The parent project, the component or its version does not exist |
| 409 | `ALREADY_EXISTS` | The component's project already exists under the parent; `projectId` names it. Nothing is changed: delete the project in TeamCity to create the chain again |
| 422 | `UNSUPPORTED_COMPONENT` | The build system or the VCS roots cannot be turned into a chain |
| 502 | `PARTIALLY_CREATED` | Creation failed after the project was created; `projectId` and `buildTypeIds` name what exists. Delete the project before trying again |
| 409 | `ALREADY_EXISTS` | Another request created the same project while this one ran; `projectId` names it and this request created nothing |
| 502 | `UPSTREAM_ERROR` | TeamCity or the Components Registry failed, rejected the request or is not reachable. This includes TeamCity rejecting the service's own account (401): the caller cannot fix that |

Everything before the project is created (the account, parent, existing project, component,
build system, VCS roots) is checked first, so a 4xx never leaves anything in TeamCity.

The `client` module is a Feign client for this API that rethrows these errors as typed exceptions.

### Configuration

The service starts only when all four properties are set; a blank one stops the startup with an error
that names it.

| Property | Meaning |
|---|---|
| `teamcity-automation-service.teamcity.url` | Base URL of the TeamCity server (http or https, no user info) |
| `teamcity-automation-service.teamcity.username` | The TeamCity account the service creates chains as |
| `teamcity-automation-service.teamcity.password` | Its password |
| `teamcity-automation-service.components-registry.url` | Base URL of the Components Registry |

The URLs come from the config server; the account comes from Vault. Environment variables work too, for
example `TEAMCITYAUTOMATIONSERVICE_TEAMCITY_URL`.

### Health

| Endpoint | Contains |
|---|---|
| `/actuator/health/readiness/teamcity` | TeamCity only: `UP` when `GET /app/rest/server` answers 2xx to the service's account within about 5 seconds, `DOWN` on a 401, a 5xx, a timeout or a refused connection |
| `/actuator/health/readiness` | the readiness state and TeamCity |
| `/actuator/health` | every check, TeamCity included |
| `/actuator/health/liveness` | the liveness state only: a TeamCity outage must not restart the service |

Responses carry the status only, never the URL, the account or the error; the cause is logged at `WARN`.

### Run locally

The `dev` profile runs without the config server:

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew :teamcity-automation-service:bootRun
```

In IntelliJ, set *Active profiles* to `dev` on the Spring Boot run configuration. Add
`SPRING_CONFIG_ADDITIONAL_LOCATION=server/dev/` to also expose the actuator endpoints and debug
logging from `server/dev/application-dev.yml`. The API is then on `http://localhost:8080` with
Swagger UI at the root.

### Functional tests

`ft` runs the service image against TeamCity 2022 and 2026 and the Components Registry, on Docker
(the default) or in an OKD namespace. The service works with one TeamCity, so it runs once per TeamCity
version, plus a third instance with a wrong password to check that its health is `DOWN`. `test` runs it too, after the `:core` and `:cli` integration
tests have taken their own servers down, so `./gradlew build test` covers everything and
`-x test` leaves it out:

```bash
./gradlew :ft:ft
```

```bash
./gradlew :ft:ft -Ptest.platform=okd -Pokd.project=<namespace> -Pokd.cluster-domain=<apps domain>
```

The same values can come from `TEST_PLATFORM`, `OKD_PROJECT` and `OKD_CLUSTER_DOMAIN`. On OKD the
pods are created from `okd/*.yaml` with the `oc` CLI, which must be logged in, and the service
image is pushed to `OCTOPUS_GITHUB_DOCKER_REGISTRY` first so the namespace can pull it. Set
`DOCKER_REGISTRY` to pull the TeamCity and registry images through a mirror.
