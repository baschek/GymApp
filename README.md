# GymApp

GymApp is an offline-first workout planner and training log. It installs as a Progressive Web App on Android, stores all personal data locally, imports professional workout plans from a documented CSV format, and provides transparent weight suggestions from completed sets and effort ratings.

## Current Features

- Installable Android PWA with offline application data
- A personal exercise list in which every exercise, measurement type, equipment name, instructions, and optional photo is defined by the user
- Personal and isolated Test profiles
- Current-gym selection for Basic Fit, John Reed, AI Fitness Lahnstein, and AI Fitness Koblenz
- Reusable workout plans with Normal, Fast, and Ultra Fast prescriptions
- CSV plan import with validation, mapping preview, and conflict handling
- Gym-specific machine profiles with configurable weight ranges and freely named setup parameters
- Start/stop set timing, count-up rest timing, and actual rest-duration logging
- Workout-specific notes stored only with the dated session
- Exercise information modals with offline illustrations and concise instructions
- Focused active-workout screen with variable weights per set, RIR/effort, temporary edits, and supersets
- Two-set evidence rule for automatic saved-weight progression
- Dated editable history, exercise charts, activity summaries, and optional body-weight log
- Historical workout CSV import with exercise mapping and duplicate detection
- Personal exercise CSV import with validation, preview, and explicit conflict handling
- Versioned full backup and replace-only restore through Android sharing or desktop download

## Android Installation

Production is deployed from `main` to GitHub Pages.

1. Open the GitHub Pages URL in current Android Chrome.
2. Open Chrome's menu and choose **Install app** or **Add to Home screen**.
3. Launch GymApp from the Android app list.
4. Open it once while online so the service worker can cache the application.

Workout data is not uploaded to GitHub Pages. It stays in the installed browser profile. Create periodic backups in **Settings > Backup and restore**.

## PC Development

Requirements: Node.js 24 and npm.

```powershell
npm.cmd install
npm.cmd run dev
```

Vite prints the local URL, normally `http://localhost:5173/GymApp/`. Use responsive browser tools for phone layouts and switch to the isolated Test profile under Settings for development data.

Useful commands:

```powershell
npm.cmd run lint
npm.cmd test
npm.cmd run test:e2e
npm.cmd run build
npm.cmd run preview
```

PowerShell on this machine blocks `npm.ps1`, so examples use `npm.cmd`. Ordinary shells can use `npm`.

The current prerelease is `0.1.0-beta.1`.

- [Beta testing guide](docs/BETA_TESTING.md)
- [Release process](docs/RELEASE_PROCESS.md)
- [Changelog](CHANGELOG.md)

## Workout Plan CSV

One CSV file imports one saved workout. It contains exact Normal, Fast, and Ultra Fast set counts, target ranges, optional starting loads, RIR targets, rest durations, and supersets.

- [CSV standard](docs/WORKOUT_PLAN_STANDARD.md)
- [Example full-body plan](examples/full-body.csv)

The in-app Workouts screen also provides a downloadable template.
Exercise IDs are optional. During import, each row is matched against **My exercises** by ID or name. Unmatched rows must be mapped to an existing definition or defined manually before the plan is saved.

## Exercise CSV

The Exercises screen imports personal exercise definitions into the active
profile in bulk. Measurement type and weight basis remain explicit, so the
importer never guesses whether a machine uses normal weight or assistance.

- [Exercise import standard](docs/EXERCISE_IMPORT_STANDARD.md)

## Historical Workout CSV

The History screen imports previously recorded workouts from a one-row-per-set CSV. A single file may contain multiple dated sessions and can preserve gym, start time, weights, repetitions, RIR, actual rest, set duration, and notes.

- [AI and human transcription standard](docs/HISTORICAL_WORKOUT_TRANSCRIPTION.md)
- [Historical workout example](examples/historical-workouts.csv)

Unknown exercises are resolved in an import preview. Historical sessions appear in History and Progress but do not rewrite permanent plan weights.

## Data And Recommendations

GymApp stores data locally in the browser with schema migrations. Actual set values remain blank until entered or a visible suggestion is accepted. Completed working sets require an effort rating.

Rep-based suggestions use a conservative estimated-performance calculation and configured equipment values. Assistance, timed, and distance exercises use direction-aware step rules. A saved plan advances only when two qualifying working sets prove the new value. Failed or pain-marked sets never increase it, and reductions are suggested rather than applied automatically.

Recommendations are training heuristics, not medical advice. Stop an exercise when pain or unusual discomfort occurs.

## Exercise Ownership

There is no browsable built-in exercise library. Each profile owns its exercise definitions, so assisted machines, bodyweight movements, and weighted exercises are never classified through name guessing.

When upgrading from an older GymApp version, only built-in exercises already referenced by saved plans, machine settings, milestones, or workout history are copied once into **My exercises**. Those copies are editable and become normal user-owned definitions. The legacy source files remain packaged temporarily so this upgrade can preserve existing local data.
