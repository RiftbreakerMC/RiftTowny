# Building RiftTowny

## Requirements

- JDK 25 (Temurin 25.0.3 is what CI uses)
- Maven 3.9+

No GitHub Packages token is needed today: RiftTowny does not yet compile against any
sibling artifact. That changes when the adapters in
[INTEGRATION_CONTRACTS.md](INTEGRATION_CONTRACTS.md) land — from then on, either
authenticate to GitHub Packages or run `mvn install` in the sibling checkout first.

## Build

```bash
mvn clean verify
```

The shippable jar is `rifttowny-paper/target/RiftTowny.jar`. It is named for the plugin
only, org-wide convention, so a download needs no renaming; the version is not lost,
because `plugin.yml` is resource-filtered and carries `${project.version}`.

## Verifying a jar

Compiling is not shipping. Two checks catch the failures that have actually happened in
this organisation.

### 1. Relocation

```bash
unzip -l rifttowny-paper/target/RiftTowny.jar | grep -E 'com/zaxxer|org/sqlite|libs/hikari' | head
```

Expected: **zero** `com/zaxxer` entries, `org/sqlite` present and **unrelocated**, hikari
present under `net/riftbreaker/rifttowny/libs/hikari`.

`org.sqlite` must never be relocated. sqlite-jdbc is JNI, and its native binary exports
symbols named for the Java package, so a shaded rename leaves the class calling a symbol
that is not in the binary — `UnsatisfiedLinkError` on Linux, `NoClassDefFoundError` on
Windows. It only fires on the SQLite backend, so a MySQL-backed server never reveals it.

### 2. End-to-end probe

The probe opens the relocated pool, loads the JNI driver, migrates through the jar's own
classloader and round-trips the outbox — everything the relocation trap would break.

```bash
java -cp rifttowny-paper/target/RiftTowny.jar tools/ShadedJarProbe.java
```

Expected last line: `RESULT       : OK`.

## Conventions worth not rediscovering

- **`.gitattributes` pins sources to LF.** A CRLF working tree breaks source-matching
  tests with unrecognisable errors that do not reproduce on Linux.
- **No floating versions.** No `LATEST`, no `RELEASE`, no ranges, no third-party
  `-SNAPSHOT`. A JitPack rebuild once produced an empty jar that still "resolved
  successfully".
- **artifactIds stay lowercase.** GitHub Packages rejects uppercase with HTTP 422 before
  checking anything else.
- **maven-shade-plugin 3.6.2 or newer.** Relocation rewrites bytecode, and the ASM bundled
  with earlier releases cannot read Java 25 class files.
- **`-Xlint:all -Werror`** is on. A warning fails the build.

## CI

`Jenkinsfile` uses `temurin-25` and `maven-3`, builds with `clean install`, deploys to
GitHub Packages from `main` only, and treats an HTTP 409 from Packages as "this version is
already published" rather than a failure — to publish changed code under a release
version, bump the version.

The branch is resolved with `git for-each-ref --points-at HEAD` rather than
`when { branch 'main' }`: that directive only matches in multibranch jobs, and in a plain
Pipeline job it silently never fires while the build still reports success.
