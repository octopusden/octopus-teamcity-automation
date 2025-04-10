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
