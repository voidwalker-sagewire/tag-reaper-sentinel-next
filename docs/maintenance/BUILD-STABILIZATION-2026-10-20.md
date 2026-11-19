# Build stabilization — BUILD-STAB-20261020

Date: 2026-10-20
Status: Prepared on an isolated branch; merge, deployment, and field acceptance are not performed by this change.

## Authorization and baseline

Michael David Miller authorized preparation of the build stabilization changes in the Mobile Development Brief conversation on October 20, 2026.

Repository: `voidwalker-sagewire/tag-reaper-sentinel-next`
Target branch: `codex/sentinel-next-field-repair-r1`
Inspected source commit: `cabc9ac722e52ace59e4e6e836b8c9c28e6d9c99`

## Problem and change

GitHub is moving `ubuntu-latest` from Ubuntu 24.04 to Ubuntu 26.04 during October 19–November 19, 2026. Pin 1 job to `ubuntu-24.04` so the workflows do not inherit that major OS change automatically.

- `.github/workflows/build-apk.yml`

This pins the OS release, not an immutable runner image. GitHub still updates the Ubuntu 24.04 image, and existing action tags and dependency constraints keep their existing behavior.

## Scope and validation boundary

The refreshed change pins the runner, selects patched Gradle 8.14.7, runs the existing recovered R1 harness, and updates maintenance documentation. Existing triggers, permissions, application dependencies, signing, artifact retention, product versions, and deployment behavior are preserved. This work does not merge the branch, publish an app, deploy a service, flash firmware, or establish field acceptance.

Local validation compares parsed workflow structure before and after: the runner label, selected Gradle distribution, and insertion of the existing R1 harness check may differ. The runner label must be `ubuntu-24.04`. Remote source is read back after writing. Existing GitHub event rules determine whether CI runs; the pull request checks/run history are the authority for compiler and test results. A runner-label pin alone is not a claim of passing CI.

Deployment, release-signing, and automatic version-bump workflows are not manually dispatched for validation. Any workflow limited to pushes on its target branch cannot be runtime-verified from this preparation branch without a separate approved trigger change or test dispatch.

## Resume point

Review this maintenance-only diff and available CI results before merging into `codex/sentinel-next-field-repair-r1`. Prepare a separate Ubuntu 26.04 compatibility test when migration is desired; do not remove this pin merely because the label migration completes. Existing product work orders and release/field acceptance states retain their authority.

Source: https://github.blog/changelog/2026-09-17-ubuntu-26-generally-available-and-latest-migration/

## Refresh — November 19, 2026

- Current target: `codex/sentinel-next-field-repair-r1` at `cabc9ac722e52ace59e4e6e836b8c9c28e6d9c99`.
- Preserved the R1 repair branch as this PR's target. This change does not promote R1 into main or field acceptance.
- Application source, hardware SDK, product versions, and test-source files are unchanged.
- Updated CI Gradle 8.5 to patched 8.14.7 for the November 10 advisories: https://github.com/gradle/gradle/security/advisories/GHSA-3cfg-jpcr-gfp3 and https://github.com/gradle/gradle/security/advisories/GHSA-gqrr-4j89-5267 .
- Added execution of the published recovered R1 Java harness. It uses a simulated Handler and does not exercise Android or hardware.
- The existing Gradle app/ build produces the older Rev3 0.6.0 debug APK. It does NOT reproduce the recovered 0.7.1-next-r1 APK; that requires the separately preserved Preview APK and the documented field-repair tooling.
- Exact verification status and run links are maintained in [PR #1](https://github.com/voidwalker-sagewire/tag-reaper-sentinel-next/pull/1).
- No merge, installation, hardware operation, Play publication, or field acceptance is included.

