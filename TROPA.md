# Tropa fork layout

Tropa is a fork of [2dust/v2rayNG](https://github.com/2dust/v2rayNG). The fork is organised so that
upstream changes can be merged with a plain `git merge` and conflicts stay rare and small.

## Branches

| Repository | Tropa branch (default) | Upstream mirror |
|---|---|---|
| `Du10777/tropa` | `tropa` | `master` = `2dust/v2rayNG@master` |
| `Du10777/AndroidLibXrayLite` | `tropa` | `main` = `2dust/AndroidLibXrayLite@main` |

## Where Tropa code lives

- `V2rayNG/app/tropa.gradle` — all fork-specific build logic: application id, version, APK names,
  resource overlays, disabled fdroid variants.
- `V2rayNG/app/src/tropa/` — everything else of Tropa:
  - `java/io/github/du10777/tropa/` — Kotlin sources (`data/` storage, link parsing, server plans
    and selection; `service/` service state and the connection watchdog, which runs in the VPN
    service process; `ui/` Compose screens);
  - `res/` — Tropa resources, every name prefixed with `tropa_`;
  - `res-override/` — resources attached to the build type source sets, so they override upstream
    ones: app name and labels, launcher and notification icons;
  - `AndroidManifest.xml` — overlay merged above the upstream manifest: the Tropa screen becomes the
    launcher, the upstream `MainActivity` keeps only the TV launcher entry, links and shared text go
    to Tropa;
  - `test/java/` — JVM unit tests (run with `testPlaystoreDebugUnitTest`).
- `.github/workflows/tropa-build.yml` — CI: builds `libv2ray.aar` from the AndroidLibXrayLite commit
  pinned by the submodule plus our `tropa_*.go` files, builds hev, signs and uploads the APKs; a push
  of a new version (tropa.gradle) publishes the GitHub release `v<version>` with the notes of
  `V2rayNG/app/src/tropa/changelog/v<version>.md`, which the in-app updater offers.
- `.github/workflows/tropa-sync.yml` — the nightly upstream sync, see below.
- `.github/README.md` — the repository front page (GitHub prefers it over the upstream `README.md`).
- `TROPA-SERVER.md` — for server owners (in Russian): the subscription headers Tropa sends and reads,
  the `Tropa-Checks` check parameters, problem reports, one-time links.
- AndroidLibXrayLite fork: only new `tropa_*.go` files. `tropa_api.go` adds `TropaProbe` (fetch a URL
  through the running core, optionally forced through one outbound), `TropaOverrideBalancer` and
  `TropaBalancerTarget` (steer a balancer in process, no gRPC port), `TropaAddOutbound` (add a server
  of an updated subscription to the running core, so it is used without a restart).

## Changes to upstream files

Every change to a file that also exists upstream is listed here and marked with `FORK:` in the code.

| File | Change | Why |
|---|---|---|
| `V2rayNG/app/build.gradle.kts` | `apply(from = "tropa.gradle")` at the end | single hook for the build overlay |
| `V2rayNG/app/src/main/java/com/v2ray/ang/ui/main/MainActivity.kt` | `BackHandler` finishes the screen unless it is the task root | Back from the advanced mode returns to the Tropa screen instead of hiding the app |
| `V2rayNG/app/src/main/java/com/v2ray/ang/core/CoreServiceManager.kt` | `coreController` and `reloadCore()` are `internal` instead of `private` | the watchdog probes servers, steers the balancer and reconnects without dropping the VPN |
| `V2rayNG/app/src/main/java/com/v2ray/ang/service/CoreVpnService.kt` | `onStartCommand` calls `LocalProxy.refreshCredentials()` before `setupVpnService()` | a new port, login and password for the internal SOCKS inbound on every start, before hev (started first) and the core read them |
| `V2rayNG/app/src/main/java/com/v2ray/ang/core/CoreServiceManager.kt` | `launchCore` passes the config through `LocalProxy.patchConfig` | local inbounds of JSON subscriptions (10808/10809 without a password) are replaced by the protected internal one, the user's own local proxy is added |
| `V2rayNG/app/src/main/java/com/v2ray/ang/core/CoreServiceManager.kt` | `reloadCore()` wakes `TropaWatchdog` after the core is back | after a network handover the watchdog puts its server choice back at once (and follows the saved config if a subscription was applied on the fly) instead of on its next round |

## Building locally

Requirements: JDK 21, Android SDK (platform 37, build-tools 37.0.0), NDK 30.0.16248370, Go (version
from `AndroidLibXrayLite/go.mod`) with gomobile.

1. Build `libv2ray.aar` in `AndroidLibXrayLite` (see its CI) and copy it to `V2rayNG/app/libs/`.
2. Build hev with `compile-hevtun.sh` and copy `libs/` to `V2rayNG/app/`.
3. `cd V2rayNG && ./gradlew assemblePlaystoreRelease` with the `android.injected.signing.*`
   properties pointing to the signing key.

Upstream workflows (`build.yml`, `fastlane.yml` here; `main.yml`, `tidy.yml` in the library fork) are
disabled in the GitHub Actions settings of the forks, so they never run on mirror pushes.

## Nightly upstream sync

`tropa-sync.yml` runs every night at 01:17 UTC (04:17 Moscow time) and by hand (Actions → Upstream
sync → Run workflow, optionally with another upstream branch or tag, or "build even if nothing is
new"):

1. mirrors `2dust/v2rayNG@master` into our `master`;
2. merges it into `tropa` on a temporary branch `upstream-sync` (commit message with `[skip ci]`);
3. builds that branch with `tropa-build.yml` (called as a reusable workflow, no release);
4. if the build is green, fast-forwards `tropa` to it and deletes `upstream-sync`.

A merge conflict, a broken build or a failed push opens an issue labelled `upstream-sync` (later
failures comment on the open one, and GitHub mails the owner); the next sync that goes through
closes it. To fix a conflict: merge upstream into `tropa` locally, resolve, build, push; the next
night finds nothing new and closes the issue. Upstream changes reach people with the next Tropa
version, the sync itself never publishes a release.

GitHub turns off scheduled workflows of public repositories after 60 days without activity; the
sync's own merges count, so this only matters if upstream stops changing for two months.

## CI secrets

`TROPA_KEYSTORE_BASE64`, `TROPA_KEYSTORE_PASSWORD`, `TROPA_KEY_ALIAS`, `TROPA_KEY_PASSWORD` (signing).

`TROPA_SYNC_TOKEN`: a fine-grained token with Contents and Workflows write access to this repository,
used by the upstream sync, because upstream commits can change workflow files and `GITHUB_TOKEN` may
not push those. When it expires, the sync fails and opens its issue: create a new token and put it
into the secret (`tools/gh.py sync-token Du10777/tropa` uploads the token of the git credential
manager).
