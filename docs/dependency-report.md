# RiftTowny — Dependency Report

Every dependency, why it is there, how it is scoped, and what it costs at runtime.
Generated 2026-08-09 against the initial Phase 1 build.

## 1. Compile / runtime dependencies

| Artifact | Version | Scope | Module | Why | Shaded? |
|---|---|---|---|---|---|
| `io.papermc.paper:paper-api` | `26.2.build.92-stable` | `provided` | api, integrations, paper | Server API. **Pinned, not ranged** — a `[26.2.build,)` range re-resolves every build and silently moves the compile target | no |
| `com.zaxxer:HikariCP` | `7.0.2` | compile | storage | Connection pooling. Org-wide standard since 2026-08-03 | **yes, relocated** to `net.riftbreaker.rifttowny.libs.hikari` |
| `org.flywaydb:flyway-core` | `12.4.0` | compile | storage | Schema migrations. Carries the SQLite dialect itself | yes, not relocated |
| `org.flywaydb:flyway-mysql` | `12.4.0` | compile | storage | MariaDB/MySQL dialect, which core does not carry | yes, not relocated |
| `org.mariadb.jdbc:mariadb-java-client` | `3.5.8` | compile | storage | Production driver. Pure Java | yes |
| `org.xerial:sqlite-jdbc` | `3.51.1.0` | compile | storage | Development/single-server driver | yes — **never relocated**, see §3 |
| `org.junit:junit-bom` | `5.13.4` | test | all | Test platform | n/a |
| `org.assertj:assertj-core` | `3.27.3` | test | all | Assertions | n/a |

Adventure and MiniMessage are **not** separate dependencies: paper-api bundles them
(`net.kyori.adventure.text.minimessage`). Adding them explicitly would risk two Adventure
versions on the classpath.

SnakeYAML is likewise not added — Bukkit's `YamlConfiguration` is used for config loading.

## 2. Soft dependencies (never on the compile classpath as `compile`)

Declared `provided` where a local source or a published artifact exists, and accessed
reflectively where it does not. All are `softdepend` in `plugin.yml`; **none** is required
to start.

| Plugin | How referenced | Absence causes |
|---|---|---|
| RiftEco | `provided` artifact | Economy features disabled; territory and governance unaffected |
| RiftCore | `provided` artifact | Shared GUI framework unavailable; RiftTowny falls back to its own inventory holder |
| RiftLogger | `provided` artifact | Civic audit not written to the central log; local audit table still populated |
| RiftPvP | `provided` artifact | Combat-tag flight removal disabled |
| RiftBoosters | `provided` artifact | Community flight booster unavailable |
| RiftChat | `provided` artifact | `/tc` `/nc` `/ac` unavailable |
| RiftShop, RiftSpawners, RiftEvents, RiftEssentials, VelocitySrv | `provided` artifact | Corresponding subsystem disabled |
| PlaceholderAPI | `provided` | Placeholders not registered |
| LuckPerms | `provided` | Contexts not registered |
| ~~CoreProtect~~ | — | **Dropped 2026-08-10.** RiftLogger is the sole audit integration |
| Floodgate / Geyser | `provided` | Bedrock forms unavailable; Java GUIs unaffected |
| BlueMap, Dynmap, squaremap | reflective per back end | That map back end only |
| mcMMO | `provided`, all transitives excluded | Skill modifiers disabled |
| VaultUnlocked | `provided` | Only matters when RiftEco is also absent |

**mcMMO must exclude everything.** It declares `ProtocolLib:LATEST` and a tree of
`7.0.0-SNAPSHOT` WorldEdit/WorldGuard artifacts, six of which fetch `maven-metadata.xml`
on every build from a host that answers HTTP 500. Excluded with
`<exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion>`, matching RiftCore
and VelocitySrv.

## 3. Shading rules

Configured in `rifttowny-paper`, the only module that produces a shippable jar.

**Relocated:** `com.zaxxer.hikari` → `net.riftbreaker.rifttowny.libs.hikari`. Without this,
two plugins shading different HikariCP versions collide on the same server. Relocation
requires **maven-shade-plugin 3.6.2 or newer**: relocation rewrites bytecode, and the ASM
bundled with 3.6.0 cannot read Java 25 class files.

**Never relocated:** `org.sqlite`. sqlite-jdbc is JNI, not pure Java. Its bundled native
binary exports symbols named for the Java package
(`Java_org_sqlite_core_NativeDB__1open_1utf8`), so a shaded rename leaves the class calling
a symbol that is not in the binary. This surfaces as `UnsatisfiedLinkError` on Linux and
`NoClassDefFoundError: org/sqlite/core/NativeDB` on Windows, **only on the SQLite
backend** — a MySQL-backed test run never reveals it. It has already cost this
organisation two production incidents.

**Not relocated but shaded:** Flyway and the MariaDB driver. Both are pure Java, neither
is commonly shaded by other plugins in a conflicting version.

`ServicesResourceTransformer` is applied so JDBC `META-INF/services` entries survive
shading.

## 4. Classloading

Two traps this organisation has already hit, both applying here:

1. **Flyway and any `ServiceLoader` use must be handed the plugin's own classloader.**
   Under a plugin classloader the thread context classloader sees nothing, and Flyway
   reports "no migrations found" rather than an error.
2. **Pooled SQLite keeps the file open**, so JUnit `@TempDir` cleanup fails on Windows.
   The storage tests register an `AfterEachCallback` that closes pools before the temp
   directory is removed.

## 5. Pool sizing

A **SQLite pool of one deadlocks.** Several repositories open a second connection while
holding the first; with `maximumPoolSize=1` the borrow blocks until the timeout and
surfaces as a wrong answer rather than an error. SQLite pools are sized ≥ 4 with writes
serialised at the application layer instead.

## 6. Version floating — prohibited

No dependency in this repository may use `LATEST`, `RELEASE`, a version range, or a
`-SNAPSHOT` of a third party. All three have caused failures in sibling repos, including a
JitPack rebuild that produced an **empty jar** which still "resolved successfully".

Sibling `net.riftbreaker:*` SNAPSHOTs are the one exception, and their repository entry
carries `<updatePolicy>always</updatePolicy>` — Maven otherwise re-checks a SNAPSHOT only
once a day, which keeps a stale jar in place and makes a fixed upstream look unfixed.
