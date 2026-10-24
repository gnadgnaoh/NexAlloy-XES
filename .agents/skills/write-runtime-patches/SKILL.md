---
name: write-runtime-patches
description: Write or fix NexAlloy DexKit/Xposed runtime patches, diagnose patch crashes, and adapt fingerprints across app versions. Use merge-upstream-patches for upstream version syncs.
---

# Write runtime patches

Apply these conventions to local patches under `app/src/main/java/io/github/nexalloy/morphe`.
Read [DEVELOPMENT.md](../../../DEVELOPMENT.md) for the repository's APIs and
[test-fingerprints](../test-fingerprints/SKILL.md) when preparing APK inputs and
running matching tests. Use the current compatibility APIs
and nearby ports when older examples in the development guide differ.

## Establish the behavior

Compare the local port with the upstream source in the checked-out `morphe-patches`
submodule. Trace the selected instruction, the value it consumes or produces, and
the intended call scope. Use logs and the original APK's bytecode to distinguish an
upstream defect from a runtime-port defect; a matching fingerprint alone does not
establish that the hook is correct.

Prefer a business method or the producer of the required value as the injection
point. Preserve upstream timing, conditions, and value flow when choosing an
alternative. Avoid a shared low-level method that the hook callback itself can
call, since that can cause re-entry. For a single call site inside an outer method,
use `scopedHook` and restrict the receiver when the target has other uses in that
scope. Preserve meaningful null and exception behavior.

## Keep matching declarative

- Prefer `Fingerprint`, `methodCall`, `fieldAccess`, and instruction locations over
  manual instruction offsets or register tracing. Extract `methodRef`, `fieldRef`,
  and `classRef` from `instructionMatches`.
- Modify the existing fingerprint directly when a small adaptation is sufficient.
  Mark local deviations with a short `// NexAlloy: ...` comment explaining why.
- When old and new structures differ substantially, split them into separate
  fingerprints. Append `Legacy` to the older fingerprint's name and select the
  branch through the existing `VersionCheck` flags. Declare the patch's dependency
  on `VersionCheck` before reading those flags.
- Verify the version boundary using APKs immediately below and at or above it.
  Do not infer a cutoff from a gap in available samples. If variants of the same
  version differ, a version flag alone is insufficient; use a focused structural
  fallback for that case.
- Expose fixed fields with distinct roles through separate `findFieldDirect`
  finders. Use a field-list finder for an actual collection, not a fixed receiver
  path. Expose methods and classes through the corresponding direct finders.
- Keep the implementation small. Avoid target-wrapper objects and repeated
  validation checks when matched references already provide the required data.
  Retain conditions needed for correct runtime scope, receivers, and results.

DexKit query uniqueness and instruction matching are different checks. Use
`match()` or `instructionMatches` to validate the instruction sequence; `run()`
alone does not validate filter order or distance. Recheck extraction indices after
changing filters. Version-gate fingerprints and their final finders consistently
with `@RequireAppVersion` and `@get:RequireAppVersion` so tests execute the applicable
branch rather than treating an incompatible branch as a regression.

## Verify the final targets and runtime

Build with `./gradlew :app:assembleDebug`, then follow
[test-fingerprints](../test-fingerprints/SKILL.md) to verify the final targets on
applicable versions, including both sides of every changed version branch.

When device validation is requested, install the debug build, reproduce the
trigger, and capture logs. Confirm that the intended version branch and hook
targets actually loaded. Distinguish matching, startup stability, and feature
behavior in the result; startup alone does not verify a gesture or other feature.
