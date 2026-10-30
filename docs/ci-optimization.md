# Shader CI scheduling and provenance

This change is based on main `6c33695c4305eb5ebcb2ca52412276a409c13780`.
It does not contain the separate requested-generation port. Released dependency
pins, all five target loaders/versions, production tests and native scenarios
remain unchanged.

## Coverage equivalence

`tools/shader/coverage-manifest.json` was generated from the executable
`shader_test.py` at that baseline, then independently compared with `git show`
of the baseline after implementation. The canonical compact sorted-JSON SHA-256 is
`01857e1735a2c2ecfda422ff5a1bc813526bf8d2f4a501cd8d452275460a21ec`.
It records every scenario field, sample count and pack option:

- 10 variants, 92 scenarios, 380 captured samples per target;
- all five targets: 50 variants, 460 scenarios, 1,900 captured samples;
- additionally, all ten-frame convergence proofs for the seven paired variants
  (two scenes each) remain required;
- original distortion controls, reload, alpha, repeat stability, factorial glow,
  full facings/distances/angles/occlusion and actual renderer-mod checks remain.

The temporal shard runs shader-off/original/fixed. The paired shard keeps all
seven static controls, independent repeats and all comparisons on **one runner**.
Fresh worlds and real server/client startup remain per variant. The change does
not attempt to reuse game state or run competing native sessions on one runner.
No thresholds, failure semantics, security/session rules or timeouts are reduced.

The required job rejects missing/duplicate shards, stale/dirty sources, altered
scenario manifests, artifact hashes and inconsistent graphics or actual client
Java vendor/version/architecture. Production verification and standalone
PacketWeave builds occur once per target, not twice. Every consumer verifies the
same workflow's exact-head/target artifact, byte digest, source snapshot, pinned
PacketWeave source, actual launchers, and the resolved Gradle compiler/release
that produced its native classes (also checked against class-file versions). The
standalone root compileJava task executes freshly once per target to bind that
compiler report; consumers do not recompile it. Production verification records
its launcher, not a claim about every production compiler. A failed production
build cannot produce a successful prerequisite receipt.

## Scheduling and resources

The build phase is bounded to five runners. The shader phase is bounded to ten
runners, each with at most one server/client pair. It still starts fifty native
pairs overall. This trades fleet concurrency and extra isolated-runner setup for
shorter critical-path latency; **it is not a claim of lower runner-minutes**.
Limited organization capacity or other workflows can queue the shards and erase
that advantage. Artifact transfer and aggregate verification are included in
end-to-end timing, not hidden behind a harness-only number.

Each shard emits per-variant monotonic durations, dependency-cache state, runner
image/CPU count and `/usr/bin/time -v` output. GNU time's maximum RSS is the
reported subprocess high-water mark, not aggregate simultaneous host memory.
Do not describe it as whole-runner peak RAM. The original unsplit CLI remains
available (omit `--shard`) for same-runner comparisons.

## Measured baseline and measurement protocol

Successful main workflow [39578384237](https://github.com/UpperMoon0/Simply-Screens/actions/runs/39578384237)
took 50m36s end-to-end. Its NeoForge 1.21.1 job
[119152253244](https://github.com/UpperMoon0/Simply-Screens/actions/runs/39578384237/job/119152253244)
ran 09:43:45–10:25:06 UTC on 2026-10-21. The seven job log execution
windows sum to 11,611.536 seconds (193.526 runner-minutes), excluding queue
time; this is execution time, not a claim about billable rounding. The prepare
job had a cold OpenUI key miss and populated the cache for all five shader jobs. Its OpenUI cache was a verified
primary-key **hit**, so this is not a fully cold baseline. Shader download caching
was not configured in that workflow. The first temporal variant began 09:48:54,
and the paired group began 10:06:44: 17m50s of temporal work before the paired
controls. This identifies independent work; it does not measure a speedup.

After results must be tied to the exact PR head and report:
1. workflow wall time, queue delays and summed job execution time;
2. successful scenario/receipt count and the aggregate gate result;
3. each relevant cache hit/miss rather than labeling a mixed run simply warm;
4. per-variant time and GNU time measurements where collected;
5. comparison to the baseline with runner image and cache differences disclosed.

The separate existing Cold OpenUI workflow intentionally uses an empty Gradle
home and no Maven cache and remains mandatory when its path filter matches.
Cold and warm dependency integrity are regression-tested: checksum-matching
shader downloads can be reused; poisoned entries must be replaced by a matching
download or fail. OpenUI build caches are keyed by immutable source, helper and
action contents, OS, architecture and declared JDK21. Native production outputs
are **not** reused across workflow runs. No timings are estimated as measured
results; actual exact-head CI results belong in the PR validation record.

## Integration with requested-generation ports

The optimization applies to the maintained native acceptance matrix; it must not
change the configured release version, dependency floors, target manifests or
requested-generation qualification. It does not replace separate native-port
acceptance or imply runtime coverage beyond the recorded checks. Legacy
1.16.5 retains Java 8. Existing dedicated-client eligibility limitations remain
explicit in the port documentation.
Historical 26.2 render calibration remains a separate qualification limit;
this integration does not weaken its gates or convert inconclusive evidence to a pass.

Exact-head CI after integration must pass before this PR is ready. An artifact
quota failure is an infrastructure blocker, not a passing qualification result.
