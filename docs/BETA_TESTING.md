# GymApp Beta Testing

Current beta: `0.1.0-beta.1`

This beta is intended for real workout sessions on an Android phone. It is not
yet a stable `1.0` release. Training data remains local to the browser profile,
so backups are part of the beta procedure.

## Before The First Session

1. Open the deployed GitHub Pages URL in Android Chrome.
2. Install GymApp from Chrome's **Install app** action.
3. Open **Settings** and select the current gym.
4. Define or import the exercises needed for the planned workout.
5. Configure each machine's available increments and setup parameters.
6. Create or import a workout plan.
7. Create a backup after the initial setup.

Data created on `localhost`, GitHub Pages, and the installed Android app belongs
to separate browser origins. Transfer existing PC data with **Settings > Backup
and restore**.

## Real-Workout Checklist

During at least two normal sessions, verify:

- The selected gym and its machine settings are correct.
- Suggested weight is visible before and during every weighted set.
- Assistance exercises treat lower assistance as progress.
- Weight can be entered directly with the phone keyboard.
- Starting and finishing a set records its duration.
- The rest timer starts immediately after **Finish set**.
- Starting the next set records the actual rest duration.
- Repetitions, weight, effort, seconds, and distance appear where expected.
- A workout-specific note can be added during the session.
- Temporary exercise additions do not modify the saved plan.
- Finishing the workout creates a dated History entry.
- The workout note appears only on that dated History entry.
- Closing and reopening the installed app preserves the active session.
- The app opens without a network connection after one complete online load.

## Data Safety

- Create a backup after every few beta sessions.
- Keep the original manually recorded workouts until their imported data has
  been checked.
- Do not clear Chrome site data or uninstall the browser before exporting a
  current backup.
- The isolated Test profile is suitable for destructive experiments. Test
  profile data is not included in Personal backups.

## Reporting A Beta Issue

Include:

- Gym and exercise name
- Android and Chrome version
- Exact action sequence
- Expected and actual result
- Whether the app was online or offline
- Screenshot when the issue is visual
- Whether reloading reproduced the issue

Do not publish a backup file in a public issue because it contains personal
training data.

## Automated Beta Gate

The release workflow currently requires:

- ESLint
- 15 Vitest unit tests
- Production TypeScript and Vite build
- 8 Playwright flows across Android and desktop viewports
- Installed-PWA cold launch while offline

The automated checks do not replace real-device validation of Android keyboard
behavior, storage eviction, camera permissions, or long-running gym sessions.

## Known Beta Limitations

- There is no cloud synchronization or account system.
- Installation is through the PWA, not the Google Play Store.
- Data does not automatically move between PC and phone.
- Legacy exercise assets remain packaged temporarily for one-time migration of
  records created before the personal exercise library.
