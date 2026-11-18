# Changelog

All notable GymApp changes are documented here.

## [Unreleased]

## [android-v2.9.1] - 2026-11-19

Native Android app release (GitHub release `android-v2.9.1`) with phone APK
`gymapp-2.9.1.apk` and watch APK `gymapp-watch-2.9.1.apk`. Update phone and watch together.

### Fixed

- Timed exercises (Farmer's Hold, plank): the stopwatch is now the big main action "Zeit starten" in the pinned bottom area of the set screen instead of a small button below the input block. While it runs the time is shown large (mm:ss) next to the target, and "Stopp und Satz speichern" saves the measured time as seconds. The seconds wheel stays for manual values, and a set can be saved without measuring.
- Timed exercises are always measured in seconds, whatever the plan says: an exercise created in the plan editor, added during the workout or by the assistant, or taken from a plan with a rep target had the metric "reps", so phone and watch offered no stopwatch. The plan editor shows and edits seconds targets in 5 s steps up to 10 minutes (it said "Wdh." and stopped at 60).
- During a measurement the notification shows the running time instead of the planned value.

## [android-v2.9.0] - 2026-11-18

Native Android app release (GitHub release `android-v2.9.0`) with phone APK
`gymapp-2.9.0.apk` and watch APK `gymapp-watch-2.9.0.apk`. Update phone and watch together.

### Fixed

- Phone and watch recover incomplete workout transfers, retry pending changes promptly, and process workout events independently. Server pulls preserve active workouts with local changes or a watch replica. The watch shows how many changes are still pending.
- During live handoff, the watch waits for a burst of phone edits to settle before changing the workout view and keeps edits in progress.
- Health Connect: deleting a personal workout also removes its exported exercise session and heart-rate records. Exercise records now carry the recording device, the plan name as title and time-zone offsets, so earlier exports are replaced once.
- Watch: the header shows only the time and, while recording, the pulse; long exercise names stay inside the round display (two lines, smaller font); round buttons use monochrome icons instead of text symbols, including a clean heart for the pulse.

### Changed

- The navigation recorder is always on for phone and watch, without a switch. It starts a new recording per workout and after 3 h, keeps the newest 20 recordings for at most 14 days (phone 100 MB, watch 20 MB), takes no watch screenshots in ambient mode, and can be shared or deleted in the settings. Masking is unchanged.

## [android-v2.8.1] - 2026-11-18

Native Android app release (GitHub release `android-v2.8.1`) with phone APK
`gymapp-2.8.1.apk` and watch APK `gymapp-watch-2.8.1.apk`. Update phone and watch together.

### Fixed

- Phone and watch resolve repeated disputes about the same set in one choice. The phone clears conflicts for deleted workouts on startup and reports a failed resolution.
- The conflict notice and the Test profile banner clear the phone status bar. Settings and workout screens account for the space already taken by the banner.
- On reconnect, both devices apply a pending workout batch before updating the visible session, focus and timer. Each event retains its original ID, predecessor and individual acknowledgement.

## [android-v2.8.0] - 2026-11-18

Native Android app release (GitHub release `android-v2.8.0`) with phone APK
`gymapp-2.8.0.apk` and watch APK `gymapp-watch-2.8.0.apk`. Roll out the TESS
server change (progression amendment H) first.

### Added

- Watch: redesign after the design spec (`android/docs/watch-design-spec.md`) on Wear Material 3: time on every screen, three pages (exercises, set, training) with page indicator, start flow gym → mode → packing list, set screen with target range and tap-to-edit editor, effort picker after each set (5 s, preselection marked as automatic), clean rest screen with "Als Nächstes" preview, machine setup and one-tap weight suggestion, exercise-finished and device-setup screens, notes with quick chips and voice/keyboard, ambient mode after the guidelines, weight-increase scene and summary; new app icon matching the phone
- Earlier progression (phone, watch and server): one strong set with entered reserve proves a higher working weight (e.g. 180 kg × 10 after always 170 kg), in-workout next-step suggestion, start one step lower after two failed sessions in a row; new optional set field `effort_source` (sync protocol amendment H)
- Heart rate on the watch starts automatically after one-time consent, with a clear status and the real error reason
- Temporary navigation recorder on phone and watch (off by default, only in this version) to analyse real navigation; export as ZIP via the share sheet

### Fixed

- Watch: views no longer jump on their own; navigation changes only on real events, typed values survive remote updates
- Next exercise after a jump is the first open exercise, identical on phone and watch
- "Aktion nicht möglich" removed: no-op actions are ignored, real rejections name the reason for 3 s

### Validation

- CI: core, app, wear-backend and watch tests, lint and assemble green; on-device checks of spec section P still open

## [android-v2.7.0] - 2026-11-17

Native Android app release (GitHub release `android-v2.7.0`) with phone APK
`gymapp-2.7.0.apk` and watch APK `gymapp-watch-2.7.0.apk`.

### Added

- Watch: vertically scrolling start screen with settings, progress (frequency, working weights, comparable bests per gym) and history; horizontal gestures to info and controls during a workout; round-safe rest screen; own launcher icon
- Watch: built-in updater that finds the matching watch APK in GitHub releases, verifies size, SHA-256, package, version and signature and offers the install (the first version with the updater has to be installed manually once)
- Phone: after a one-time Health Connect approval, finished personal workouts are exported automatically, including workouts without pulse and pulse data that arrives later; the Test profile stays local

### Changed

- Phone/watch sync: pending events are kept and retried until applied, publications are no longer cancelled by new database changes, and updated devices check the event order; a complete session index removes deleted finished workouts from the watch history

### Validation

- Full Gradle check (core, app, wear-backend, watch) with 310 unit tests; phone/watch sync, gestures and Health Connect still need testing on real hardware

## [android-v2.6.0] - 2026-11-17

Native Android app release (GitHub release `android-v2.6.0`). The release now
also contains `gymapp-watch-2.6.0.apk` for the watch; phone and watch APKs are
signed with the same key, which Wear sync requires.

### Added

- Native Wear OS app for Galaxy Watch: prepare and run workouts on the watch (rotary/touch set editing, rest and setup timers, measured timed holds, corrections and undo, weight-increase celebrations, ambient mode, start/resume complication)
- Phone and watch sync workouts through the Wear Data Layer: stable session/set/event IDs prevent duplicate sets, concurrent edits show up as explicit conflicts on the phone, and the phone recalculates progression on finish, undo and refinish
- Heart-rate recording on the watch; the phone shows the pulse curve in history and summary, and exports to Health Connect only after explicit consent (Test-profile data stays local)

### Changed

- Timed sets on the phone use the measured stopwatch time of the matching set
- The in-app updater ignores the watch APK in a release

### Validation

- New unit tests in `android/core`, `android/app`, `android/wear-backend` and `android/watch`; CI builds, tests and lints all three Android modules
- Not yet tested on a paired phone and watch

## [android-v2.5.1] - 2026-11-16

Native Android app release (GitHub release `android-v2.5.1`).

### Changed

- Native Android app build: Gradle 9.8.2, Android Gradle plugin 9.5.1 (built-in Kotlin, `org.jetbrains.kotlin.android` plugin removed), Kotlin 2.4.21, KSP 2.3.14, WorkManager 2.12.0 (replaces Dependabot PRs #9, #12, #14)

## [android-v2.5.0] - 2026-11-12

Native Android app release (GitHub release `android-v2.5.0`). Earlier Android
releases (up to `android-v2.4.2`) are listed on the GitHub releases page only.

### Added

- Native Android app: add an extra set to the running exercise yourself ("Satz hinzufügen" below the set list, "Noch ein Satz" on the rest screen); the set is logged, undone and synced like any other set
- Native Android app: "Wie lange noch?" on the rest screen estimates the time left from the logged set times of earlier workouts (per exercise, same gym preferred), falls back to the plan formula without history, scales with today's pace, and gets better with every recorded workout
- Native Android app: a short animation per exercise whose working weight went up ("Beinpresse 60 → 65 kg") before the summary, based on the milestone screen of the web version; tap to continue, "Überspringen", still pictures when system animations are off

### Validation

- 26 new Kotlin unit tests (`AddSetTest`, `TimeLeftTest`, `WeightCelebrationTest`) in `android/core`

## [0.2.0-beta.2] - 2026-08-28

### Added

- Storage protection status in Settings with a user-triggered persistent-storage request
- A prominent warning when Personal data is stored in best-effort browser storage
- A non-destructive startup error screen when IndexedDB cannot be opened

### Changed

- Personal backup reminders now begin as soon as any Personal data exists without a backup
- Android installation and beta-testing guidance now include verification of persistent storage

### Fixed

- Persistent storage is requested from an explicit user action and its granted or denied state is no longer ignored
- IndexedDB open failures no longer render normal empty-data screens that can be mistaken for deleted entries

### Validation

- 21 Vitest unit tests
- 14 Playwright Android and desktop flows
- Pixel-width visual verification of the storage protection states
- Offline installed-PWA cold-launch coverage
- ESLint and production TypeScript/Vite build

## [0.2.0-beta.1] - 2026-07-28

### Added

- Read-only gym packlist with headphones, towel, gym clothes, shoes, water
  bottle, and lock
- Exercise-specific workout notes that remain attached to the dated exercise
  entry in History
- Separate machine-change and setup timing when moving between exercises
- Opt-in Android status notification for active set, rest, and machine-setup
  phases
- Concrete evidence text for weight suggestions and saved-weight progression
  status
- Regression coverage for decimal-comma weight ranges, effort bands, exercise
  notes, background timers, machine setup, notifications, and offline launch

### Changed

- Replaced the previous effort choices with At limit, 1–2 left, 3–4 left, and
  5+ / easy while preserving existing legacy values
- Weight suggestions now round to the nearest configured gym weight, with ties
  choosing the lower value
- Active timers derive elapsed time from stored timestamps and catch up after
  leaving or reopening the app
- Historical CSV RIR values map to the new effort bands
- Legacy workout-level notes remain readable and editable, but new notes are
  stored per exercise
- PWA generation now uses a custom service worker for reliable prompt-based
  updates, offline navigation, and notification click handling

### Fixed

- Clearing and replacing a machine weight-range value no longer prepends a
  zero, including when Android supplies a decimal comma
- Backup sharing now falls back cleanly when the platform share sheet cannot
  accept the generated file
- Exercise guidance and several Android/desktop responsive layouts were
  corrected
- Starting a newly created plan now waits for its exercise definitions to load
  instead of briefly recording an unknown exercise
- Machine profile prompts no longer interrupt an active or pending set
- Pinned the affected transitive brace-expansion build dependency to its
  security-fixed release

### Validation

- 18 Vitest unit tests
- 12 Playwright Android and desktop flows
- Offline installed-PWA cold-launch coverage
- ESLint and production TypeScript/Vite build
- npm audit with zero vulnerabilities

## [0.1.0-beta.1] - 2026-07-24

### Added

- Installable offline-first Android PWA
- Personal and isolated Test profiles
- Gym selection for Basic Fit, John Reed, AI Fitness Lahnstein, and AI Fitness
  Koblenz
- User-owned exercise definitions with explicit measurement and weight basis
- Exercise CSV import with validation, preview, and conflict handling
- Workout-plan and historical-workout CSV imports
- Gym-specific machine increments and freely named setup parameters
- Start/finish set timing and actual rest-duration logging
- Weight suggestions based on completed workout history
- Workout-specific notes stored with dated sessions
- Backup and replace-only restore

### Changed

- Removed the browsable built-in exercise database from the product workflow
- Replaced weight dropdowns with direct numeric input
- Made suggested set weight visible before and during a set
- Removed permanent plan and plan-exercise notes

### Validation

- 15 Vitest unit tests
- 8 Playwright Android and desktop flows
- Offline installed-PWA cold-launch coverage
- ESLint and production TypeScript/Vite build
