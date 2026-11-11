## Purpose

Governs teamcity-automation-service's health endpoints: which checks each one contains and what it
shows. Probe timings belong to the deployment, not here.

## ADDED Requirements

### Requirement: TeamCity health is checked with the service's own account

`/actuator/health/readiness/teamcity` SHALL be `UP` only when the configured TeamCity server answers
`GET /app/rest/server` successfully, as the configured account, within a few seconds. It SHALL be
`DOWN` otherwise. The response SHALL NOT include the URL, the account or the error.

#### Scenario: TeamCity reachable and credentials accepted

- **WHEN** TeamCity answers 200
- **THEN** `/actuator/health/readiness/teamcity` answers HTTP 200 with status `UP`

#### Scenario: Credentials rejected

- **WHEN** TeamCity answers 401 to the service's account
- **THEN** `/actuator/health/readiness/teamcity` answers HTTP 503 with status `DOWN`, and the log has a WARN naming
  the cause without the password

#### Scenario: TeamCity unreachable or slow

- **WHEN** the connection is refused or TeamCity does not answer within the timeout
- **THEN** `/actuator/health/readiness/teamcity` answers HTTP 503 with status `DOWN`

### Requirement: Readiness and the aggregate follow TeamCity; liveness does not

`/actuator/health/readiness` and `/actuator/health` SHALL include the TeamCity check.
`/actuator/health/liveness` SHALL NOT include it, nor any other external system.

#### Scenario: TeamCity goes down while the service runs

- **WHEN** the TeamCity check turns `DOWN`
- **THEN** `/actuator/health/readiness` and `/actuator/health` answer 503, and `/actuator/health/liveness`
  still answers 200

#### Scenario: TeamCity comes back

- **WHEN** the TeamCity check turns `UP` again
- **THEN** `/actuator/health/readiness` answers 200 without a restart
