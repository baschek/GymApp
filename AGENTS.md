# GymApp Development Guide

## Repository Layout

This public repository contains two apps:

- **Legacy PWA** (repository root: `src/`, `public/`, `e2e/`, `package.json`).
  The React/Dexie web app deployed to GitHub Pages by `.github/workflows/ci-pages.yml`.
  Keep it working and untouched unless the owner asks otherwise. Everything in the
  sections "Current Phase" to "Stable Release Gate" below applies to it.
- **Native Android app** (`android/`). Kotlin, Jetpack Compose, Room. Data lives in
  Room (SQLite) and is synchronized with a sync server, because Android evicted the
  PWA storage once and data was lost. Protocol: `android/docs/sync-protocol.md`.

Android modules:

- `android/core`: pure Kotlin/JVM (no Android dependencies): contract documents,
  unknown-field-preserving JSON merge, progression and recalculation rules, sync
  engine against interfaces, Ktor API client, release checker. Unit-testable with
  `./gradlew :core:test`.
- `android/app`: Android application (Room, repository, WorkManager sync, timer
  foreground service, updater, minimal Compose UI in `ui/<feature>/` with ViewModels).
  The repository (`data/GymRepository`) is the only code that touches the database.

## Android Rules

- **Public repository.** Never commit secrets or personal data: no tokens, pairing
  codes, keystores or passwords, no server host names or addresses of the owner,
  no real training data (use synthetic data in tests and examples), no backups
  (`gymapp-backup-*.json` is ignored). The app only knows "a sync server" whose
  URL the user types in when pairing. Secrets for releases live in GitHub Actions
  secrets only. A gitleaks scan runs on every push and pull request.
- **Room migration duty.** The on-device database is the primary copy of the data
  until it is synced. `exportSchema = true`; schemas are committed in
  `android/app/schemas`. Every entity change needs a version bump, a `Migration`,
  the new committed schema file and a migration test. Never use destructive
  migrations. CI fails when existing schema files change.
- **Test profile.** The Test profile is local-only: its documents have
  `profile = 'test'`, never get outbox entries and are never touched by pulls or
  snapshots. Use it for destructive experiments and demo data.
- **Contract.** Do not change the sync protocol on one side only. Documents keep
  unknown fields (`DocumentCodec`); never rebuild a document from typed fields alone.
- **Progression.** `core/progression` is the single implementation. Behavior is
  pinned by `core/src/test/resources/progression_golden.json`; change the rules only
  together with the golden cases.
- **Per-gym weights.** Working weights and progression exist per gym x exercise (`machine_profile.working_weight_kg`); plan weights are legacy fallbacks. A session always has a gym, confirmed explicitly at start; changing the gym of a finished session goes through `GymRepository.reassignSessionGym` so both gyms are recalculated.
- **Workout robustness.** While a session is active, `MainActivity` keeps the screen on (window flag, re-applied on every start) and `WorkoutService` shows one ongoing notification (actions "Satz geschafft", "Pause überspringen"). Timer state is persisted in the settings table and restored at app start; keep it that way when changing timers.
- **Live Update.** On Android 16+ the ongoing workout notification requests promotion (`setRequestPromotedOngoing`, `ProgressStyle` per exercise, `setShortCriticalText` "Satz n/m", rest countdown as chronometer); older versions show the plain ongoing notification with a chronometer. Needs androidx.core 1.17+, `compileSdk 36`, permission `POST_PROMOTED_NOTIFICATIONS`; not colorized, no custom views. Duration estimates ("≈ 52 min") are learned from completed sessions in `core/stats/DurationEstimate.kt`.
  Promotion prerequisites (checked in `WorkoutService`, logged as `promotable/allowed/importance` without content): ongoing + title + not colorized + no custom views + style none/ProgressStyle + channel importance above MIN (channel `timer_live` is DEFAULT, silent; the old LOW channel `timer` is deleted because importance cannot be raised later). The user must additionally allow "Live-Benachrichtigungen" per app (`NotificationManager.canPostPromotedNotifications()`, `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`); Settings shows the status (`timer/LiveUpdateSupport.kt`) and the notification dialog before the first workout hints at it once. During a running rest the status-bar chip shows the ticking chronometer; the short critical text is "Satz n/m" otherwise and "Los!" once the planned rest is over. Samsung One UI 8: the Now Bar shows standard Android 16 promoted notifications of third-party apps (no Samsung SDK needed), but only if the app-level switch is on; Samsung may additionally list apps under Settings > Notifications > Live notifications/Now Bar. Research (secondary press/community sources, no official Samsung docs found): One UI 8 beta 3 had a developer-style option "Live notifications for all apps"; on release builds users report enabling it per app (App info > Notifications > Live notifications) and in the Now Bar settings; some reports say few third-party apps show up. Not verified on the owner's device; check there and update this note.
