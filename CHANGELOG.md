# Changelog

All notable GymApp changes are documented here.

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
