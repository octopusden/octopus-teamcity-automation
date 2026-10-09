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
