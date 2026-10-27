<!--
SPDX-FileCopyrightText: 2026 Slavi Pantaleev

SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Upstream sync

The bridge fork carries local work on top of FossifyOrg/Messages (see
[Bridge mode](bridge-mode.md)). `scripts/sync-upstream.sh` keeps that work rebased onto
`upstream/main`: it fetches upstream, reports how far the branch has drifted, and can rebase
and run the local verification suite.

The script is local tooling. **It never pushes.** Every push in its output is a manual
command that a human runs after reviewing the result. There is intentionally no CI job for
this: syncing happens when a developer asks for it, not on a schedule.

## Usage

```sh
# Check: fetch upstream and report behind/ahead counts and tips (default mode).
# Safe with a dirty working tree; changes nothing except remote-tracking refs.
scripts/sync-upstream.sh

# Apply: rebase the current branch onto upstream/main, then verify.
# Requires a clean working tree (commit or stash first).
scripts/sync-upstream.sh --apply

# Same, without the Gradle verification.
scripts/sync-upstream.sh --apply --no-verify
```

Flags:

| Flag              | Meaning                                                                                          |
| ----------------- | ------------------------------------------------------------------------------------------------ |
| `--check`         | Fetch and report only (default). Tolerates a dirty tree.                                         |
| `--apply`         | Rebase the branch onto `upstream/main` and verify (unless `--no-verify`).                        |
| `--remote <name>` | Remote to fetch/rebase from (default: `upstream`).                                               |
| `--branch <name>` | Local branch to rebase (default: the current branch; checked out if different).                   |
| `--allow-dirty`   | Skip the clean-tree preflight for `--apply`. Git may still refuse to rebase modified tracked files. |
| `--no-verify`     | Skip the Gradle verification after a successful rebase.                                          |
| `--help`          | Show usage.                                                                                      |

`--check` output looks like:

```text
==> local branch       : bridge (b6e61d61 build: refuse to generate a new signing key automatically)
==> upstream branch    : upstream/main (1dc21451 chore(deps): bump gradle-wrapper from 9.8.1 to 9.8.2 (#936))
==> behind upstream/main : 2 commit(s)
==> ahead of upstream/main: 22 commit(s)
```

## What `--apply` does

1. Refuses to start when the working tree is dirty (any modified, staged or untracked file)
   unless `--allow-dirty` is passed.
2. Creates a backup tag `bridge-pre-sync-<UTC>` (e.g. `bridge-pre-sync-20261026T232000Z`) on
   the pre-rebase tip.
3. Rebases `--branch` (default: the current branch) onto `upstream/main`.
4. Runs verification unless `--no-verify`:

   ```sh
   JAVA_HOME=/home/user/tools/jdk-21 ./gradlew :app:compileFossDebugKotlin :app:testFossDebugUnitTest :app:detekt
   ```

   The script prints that exact command and whether it passed.
5. Prints the suggested next steps; pushing stays manual:

   ```sh
   git log --oneline --graph -n 30 bridge-pre-sync-<UTC>..HEAD   # review
   git push --force-with-lease origin bridge                     # only after reviewing
   git tag -d bridge-pre-sync-<UTC>                              # once pushed and known good
   ```

If the verification fails, the rebase is left in place and the failure output tells you to
fix the fallout or `git reset --hard` back to the backup tag.

## Conflict recovery

On a conflict the script captures the conflicted file list, runs `git rebase --abort` (the
branch returns to its pre-rebase tip), prints the list plus instructions, and exits non-zero.
Nothing is pushed. The backup tag from step 2 keeps the same pre-rebase state as extra
safety.

To resolve by hand:

```sh
git rebase upstream/main          # start the rebase yourself
git status                        # see conflicted files
# edit each file, then:
git add <file>
git rebase --continue
# repeat until done, or give up at any point with:
git rebase --abort
```

Restore the pre-rebase state at any time with `git reset --hard bridge-pre-sync-<UTC>`, and
delete the tag with `git tag -d bridge-pre-sync-<UTC>` once it is no longer needed.

## Requirements

- Bash and Git.
- JDK 21 at `~/tools/jdk-21` (`JAVA_HOME=/home/user/tools/jdk-21`) for the verification
  step. The script honours an existing `JAVA_HOME` and defaults to `$HOME/tools/jdk-21`.
- The Gradle wrapper (`./gradlew`); dependencies are downloaded on the first verification
  run.
- Network access to the upstream remote (GitHub). `--check` fails cleanly if the fetch does
  not work.

## When to run it

Manually, whenever desired. Typical moments: before starting new bridge work that could
touch upstream code, after an upstream release, and before cutting a bridge release. Running
`--check` often is cheap and safe; run `--apply` when the drift is real and the tree is
clean. If the local tree carries work in progress, commit or stash it first (untracked files
also count, including this script until it is committed).

Note that `git fetch` also brings upstream release tags (e.g. `1.10.2`) into the local
repository; they are not adapted to the fork's versioning.
