# RiftTowny

Town, nation, territory, economy, governance, war, events and integration platform for
Paper and Folia.

**Clean-room.** Behaviourally familiar to players who know Towny, Lands or HuskTowns —
same command vocabulary, same mental model — but sharing no code, configuration, message
text, assets or internal design with any of them. Compatibility is reproduced from
observable public behaviour and the public placeholder reference only.

- **Java 25**, Paper and Folia **26.1–26.2**, `folia-supported: true`
- **MariaDB** for production and networks, **SQLite** for development and single servers
- MiniMessage for every configurable string
- Commands, clickable chat, Java GUIs and native Bedrock forms — **no feature is GUI-only**

> **RiftTowny cannot run alongside Towny.** The command tree and the
> `%townyadvanced_*%` PlaceholderAPI namespace both collide, so RiftTowny refuses to start
> when Towny is present rather than half-registering.

## Status

**Phase 1 (Foundation) is complete and verified. Phase 2 has not started** — there are no
towns, claims or commands beyond `/rifttowny status` yet.

What exists and is tested: the module structure and build, the Paper/Folia scheduler
abstraction, typed configuration, the MiniMessage message service, the storage layer
(dialect abstraction, pooling, migrations, transactional outbox, idempotency store) and
the integration capability registry.

[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) tracks every item as implemented,
partial or planned, and says what is unverified rather than assuming it works.

## Documents

| Document | What it covers |
|---|---|
| [SPECIFICATION.md](SPECIFICATION.md) | Architecture, domain model, and the behaviour every subsystem must have |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | Phased plan and the honest checklist |
| [INTEGRATION_CONTRACTS.md](INTEGRATION_CONTRACTS.md) | Every optional dependency, with the verified state of its real API |
| [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md) | Platform, command and the 150-placeholder compatibility manifest |
| [docs/war-decisions.md](docs/war-decisions.md) | **Awaiting approval** — war rules are not implemented until this is signed off |
| [docs/dependency-report.md](docs/dependency-report.md) | Every dependency, its scope, and the shading rules |
| [docs/risk-register.md](docs/risk-register.md) | Ranked risks with owner actions |
| [BUILDING.md](BUILDING.md) | How to build and how to verify a jar |

## Modules

```
rifttowny-api           the published contract other plugins compile against
rifttowny-domain        entities and rules — no Bukkit, enforced by test
rifttowny-storage       JDBC, Flyway, outbox, idempotency — no Bukkit, enforced by test
rifttowny-integrations  capability registry and per-plugin adapters
rifttowny-paper         the shippable plugin
```

Dependencies run one way: `paper` → `integrations` → `storage` → `domain` → `api`.

## For plugin developers

```xml
<dependency>
  <groupId>net.riftbreaker</groupId>
  <artifactId>rifttowny-api</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

```java
RiftTownyProvider.get().ifPresent(api -> {
    api.requireApiVersion(new ApiVersion(0, 1));
    if (api.capabilities().isActive(Capability.ECONOMY_RIFTECO)) {
        // ...
    }
});
```

Obtain the API no earlier than your own `onEnable`, and declare RiftTowny as a `depend` or
`softdepend`. Prefer calling `RiftTownyProvider.get()` at the point of use over caching the
instance, which goes stale across a plugin reload.
