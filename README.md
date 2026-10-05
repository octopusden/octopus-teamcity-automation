# octopus-teamcity-automation

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

## Using the build-chain library

`create-build-chain` is a thin adapter over the `build-chain` library, which creates the same
Compile → Release Candidate → Release Checklist Validation → Release chain without the CLI.

Coordinates: `org.octopusden.octopus.automation.teamcity:build-chain:<version>`. It is published to
GitHub Packages, not Maven Central, and GitHub Packages authenticates every read, so you need a
GitHub token with the `read:packages` scope.

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
    implementation("org.octopusden.octopus.automation.teamcity:build-chain:<version>")
}
```

The caller builds both clients; the library only orchestrates:

```kotlin
val result = BuildChainCreator(teamcityClient, componentsRegistryClient)
    .create(BuildChainRequest(parentProjectId = "MyParent", componentName = "my-component", minorVersion = "1.0"))
// result.projectId, result.compileBuildTypeId, result.rcBuildTypeId, result.checklistBuildTypeId, result.releaseBuildTypeId
```

- `BuildChainConfig` overrides the five template ids and the JDK version parameter name; its defaults
  are the templates the CLI uses (`CDGradleBuild`, `CDJavaMavenBuild`, `CdReleaseCandidateNew`,
  `CdReleaeChecklistValidation`, `CDRelease`) and `JDK_VERSION`.
- A component the generator cannot handle raises a `BuildChainException` before anything is created
  in TeamCity: `UnsupportedBuildSystemException`, `UnsupportedVcsTypeException` (a non-Git root) or
  `UnsupportedVcsRootLayoutException` (a repeated repository, several roots at the checkout root, or a
  Checkout Directory that collides with `RELEASE_NOTES_REPORT_TEMPLATE_CHECKOUT_DIR`).
- Logging goes through SLF4J; bring your own binding.
