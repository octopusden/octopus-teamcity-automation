## Purpose

Governs teamcity-automation-service's `POST /rest/api/1/build-chains`: where the servers and the TeamCity
account come from, and how failures that involve them are reported. It does not govern
what the chain contains, which belongs to `core`.

## ADDED Requirements

### Requirement: Servers and account come from configuration

The service SHALL create the chain on the TeamCity server set in
`teamcity.url`, as the account in `teamcity.username` and `teamcity.password`, and SHALL read components from
`components-registry.url`.
The request SHALL NOT be able to choose any of them.

#### Scenario: Request without connection fields

- **WHEN** a caller posts `parentProjectId`, `component` and `minorVersion` only
- **THEN** the chain is created on the configured TeamCity server and the response is `201`

#### Scenario: Request still carrying the old fields

- **WHEN** a request contains `teamcity` or `componentsRegistryUrl`
- **THEN** the service ignores them, uses the configured servers, and logs a warning that does not
  include their values

### Requirement: Missing configuration stops startup

The service SHALL fail to start when the TeamCity URL, username or password, or the Components Registry
URL is blank, and the startup error SHALL name the missing property.

#### Scenario: Password not in Vault

- **WHEN** `teamcity.password` is blank
- **THEN** the application does not start and the log names that property

### Requirement: The service's own credentials are never exposed

The TeamCity password SHALL NOT appear in any response, log line or exception message.

#### Scenario: TeamCity rejects the service account

- **WHEN** TeamCity answers 401 to the service's account
- **THEN** the service answers `502` with `errorCode` `UPSTREAM_ERROR`, a message saying the service's
  TeamCity account was rejected, and neither the username nor the password

#### Scenario: Account lacks permission on the parent project

- **WHEN** TeamCity answers 403 while reading or creating under the parent project
- **THEN** the service answers `403` with `errorCode` `FORBIDDEN`, naming the parent project
