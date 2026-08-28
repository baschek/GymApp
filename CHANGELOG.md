# Changelog

All notable GymApp changes are documented here.

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
