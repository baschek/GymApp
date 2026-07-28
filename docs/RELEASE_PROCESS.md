# GymApp Release Process

GymApp uses GitHub Pages as its production host and Android installation target.
The `dev` branch is the integration branch. Only `main` deploys.

## Version Naming

Use semantic prerelease versions:

- Package version: `0.2.0-beta.1`
- Git tag: `v0.2.0-beta.1`
- GitHub Release title: `GymApp 0.2.0 Beta 1`
- Pull request title: `Release GymApp 0.2.0 Beta 1`

Increment the beta suffix for fixes made without changing the feature set:
`0.2.0-beta.2`, `0.2.0-beta.3`, and so on. Increment the minor version for
another substantial beta feature set. Use `1.0.0` only after the beta checklist
has been completed without a release-blocking issue and the owner explicitly
approves the stable release.

## Release Gate

Run from a clean `dev` checkout:

```powershell
npm.cmd ci
npm.cmd run lint
npm.cmd test
npm.cmd run test:e2e
```

`test:e2e` creates the production build and runs Android and desktop browser
flows against `vite preview`.

## Publishing

1. Update `package.json`, `package-lock.json`, `CHANGELOG.md`, and beta notes.
2. Commit the complete intended change set on `dev`.
3. Push `dev` to `origin`.
4. Wait for the `dev` GitHub Actions verification job.
5. Open a pull request from `dev` to `main`.
6. Review the diff and CI result.
7. Merge the pull request.
8. Wait for the `main` workflow to build and deploy GitHub Pages.
9. Open the deployed PWA and perform a short smoke check.
10. Create the matching Git tag and GitHub prerelease.

The expected production URL is:

```text
https://baschek.github.io/GymApp/
```

GitHub repository settings must use **GitHub Actions** as the Pages source.

## Android Update

The installed PWA checks for a new service worker. When an update is available,
GymApp displays **Reload to update**. Applying the update replaces application
files but keeps IndexedDB training data for the same origin.

Create a Personal backup before applying a beta update.

## Rollback

If a release is unusable:

1. Do not clear browser data.
2. Revert the release commit on `main`.
3. Let GitHub Actions redeploy the previous application code.
4. Restore a backup only when stored data itself is damaged.

Application rollback and data rollback are separate actions.
