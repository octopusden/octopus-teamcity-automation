## Why

`POST /rest/api/1/build-chains` takes the TeamCity URL, user and password, and the Components Registry
URL, in every request body. The service holds no credentials and calls whichever allow-listed server
the caller names. That has three costs:

- The TeamCity secret lives with every caller instead of in one place. The only planned caller, the
  Components Management Portal (its `create-teamcity-build-chain` branch, not merged yet), is built to
  read a technical TeamCity account from its own config (`portal.teamcity-automation.username/password`,
  meant to come from Vault) just to forward it on each call.
- The secret crosses one more hop than it needs to. Portal to service is plain HTTP inside the cluster
  (`http://teamcity-automation-service-*.f1.svc.cluster.local:8080`), so the password travels
  unencrypted there. HTTPS on its own would protect a body as well as a header, so the issue is the
  extra hop and the extra holder, not the body as such.
- Keeping the password out of logs takes special handling: the masked `TeamcityConnection.toString()`,
  `ClientWireLogFilter`, and the Feign client stuck at `BASIC` log level.

The platform already has a config server and Vault for this. Host URLs belong in service-config and
credentials belong in Vault.

## What Changes

- **BREAKING (API):** the request no longer has `teamcity` or `componentsRegistryUrl`. The service
  connects to the TeamCity server and Components Registry set in its configuration, as the TeamCity
  account stored in Vault.
- The allow-lists (`allowed-teamcity-urls`, `allowed-components-registry-urls`) go away. They only
  existed because the caller picked the server.
- The service starts only when the TeamCity URL, username and password and the Components Registry
  URL are all configured. A missing value stops startup with an error that names it.
- The service still has no inbound authentication. With the credential gone from the request, any
  caller that can reach it can create chains as the service's account. This is an accepted risk for now
  (see design, decision 3).
- If TeamCity rejects the service's own account, the answer is `502 UPSTREAM_ERROR` instead of
  `401 UNAUTHORIZED`, because the caller cannot fix it.
- `common` and `client` drop `TeamcityConnection` and the two fields from `CreateBuildChainRequest`.
- A TeamCity health check: `/actuator/health/readiness/teamcity` reports whether the configured TeamCity
  answers with the service's account. It is part of the readiness group and, like every health check,
  of `/actuator/health`. It is not part of the liveness group. This is related but separate work: it only became possible once the service has
  its own TeamCity connection.

## Capabilities

### New Capabilities

- `build-chain-service`: the build-chain endpoint of teamcity-automation-service: where its servers and
  account come from, who may call it, and its error contract.
- `service-health`: the health endpoints and which of them include TeamCity.

### Modified Capabilities

None. There are no specs in this repository yet.

## Impact

- **service-config:** `teamcity-automation-service*.yml` gains the TeamCity URL (`${teamcity.host}`) and
  the Components Registry URL per environment, and loses the allow-lists.
- **Vault:** `teamcity-automation-service` gains the username and password of the TeamCity service account
  `s-f1-teamcity`.
- **Components Management Portal:** the unmerged `create-teamcity-build-chain` branch stops sending the
  TeamCity URL, credentials and registry URL, and drops `portal.teamcity-automation.username/password`.
  If that branch merges first, the service ignores the old fields (decision 4), so the order does not
  matter.
- **service-deployment:** no change. The startup probe stays on the stand-wide default `/actuator/health`,
  which now includes TeamCity (an accepted risk, see design, decision 6). The shared `spring-cloud` chart has
  no readiness probe; adding one is a separate, future issue.
- **CLI and `core`:** unchanged. The CLI still takes `--url/--user/--password`.
- **`ft`:** the service image is configured with the test TeamCity and registry instead of receiving
  them in requests.
