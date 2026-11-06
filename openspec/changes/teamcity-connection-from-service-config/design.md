## Context

- `BuildChainServiceImpl` builds a `TeamcityClient` and a `ComponentsRegistryServiceClient` per request
  from the request's `teamcity` and `componentsRegistryUrl`, after checking both against
  `AllowedServersProperties`.
- The service has no inbound authentication. It is reachable inside the cluster and through the
  api-gateway route `/teamcity-automation-service/**`. Today the TeamCity password in the body is the
  only thing that stops an anonymous caller.
- The only planned caller is the Portal, on its unmerged `create-teamcity-build-chain` branch. That
  branch sends one technical TeamCity account from its own config, and it checks that the user can edit
  the component in CRS before calling.

## Goals / Non-Goals

**Goals:** no credentials or server URLs in the request. One TeamCity account, stored in Vault.

**Non-Goals:** more than one TeamCity server per environment. Per-user TeamCity permissions. Inbound
authentication. Changes to the CLI or `core`.

## Decisions

### 1. One TeamCity server and one Components Registry per deployment, from configuration

```yaml
teamcity-automation-service:
  teamcity:
    url: ${teamcity.host}          # service-config
    username: <vault>              # s-f1-teamcity
    password: <vault>
  components-registry:
    url: http://components-registry-service-<env>.f1.svc.cluster.local:8080
```

The clients are built once at startup instead of once per request. Rejected: keeping a URL field in the
request and checking it against the allow-list. Each environment has exactly one TeamCity server, so a
choosable URL only adds surface.

### 2. Fail fast on missing configuration

A blank URL, username or password fails startup and the error names the property. Rejected: starting
anyway and answering 503. A service that cannot do its only job should not report itself as ready.

### 3. No inbound authentication for now

Once the service holds a privileged account, anyone who can reach it (inside the cluster, or through
the api-gateway route) could create chains under any parent project that account can write to. This is
accepted for now. There is one caller, the Portal, and it checks in CRS that the user can edit the
component before it calls. The gateway route and the network setup stay as they are.

Rejected for now:
- Keycloak bearer tokens with a client role, or any other caller authentication. Revisit this when a
  second caller appears or the risk is no longer acceptable.
- Removing the api-gateway route so that only callers inside the cluster can reach the service. This
  needs changes to the route and possibly to the Portal's URL, for a risk that is already accepted.

### 4. Old request fields are ignored, not rejected

During the rollout the Portal will still send `teamcity` and `componentsRegistryUrl`. The service
ignores unknown fields and logs one warning per request that carries `teamcity`, without the value, so
the Portal can drop them after the service ships. Once it has, the warning shows that nobody still
sends them.

### 5. Error mapping when the caller does not own the credentials

| TeamCity result | Before | After |
|---|---|---|
| TeamCity rejects the service account (401) | `401 UNAUTHORIZED` | `502 UPSTREAM_ERROR`, "service account rejected" |
| TeamCity account lacks permission on the parent (403) | `403 FORBIDDEN` | `403 FORBIDDEN`, naming the parent project |

TeamCity's 403 stays a 403 because the caller chose the parent project and can choose another one.

### 6. TeamCity health: in the readiness group, not in liveness

A `teamcity` health indicator calls `GET /app/rest/server` as the service's account, with a short
timeout (a few seconds). That one call proves the URL, the network path and the credentials. The test
`docker-compose.yml` files already use the same call, but only to check the TeamCity test containers,
not the service.

| Endpoint | Contains | Used by |
|---|---|---|
| `/actuator/health/readiness/teamcity` | TeamCity only | people and monitoring |
| `/actuator/health/readiness` | `readinessState` + TeamCity | a readiness probe, once the chart has one |
| `/actuator/health` | every check, TeamCity included | people, monitoring, the startup probe (stand-wide default) |
| `/actuator/health/liveness` | `livenessState` only | liveness probe |

Spring Boot adds every health indicator to the root `/actuator/health` on its own, and serves group
members under `/actuator/health/{group}/{component}`. So putting `teamcity` in the readiness group is
enough for both `/actuator/health/readiness/teamcity` and `/actuator/health`. Spring also serves the
same check at `/actuator/health/teamcity`, and there is no setting to hide it. The documented path is
the readiness one.

- **Readiness includes TeamCity.** The service can do nothing without TeamCity, so once a readiness
  probe exists it should take the pod out of traffic while TeamCity is down and put it back when
  TeamCity recovers. Until the chart has a readiness probe, only the `okd/` manifest used by `ft`
  uses it.
- **Liveness never includes TeamCity.** Otherwise a TeamCity outage would make Kubernetes restart a
  healthy pod again and again, which cannot fix anything.
- **The startup probe is left on the stand-wide default, which includes TeamCity (accepted risk).**
  The default startup probe in `default.yml` is the aggregate `/actuator/health`, so a pod that starts
  while TeamCity is down fails its startup probe after about 10 minutes (30 × 20 s plus a 30 s delay)
  and is restarted until TeamCity is back. This is accepted: TeamCity outages are rare, the service is
  small and few things depend on it, and the team can restart it by hand. It also mostly fixes itself:
  Kubernetes keeps retrying the container, and during a rollout the old pod keeps serving while the new
  one waits. Overriding it, as components-registry-service does, stays an option if this ever hurts.
- Health details stay hidden (the Spring default, `show-details: never`), so the endpoint only shows
  `UP` or `DOWN` and never the URL or account. The real TeamCity error goes to the log at WARN.

Rejected:
- Overriding the startup probe to `/actuator/health/liveness` in the deployment values: not needed for
  a risk the team accepts.
- A custom `/rest/api/1/health` endpoint: actuator groups already provide the probe endpoints, and the
  existing `okd/` manifest already uses `/actuator/health/readiness`.
- A readiness probe in the shared `spring-cloud` chart as part of this change: deferred to a separate
  issue.

## Risks / Trade-offs

- TeamCity's audit shows the technical account, not the person. The Portal branch would have the same
  effect, and it logs the user who asked.
- Anyone who can reach the service, including through the api-gateway, can create chains as the
  technical account. This is accepted for now (decision 3).
- Once a readiness probe exists: while TeamCity is down, the pod is not ready. With one replica, callers get a connection error or
  a gateway 503 instead of the service's `502 UPSTREAM_ERROR`. That is accepted: either way, nothing
  can be created.
- A readiness probe would call TeamCity every probe period. One cheap `GET /app/rest/server` every few
  seconds is fine. If it ever is not, cache the result for one probe period.
- Breaking change to API v1 and to the `common`/`client` artifacts. The Portal mirrors the DTOs
  locally, so nothing outside this repo uses the published client yet. Bump the release line.

## Account

The service uses the TeamCity service account `s-f1-teamcity`. Its username and password are set in
Vault for `teamcity-automation-service`. The TeamCity URL is in service-config.
