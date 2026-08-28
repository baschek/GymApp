# GymApp Beta Development Guide

## Current Phase

- The current public release is `v0.2.0-beta.2`, titled
  `GymApp 0.2.0 Beta 2`.
- Production is deployed at `https://baschek.github.io/GymApp/`.
- The owner is now testing the app in real workouts on Android.
- Expect the next sessions to begin with informal bug reports, screenshots, or
  examples of unexpected training data. Reports may be in German or English.
- The next stable release may be `v1.0.0`, but it must not be created until the
  owner explicitly asks for it after the beta period.

## Branch And Release Rules

- Perform ongoing development on `dev`. Do not commit feature or bug-fix work
  directly to `main`.
- Keep fixes narrowly scoped and preserve unrelated user changes.
- Do not force-push, rewrite release history, or delete user data.
- Batch tested beta fixes into a pull request from `dev` to `main`.
- Do not merge, deploy, tag, or publish a new release unless the owner asks to
  proceed with a release.

## Bug Report Workflow

When the owner reports an error:

1. Restate the expected and observed behavior in concrete terms.
2. Gather only missing information that cannot be learned from the repository,
   screenshot, or stored example.
3. Reproduce the smallest relevant workflow when possible.
4. Identify and explain the root cause before or alongside the fix.
5. Implement the fix on `dev` without unrelated refactoring.
6. Add or update regression coverage when practical, then run the relevant
   unit, browser, lint, and build checks.
7. Report changed files, verification results, and any remaining risk.

Useful details to obtain when they matter:

- personal or Test profile
- selected gym and machine
- installed Android PWA or desktop browser
- exact exercise, workout plan, and set sequence
- imported file and relevant row
- whether the behavior also occurs in a newly created workout

## Data Safety

- Training data is stored locally in the browser using IndexedDB. There is no
  account or cloud synchronization.
- Never clear browser storage or recreate the database while debugging a real
  report unless the owner explicitly approves it and has a current backup.
- Prefer the isolated Test profile for destructive reproduction.
- Preserve compatibility with existing stored data and documented import
  formats. Add a migration when a schema change requires one.
- Import specifications are in `docs/EXERCISE_IMPORT_STANDARD.md`,
  `docs/HISTORICAL_WORKOUT_TRANSCRIPTION.md`, and
  `docs/WORKOUT_PLAN_STANDARD.md`.

## Stable Release Gate

Before proposing `v1.0.0`:

- The owner has completed real-workout beta testing and approves the release.
- No known issue blocks recording, editing, importing, backing up, or restoring
  workouts.
- Suggested weights and set/rest timing have been verified with real history.
- Android installation, update, offline launch, and recovery have been checked.
- Lint, unit tests, Android and desktop E2E tests, production build, security
  audit, and asset validation all pass.
- Version, changelog, README, beta documentation, and release notes are updated.
- The release is merged through a green pull request, deployed successfully,
  checked at the production URL, and only then tagged.

See `docs/BETA_TESTING.md` and `docs/RELEASE_PROCESS.md` for the detailed
validation and publishing procedures.
