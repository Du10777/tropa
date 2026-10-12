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
  - `java/io/github/du10777/tropa/` — Kotlin sources (`data/` storage and link parsing,
    `service/` service state, `ui/` Compose screens);
  - `res/` — Tropa resources, every name prefixed with `tropa_`;
  - `res-override/` — resources attached to the build type source sets, so they override upstream
    ones: app name and labels, launcher and notification icons;
  - `AndroidManifest.xml` — overlay merged above the upstream manifest: the Tropa screen becomes the
    launcher, the upstream `MainActivity` keeps only the TV launcher entry, links and shared text go
    to Tropa;
  - `test/java/` — JVM unit tests (run with `testPlaystoreDebugUnitTest`).
- `.github/workflows/tropa-build.yml` — CI: builds `libv2ray.aar` from the AndroidLibXrayLite commit
  pinned by the submodule plus our `tropa_*.go` files, builds hev, signs and uploads the APKs.
- `.github/README.md` — the repository front page (GitHub prefers it over the upstream `README.md`).
- AndroidLibXrayLite fork: only new `tropa_*.go` files.

## Changes to upstream files

Every change to a file that also exists upstream is listed here and marked with `FORK:` in the code.

| File | Change | Why |
|---|---|---|
| `V2rayNG/app/build.gradle.kts` | `apply(from = "tropa.gradle")` at the end | single hook for the build overlay |
| `V2rayNG/app/src/main/java/com/v2ray/ang/ui/main/MainActivity.kt` | `BackHandler` finishes the screen unless it is the task root | Back from the advanced mode returns to the Tropa screen instead of hiding the app |

## Building locally

Requirements: JDK 21, Android SDK (platform 37, build-tools 37.0.0), NDK 30.0.16248370, Go (version
from `AndroidLibXrayLite/go.mod`) with gomobile.

1. Build `libv2ray.aar` in `AndroidLibXrayLite` (see its CI) and copy it to `V2rayNG/app/libs/`.
2. Build hev with `compile-hevtun.sh` and copy `libs/` to `V2rayNG/app/`.
3. `cd V2rayNG && ./gradlew assemblePlaystoreRelease` with the `android.injected.signing.*`
   properties pointing to the signing key.

Upstream workflows (`build.yml`, `fastlane.yml` here; `main.yml`, `tidy.yml` in the library fork) are
disabled in the GitHub Actions settings of the forks, so they never run on mirror pushes.

## CI secrets

`TROPA_KEYSTORE_BASE64`, `TROPA_KEYSTORE_PASSWORD`, `TROPA_KEY_ALIAS`, `TROPA_KEY_PASSWORD`.
