> Gradle: `./gradlew build` (unit), `./gradlew :ft:ft` (functional). Test-first for each behaviour task.
>
> Order: service-config + Vault -> this repo released. The Portal branch can change before or after, because
> the service ignores the old fields.

## 1. Configuration

- [ ] 1.1 Failing test, then `TeamcityAutomationServiceProperties` (`teamcity.url/username/password`,
      `components-registry.url`) with startup validation that names the blank property
- [ ] 1.2 Failing test, then build the TeamCity and Components Registry clients once from the properties;
      remove `AllowedServersProperties`, `BaseUrl`'s allow-list use and the per-request factory methods
- [ ] 1.3 `server/dev/application-dev.yml` and the README "Run locally" section use the new properties

## 2. API

- [ ] 2.1 Failing test, then drop `teamcity` and `componentsRegistryUrl` from `CreateBuildChainRequest`,
      delete `TeamcityConnection`; old fields are ignored with a value-free warning
- [ ] 2.2 Failing test, then the new error mapping: TeamCity 401 becomes `502 UPSTREAM_ERROR`, TeamCity 403
      names the parent project
- [ ] 2.3 Restore the `client` module's normal Feign log level now that the body carries no secret; keep
      `ClientWireLogFilter` for the outbound TeamCity calls

## 3. Health

- [ ] 3.1 Failing test, then the `teamcity` health indicator: `UP` when `/app/rest/server` answers 200 to
      the service's account; `DOWN` on a 401, a 5xx, a timeout or a refused connection; no URL or account in
      the response; WARN log with the cause
- [ ] 3.2 Failing test, then the health groups: `readiness` = `readinessState` + `teamcity`; `liveness` =
      `livenessState` only; probes enabled outside Kubernetes too, so `ft` and `dev` expose the groups

## 4. Functional tests and docs

- [ ] 4.1 `ft`: configure the service container with the test TeamCity and registry; requests stop carrying
      credentials; `ft` checks `/actuator/health/readiness/teamcity` is `UP`, and `DOWN` with wrong credentials
- [ ] 4.2 README API section: new request, error table, a note that the service has no inbound authentication, and the health endpoints; bump `.release-line`

## 5. Outside this repo (tracked here, done in their repos)

- [ ] 5.1 service-config: add `teamcity-automation-service.teamcity.url` / `components-registry.url`, drop the
      allow-lists, for default, cloud-qa and cloud-prod
- [ ] 5.2 Vault: add the `s-f1-teamcity` username/password for `teamcity-automation-service` in each environment
- [ ] 5.3 Portal (`create-teamcity-build-chain` branch): stop sending connection fields and drop
      `portal.teamcity-automation.username/password`
