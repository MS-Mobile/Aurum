---
name: add-pr
description: Use whenever a pull request is about to be opened for this repo — the user says "Add PR" or "open a PR", asks to finish a branch with a pull request, or the session opens one on its own after pushing a branch (as Claude Code web/remote sessions do by default). Read it before pushing, not after the PR exists.
---

# Add PR routine

When asked to "Add PR" or finish work with a pull request, do these in order:

1. Create a feature branch (if not already on one).
2. Regenerate any committed build artifacts the PR gates check — see below. Without a local
   toolchain this step cannot come first; follow "No local toolchain" below instead.
3. Commit all changed files.
4. Push to remote.
5. Open a PR whose body follows `.github/PULL_REQUEST_TEMPLATE.md` — fill every section
   (Description, Type of Change checkboxes, Checklist). Never submit a plain-prose body. This
   applies even when creating the PR via the GitHub API.

## PR target branch

If the branch was cut from `release/**`, the PR must target that same release branch (e.g.
`release/1.2.0`), not `master`. Master receives the change later via the backmerge workflow. See the
`release-pipeline` skill.

## Gates that fail a PR after it is opened

One PR-build check validates *committed* generated files and fails the PR if they are stale, so
regenerate them **before** step 3.

### No local toolchain — regenerate between the push and the PR

Applies **only** when Gradle cannot run in this session — Claude Code web/remote sessions, where the
egress proxy denies `services.gradle.org` and `./gradlew` dies fetching its distribution. With a working toolchain, regenerate locally at step 2 and skip this entirely. The
regeneration workflows are a substitute for a build you cannot run, not a step every PR needs.

The workflows regenerate against a **pushed** branch, so step 2 cannot come first. Reorder:

1. Push the branch.
2. Look at what the branch actually changed (`git diff --name-only master...HEAD`), then dispatch
   **Regenerate Screenshots** **only if the diff calls for it**: a `@Composable`, `@Preview`, or
   `PreviewParameterProvider` edit (or a drawable/string they render).

   A branch touching none of those — docs, ViewModels, image processing, tests, Gradle config — needs
   no dispatch. Judge it from the diff, not from a habit of always dispatching.
3. Wait for the workflow to commit the regenerated files back to the branch.
4. Open the PR, so its first build runs against the complete branch, and say in the checklist that
   nothing was verified locally.

When the diff does call for it, dispatch on that expectation rather than waiting for
`validateDebugScreenshotTest` to fail — and do not leave the dispatch as a note in the PR body for a
human to action.

### Compose screenshots — `validateDebugScreenshotTest`

Required for any Compose UI / preview change.

- Locally: `:app:updateDebugScreenshotTest` (the user calls this "updateDebugScreenshots").
- Without a local toolchain: dispatch the **Regenerate Screenshots** workflow
  (`.github/workflows/regenerate-screenshots.yml`) for the branch — this is the usual path.
- Reference PNGs live under `app/src/screenshotTestDebug/reference/**`; `git status` shows which changed.
- All camera UI states come from `CameraPreviewConfigProvider`, so one change to the shared
  `readyState` can shift every `CameraScreenScreenshotTest` reference.
- To exercise a new rendering, **add a variant at the end of the `PreviewParameterProvider`** — never
  edit the shared preview state, which would rebaseline every existing variant.

## Image processing changes

The PR build cannot run the instrumented port test: it needs a phone and the personal test photo, which
is not committed. When a PR touches `processing/`, run `sh tools/run-reference-test.sh` on a connected
phone first and put its PSNR line (must stay > 40 dB) in the PR description.

## Non-ASCII PR bodies via curl

The PR template contains emoji. If you POST a PR body with `curl` rather than `gh`, **never pass the
JSON inline** (`-d '{...}'`) — a Windows shell → curl hop mangles UTF-8 into `?`. Write the JSON to a
UTF-8 file and send `-d @file.json`.