- **Assistant adjustments (amendment D).** While a session is active, `WorkoutService` syncs about every 45 s (`core/sync/WorkoutPoll`: doubling backoff up to 5 min, reconnect backoff still applies) and `MainActivity` syncs on every start. After each sync `SyncManager` calls `GymRepository.applyPendingAdjustments()`: pending `workout_adjustment` documents are applied to the active session through `core/training/WorkoutAdjustment.kt` (never to a logged set; skipped operations carry a code), the result is written back and pushed at once. The training/rest screens show `AdjustmentBanner` ("Claude hat angepasst: ... (Begründung)", "Rückgängig"/"OK"); undo restores the exercise list saved in `settings` (`adjust_undo:<id>`, device only) and writes `rejected`/`undone`. A weight set by an adjustment is planned on the open working sets (`weight_kg` of a pending set) and cleared by the next logged working set; prefill order is planned, last logged, suggestion. Notification and "Satz geschafft" follow the focus exercise (`upNextExerciseIndex`).
- **Manual working weight.** `machine_profile.working_weight_source_session_id == "manual"` is a baseline for `recalculateWorkingWeights`/`finishSession`/`reassignSessionGym` (`baselines`, `GymRepository.manualBaselines`), same rule as the server; golden cases `manual baseline: ...`.
- **Rest end alarm.** The end of a planned rest is signalled by `timer/RestAlarmScheduler` (AlarmManager `setExactAndAllowWhileIdle`, fallback `setAndAllowWhileIdle` when exact alarms are revoked; permission `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` up to API 32) and `RestAlarmReceiver`, which vibrates once (respects `AppPrefs.restVibration`) and posts a silent heads-up "Pause vorbei". `WorkoutService` sets/replaces/cancels the alarm on every timer change (`core/training/RestAlarm`: +15 s moves it, skip, set logged and workout end cancel it). The UI must not vibrate additionally.
- **Device key.** Every authenticated request is signed with the device's Keystore key (`core/api/RequestSigning.kt`, protocol amendment B). Unpairing deletes token and key. A 404 after a successful sync means "disconnected"; retry with backoff, never in a loop.
- **Network.** HTTPS only (`network_security_config`); cleartext only in debug
  builds for `10.0.2.2`/`localhost`. No exported components except the launcher
  activity. Never log tokens or request bodies.
- **Releases.** Push a tag `android-v<version>` (for example `android-v0.2.0`) on a
  commit on `main`. The workflow `android.yml` builds a signed APK (keystore from the
  secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD`) and publishes it as a GitHub release; the in-app updater
  installs it. Do not create release tags unless the owner asks.
- **UI.** Compose screens live in `ui/<screen>`, shared pieces in `ui/components`, tokens in `ui/theme` (dark only, accent from DataStore). Gyms, their names and colours come only from synced gym documents; never hardcode them. Every interaction calls `Telemetry.track(screen, element)` (helper `tracked`). Pure logic belongs in `:core` with tests. The muscle map body (front and back, individually tinted regions) is third-party artwork (MIT, see `THIRD_PARTY_NOTICES.md`): path data in `ui/components/BodyArtPaths.kt`, region-to-muscle-group mapping in `core/model/BodyRegions.kt` (tested); keep the notice when changing the artwork.
- **Checks before committing:** `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.

---

# GymApp Beta Development Guide (legacy PWA)


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
