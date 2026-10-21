---
name: release-pipeline
description: Use when cutting a release, running the release, shipping a version, deploying to Play, bumping version codes, or opening a PR against a release/** branch
---

# Releasing

Three manual dispatches — **Cut Release → Release Build → Deploy** — plus a backmerge that runs on its
own. Read the YAML for what each step does; this covers the order, the tool calls that drive it, and
the rules that live in no single file. Dispatch through the GitHub MCP tools (`mcp__github__*`); the
`gh` CLI is not available in remote sessions.

## Preflight

- `versionName` in `version.properties` on master is the version being cut. Everything downstream is
  `release/<that>` and `v<that>+<versionCode>`.
- `list_branches` — if `release/X.Y.Z` already exists, stop; cut-release guards on this and fails.
- `list_releases` — surface any existing `vX.Y.Z+*`. A release already published for this version
  usually means the cut already happened.
- `actions_list` → `list_workflow_runs`, `resource_id: pull-request-build.yml`, branch `master` — cut
  from a green master, not a red one (oversize response; see *Polling runs*).

## 1. Cut

`actions_run_trigger`, `method: run_workflow`, `workflow_id: cut-release.yml`, `ref: master`. Pass
`inputs: { version_name: "X.Y.Z" }` only to override `version.properties`; the input path also commits
the version onto the new branch, so branch and file agree either way.

Poll to completion, then confirm `release/X.Y.Z` exists and find the `bump/version-*` PR the second
job opened against master (`list_pull_requests`). Let PR Build pass, then **squash-merge it** — these
land in the changelog as `Bump version to 1.11.0 (#308)`. If its checks are red, stop and ask; the
release branch is already cut and does not depend on the bump landing.

## 2. Build

`actions_run_trigger`, `method: run_workflow`, `workflow_id: release-build.yml`, `ref: release/X.Y.Z`
— the ref is what selects the release branch, there are no inputs. It computes the version code,
builds the signed AAB/APK, runs `test` and `validateDebugScreenshotTest`, then **tags
`v{versionName}+{versionCode}` and publishes the GitHub Release** with the APK attached. Minutes, not
seconds.

On success, read the Release back and report the tag. On failure, `actions_list` →
`list_workflow_jobs` for the run, then `get_job_logs` with `failed_only: true`,
`return_content: true`, `tail_lines: 100` — diagnose before re-dispatching. The two guards that fail
in the first seconds are branch ↔ `version.properties` mismatch and an already-existing tag/Release.

## 3. Deploy

Aurum is for personal use: the GitHub Release APK from step 2 is usually all that is needed, and Deploy
only works once the app exists in Play Console and `PLAY_STORE_SERVICE_ACCOUNT_JSON` is set (see the
`play-store-ci-setup` skill). Ask before dispatching it.

**Confirm the release notes with the user before dispatching.** Portuguese, default `Ajustes e
melhorias.` — it becomes both the Play release name and `whatsnew-pt-BR`, and this is the one
outward-facing, irreversible step. Keep it to a **single line with no quotes**: the workflow
interpolates it straight into a shell `echo`.

`actions_run_trigger`, `method: run_workflow`, `workflow_id: deploy.yml`, `ref: release/X.Y.Z`,
`inputs: { release_notes: "…" }`. It downloads the artifacts from the latest **successful**
release-build run on that branch, re-checks branch ↔ version and that the built commit is an ancestor
of the branch head, and uploads the AAB to the Play **internal** track. Report the version code that
went live.

## 4. After

Every push to `release/**` fires `backmerge.yml`, which opens or refreshes `backmerge/X.Y.Z` → master
(green if clean, a ⚠️ PR listing conflicts if not). Check it landed. **Merge-commit it** — squashing
loses the release branch's ancestry and the next backmerge re-proposes the same commits.

## Polling runs

`run_workflow` returns no run id, so find it once and then poll it:

1. `actions_list` → `list_workflow_runs`, `resource_id` = the workflow's file name,
   `workflow_runs_filter: { branch, event: workflow_dispatch }`. **This response is far too large for
   the harness and comes back as an oversize error — `per_page` is ignored, so expect that.** The
   error carries the path it saved the JSON to; read the ids out of the file instead of the response:
   ```bash
   python3 -c "import json;d=json.load(open('<saved-path>'));[print(r['id'],r['head_branch'],r['status'],r['conclusion'],r['created_at']) for r in d['workflow_runs'][:5]]"
   ```
   Take the newest run created after the dispatch — match on `head_branch` too, several of these
   workflows run on multiple branches.
2. `actions_get` → `get_workflow_run` with that id until `status: completed`, then read `conclusion`.

Space the checks with the Monitor tool or a backgrounded wait — a foreground `sleep` is blocked.
Never report a dispatch as done without reading its `conclusion`.

## Rules that outlive any one run

- **A PR targeting a release branch must be cut from that release branch**, not master. Cut from
  master, the squash-merge silently carries master's whole diff — version bump included — onto the
  release branch. The branch ↔ `version.properties` guard catches only half of it.
- Push-triggered workflows run from the *pushed branch's* copy of the file, so a release branch
  inherits `backmerge.yml` as it stood at cut time; fixing it on master does not apply retroactively.
- **Version codes** are `VERSION_CODE_OFFSET` (repo Actions variable) `+ run_number*10 +
  run_attempt`. `run_number` is scoped to the workflow's *file path* — renaming `release-build.yml`
  resets it and has already caused a Play rejection. The warning sits at the calculation itself.
- **Build artifacts expire after 30 days.** Deploy has nothing to download a month after the build;
  re-run Release Build instead (which mints a new version code, and so a new tag).
- **The tag is created before Play accepts the upload.** A rejected upload leaves a published Release
  for a build that never shipped — delete the orphan. The existence guard will not stop the re-run,
  because the new version code changes the tag.
