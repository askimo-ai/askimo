# Testing & Coverage

This document covers how to run the test suite, how test coverage is measured and reported,
and where the current coverage gaps are.

## Running tests

```bash
./gradlew test                 # all modules
./gradlew :shared-jvm:test     # a single module
```

Some `cli` integration tests spin up real models via Testcontainers/Ollama and need Docker.
Set `DISABLE_DOCKER_TESTS=true` to skip them (this is what CI's Windows leg does, since
Testcontainers isn't available there).

## Coverage reporting

Coverage is measured with [Kover](https://kotlin.github.io/kotlinx-kover/) and aggregated at
the root project, which is configured as Kover's "merging module" (`dependencies { kover(project(":x")) }`
for every module). This gives a genuine, Kover-native merged total in addition to each
module's own report — not a hand-rolled average.

```bash
./gradlew coverageReport
```

This runs every module's tests, generates each module's own Kover XML report plus the root's
merged XML report, and produces:

- **Console summary** — merged total + a one-line-per-module breakdown.
- **HTML dashboard** — `build/reports/kover/coverage-summary.html` (combined total + per-module
  table; open with `open build/reports/kover/coverage-summary.html` or any browser).
- Per-module native Kover reports remain available too, e.g.
  `shared-jvm/build/reports/kover/report.xml` / `.../html/index.html`.

SQLDelight-generated code (`io.askimo.core.db.sqldelight*`) is excluded from all coverage
numbers — it's generated, never hand-written, and would only dilute the real signal.
The `:detekt-rules` module (a build-tooling module with no test source) is excluded entirely.

`coverageReport` depends on the same `test` tasks as `./gradlew test`: if a module's tests
fail, that module's coverage numbers won't refresh until the failure is fixed (same as any
other Gradle task that depends on `test`). This is intentional — it mirrors the project's
existing pass/fail semantics rather than silently reporting against stale or partial data.

### CI

Every PR run (`.github/workflows/shared-tests.yml`, both Linux and Windows legs) runs
`coverageReport` right after `test` and uploads `build/reports/kover/**` as a
`coverage-reports-<os>-attempt<N>` artifact. This step is report-only
(`continue-on-error: true`): it never fails the job by itself — job pass/fail is still decided
entirely by the `test` step.

### Non-regression ratchet

`gradle/coverage-baseline.json` records the last-known-good **line** coverage percentage for
the merged total and each module (branch coverage is reported but not ratcheted, since Kover's
branch counting can shift slightly between versions without any real code change).

```bash
./gradlew checkCoverageRegression   # fails if coverage dropped below baseline - tolerance
./gradlew updateCoverageBaseline    # overwrites the baseline with today's numbers
```

`checkCoverageRegression` is **not** currently wired into CI as a required/blocking check —
it exists so the numbers can be ratcheted deliberately once the team decides to start
enforcing a floor. Only run `updateCoverageBaseline` after reviewing *why* a number changed;
never run it just to make a failing check pass.

## Current coverage gaps (as of this writing)

Overall merged line coverage is low (~15%), concentrated almost entirely in `cli` and
`shared-jvm`. The following areas have little to no test coverage today and are good
candidates for follow-up work (tracked separately from the tooling work in this document):

- **External agent integrations** (`desktop-shared`): `ExternalAgent`, `ClaudeAgent`,
  `CodexAgent`, `AntigravityAgent`, `ExternalAgentLoader` — the classes most directly involved
  in the streaming-order bug fixed alongside this tooling.
- **Desktop ViewModels** (`desktop-shared`): `AgentRunViewModel`, `ChatViewModel`,
  `SessionsViewModel`, `AgentsViewModel`, and friends.
- **`:shared` (Kotlin Multiplatform common module)**: currently has no `commonTest` source set
  at all (0% by construction, not just low coverage).

Adding tests for these is intentionally out of scope for this change (tooling + reporting
only) — see the coverage dashboard for the full, current per-module/per-class breakdown.
