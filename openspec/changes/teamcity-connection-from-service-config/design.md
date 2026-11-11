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
teamcity:
  url: ${teamcity.host}          # service-config
  username: <vault>              # s-f1-teamcity
  password: <vault>
components-registry:
  url: http://components-registry-service-<env>.f1.svc.cluster.local:8080
```

The keys have no service prefix, the same as components-registry-service's `teamcity.username` and
`teamcity.password`. In Vault, the secret for `teamcity-automation-service` holds `teamcity.username` and
`teamcity.password`. The shared config already defines `teamcity.host` and `components-registry.vcs.*`
for other services; the service ignores keys it does not bind.

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

### 4. Old request fields are rejected, not ignored

A request that still carries `teamcity` or `componentsRegistryUrl`, with any value, gets
`400 VALIDATION` naming the field and saying the service now uses its own configured server and
account. The value is never echoed or logged; a warning names the field only. Nothing is created.

Ignoring the fields would answer 200 and create the chain on the configured TeamCity as the service
account, whatever server or account the caller named. The caller would never notice, and the
TeamCity audit trail and permissions would silently switch to the service account.

Rejected: rejecting only a `teamcity.url` that differs from the configured one. A caller naming the
right server but its own account would still be switched silently, and closing that would mean
comparing caller-sent credentials. Also rejected: a `Deprecation`/`Warning` response header, which
callers rarely read. Nobody calls the service today, and 2.0 is already a breaking release, so a hard
rejection costs nothing.

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

Spring Boot adds every health indicator to the status of the root `/actuator/health` on its own. It
serves a single component path such as `/actuator/health/{group}/{component}` only when that group shows
components. `show-components` falls back to `show-details`, which defaults to `never`; with that
default, every component path answers 404. So the readiness group gets
`management.endpoint.health.group.readiness.show-components: always`, while `show-details` stays
`never`:

```yaml
management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState, teamcity
          show-components: always
        liveness:
          include: livenessState
```

That makes `/actuator/health/readiness/teamcity` answer with its status only. `/actuator/health/teamcity`
goes through the primary group, which still shows no components, so it keeps answering 404 and the
readiness path is the only one.

- **Readiness includes TeamCity.** The service can do nothing without TeamCity, so once a readiness
  probe exists it should take the pod out of traffic while TeamCity is down and put it back when
  TeamCity recovers. Until the chart has a readiness probe, nothing uses it as a probe. The `okd/`
  manifest used by `ft` gates its pods on liveness, so that the wrong-account instance still starts
  and `ft` can see its readiness answer `DOWN`.
- **Liveness never includes TeamCity.** Otherwise a TeamCity outage would make Kubernetes restart a
  healthy pod again and again, which cannot fix anything.
- **The startup probe is left on the stand-wide default, which includes TeamCity (accepted risk).**
  The default startup probe in `default.yml` is the aggregate `/actuator/health`, so a pod that starts
  while TeamCity is down fails its startup probe after about 10 minutes (30 × 20 s plus a 30 s delay)
  and is restarted until TeamCity is back. This is accepted: TeamCity outages are rare, the service is
  small and few things depend on it, and the team can restart it by hand. It also mostly fixes itself:
  Kubernetes keeps retrying the container, and during a rollout the old pod keeps serving while the new
  one waits. Overriding it, as components-registry-service does, stays an option if this ever hurts.
- Health details stay hidden (`show-details: never`, the Spring default, also on the readiness group),
  so the endpoint only shows `UP` or `DOWN` and never the URL or account. The real TeamCity error
  goes to the log at WARN.

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
- Breaking change to the `common`/`client` artifacts: `TeamcityConnection` is removed and
  `CreateBuildChainRequest` loses two constructor parameters, so code built against 1.x does not
  compile. Released as 2.0, so `1.+` ranges and minor-version auto-updates do not pick it up. No
  deprecated shim is kept: it would put a credential-carrying type back in the API and silently drop
  what callers pass. Over HTTP, old request bodies are rejected with 400 (see Decision 4).

## Account

The service uses the TeamCity service account `s-f1-teamcity`. Its username and password are set in
Vault for `teamcity-automation-service`. The TeamCity URL is in service-config.
