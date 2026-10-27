#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Slavi Pantaleev
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Keep the bridge fork in sync with upstream (FossifyOrg/Messages).
#
# The script only touches the local repository: it fetches upstream, reports
# how far the current branch has drifted, and can rebase the bridge work on
# top of upstream/main. It never pushes.
#
# Usage:
#   scripts/sync-upstream.sh                     # --check: fetch + report only
#   scripts/sync-upstream.sh --apply             # rebase onto upstream/main, then verify
#   scripts/sync-upstream.sh --apply --no-verify # rebase only
#   scripts/sync-upstream.sh --help
#
# Environment overrides:
#   JAVA_HOME  JDK used for verification (default: ~/tools/jdk-21)
set -euo pipefail

REMOTE="upstream"
UPSTREAM_BRANCH="main"
BRANCH=""
MODE="check"
ALLOW_DIRTY=0
RUN_VERIFY=1
VERIFY_JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21}"
VERIFY_TASKS=(:app:compileFossDebugKotlin :app:testFossDebugUnitTest :app:detekt)

die() {
	echo "error: $*" >&2
	exit 1
}

usage() {
	cat <<'EOF'
Sync the bridge fork with upstream (FossifyOrg/Messages).

Usage: scripts/sync-upstream.sh [--check | --apply] [options]

Modes:
  --check            Fetch upstream and report behind/ahead counts and tips.
                     Tolerates a dirty working tree. This is the default.
  --apply            Rebase the branch onto upstream/main and verify the result.
                     Requires a clean working tree unless --allow-dirty.

Options:
  --remote <name>    Remote to fetch and rebase from (default: upstream).
  --branch <name>    Local branch to rebase (default: the current branch).
  --allow-dirty      Skip the clean-tree preflight for --apply; git itself may
                     still refuse to rebase modified tracked files.
  --no-verify        Skip the Gradle verification after a successful rebase.
  --help, -h         Show this help.

The script never pushes: every suggested push in its output is a manual step.
EOF
}

while [ $# -gt 0 ]; do
	case "$1" in
	--check)
		MODE="check"
		;;
	--apply)
		MODE="apply"
		;;
	--remote)
		[ $# -ge 2 ] || die "--remote requires a value"
		REMOTE="$2"
		shift
		;;
	--branch)
		[ $# -ge 2 ] || die "--branch requires a value"
		BRANCH="$2"
		shift
		;;
	--allow-dirty)
		ALLOW_DIRTY=1
		;;
	--no-verify)
		RUN_VERIFY=0
		;;
	--help | -h)
		usage
		exit 0
		;;
	*)
		die "unknown argument: $1 (see --help)"
		;;
	esac
	shift
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
git rev-parse --git-dir >/dev/null 2>&1 || die "not a git repository: $REPO_ROOT"

REMOTE_URL="$(git remote get-url "$REMOTE" 2>/dev/null || true)"
[ -n "$REMOTE_URL" ] || die "unknown remote '$REMOTE' in $REPO_ROOT"

echo "==> repository: $REPO_ROOT (this script never pushes)"
echo "==> fetching $REMOTE ($REMOTE_URL)"
git fetch --prune "$REMOTE" || die "git fetch $REMOTE failed (network or authentication problem)"

UPSTREAM_REF="$REMOTE/$UPSTREAM_BRANCH"
git rev-parse --verify --quiet "$UPSTREAM_REF^{commit}" >/dev/null || die "no $UPSTREAM_REF after fetch"

if [ -z "$BRANCH" ]; then
	BRANCH="$(git symbolic-ref --quiet --short HEAD)" || die "HEAD is detached; pass --branch <name>"
fi
git rev-parse --verify --quiet "refs/heads/$BRANCH" >/dev/null || die "no local branch '$BRANCH'"

if ! COUNTS="$(git rev-list --left-right --count "$UPSTREAM_REF...$BRANCH" 2>&1)"; then
	die "could not compare $BRANCH with $UPSTREAM_REF: $COUNTS"
fi
read -r BEHIND AHEAD <<<"$COUNTS"

echo "==> local branch       : $BRANCH ($(git log -1 --format='%h %s' "$BRANCH"))"
echo "==> upstream branch    : $UPSTREAM_REF ($(git log -1 --format='%h %s' "$UPSTREAM_REF"))"
echo "==> behind $UPSTREAM_REF : $BEHIND commit(s)"
echo "==> ahead of $UPSTREAM_REF: $AHEAD commit(s)"
if [ "$BEHIND" -gt 0 ]; then
	echo "    next upstream commits:"
	git log --oneline --no-decorate -n 5 "$BRANCH..$UPSTREAM_REF" | sed 's/^/      /'
	[ "$BEHIND" -le 5 ] || echo "      ... and $((BEHIND - 5)) more"
fi

DIRTY=0
if [ -n "$(git status --porcelain)" ]; then
	DIRTY=1
fi

if [ "$MODE" = "check" ]; then
	if [ "$DIRTY" -eq 1 ]; then
		echo "==> note: working tree has local changes; --check ignores them, --apply needs a clean tree (or --allow-dirty)"
	fi
	if [ "$BEHIND" -eq 0 ]; then
		echo "==> nothing to sync: $BRANCH already contains all of $UPSTREAM_REF"
	else
		echo "==> run 'scripts/sync-upstream.sh --apply' to rebase $BRANCH onto $UPSTREAM_REF"
	fi
	exit 0
fi

# --apply below.
if [ "$BEHIND" -eq 0 ]; then
	echo "==> nothing to do: $BRANCH already contains all of $UPSTREAM_REF"
	echo "==> push stays manual: this script never pushes"
	exit 0
fi

if [ "$ALLOW_DIRTY" -eq 0 ] && [ "$DIRTY" -eq 1 ]; then
	die "working tree has uncommitted changes; commit or stash them, or pass --allow-dirty"
fi

CURRENT_BRANCH="$(git symbolic-ref --quiet --short HEAD || true)"
if [ "$CURRENT_BRANCH" != "$BRANCH" ]; then
	echo "==> checking out $BRANCH"
	git checkout "$BRANCH" || die "could not check out $BRANCH"
fi

TAG="bridge-pre-sync-$(date -u +%Y%m%dT%H%M%SZ)"
git rev-parse --verify --quiet "refs/tags/$TAG" >/dev/null && die "tag $TAG already exists; retry in a second"
BEFORE="$(git rev-parse HEAD)"
git tag "$TAG" "$BEFORE"
echo "==> backup tag created: $TAG ($BEFORE)"

echo "==> rebasing $BRANCH onto $UPSTREAM_REF"
if ! git rebase "$UPSTREAM_REF"; then
	if [ -d "$(git rev-parse --git-path rebase-merge)" ] || [ -d "$(git rev-parse --git-path rebase-apply)" ]; then
		CONFLICTED="$(git diff --name-only --diff-filter=U || true)"
		echo "error: rebase stopped on conflicts; conflicted files:" >&2
		if [ -n "$CONFLICTED" ]; then
			printf '%s\n' "$CONFLICTED" | sed 's/^/  /' >&2
		else
			echo "  (no unmerged paths; see git output above)" >&2
		fi
		git rebase --abort
		if [ "$(git rev-parse HEAD)" = "$BEFORE" ]; then
			echo "==> rebase aborted; $BRANCH restored to $BEFORE" >&2
		else
			echo "warning: HEAD is not back at $BEFORE; restore with: git reset --hard $TAG" >&2
		fi
		cat >&2 <<EOF

Manual recovery (backup tag $TAG holds the pre-rebase state):
  1. start the rebase by hand:    git rebase $UPSTREAM_REF
  2. resolve each conflict, then: git add <file> && git rebase --continue
     inspect at any time with:    git status
     give up with:                git rebase --abort
  3. verify the result like this script would:
     JAVA_HOME=$VERIFY_JAVA_HOME ./gradlew ${VERIFY_TASKS[*]}
  4. restore the pre-rebase state if wanted: git reset --hard $TAG

Nothing was pushed. This script never pushes.
EOF
		exit 1
	else
		echo "error: rebase did not start (see git output above); commit or stash tracked changes first" >&2
		echo "note: backup tag $TAG still points at the unchanged pre-rebase state; delete it with: git tag -d $TAG" >&2
		exit 1
	fi
fi

echo "==> rebase complete: $BRANCH is now at $(git log -1 --format='%h %s')"

if [ "$RUN_VERIFY" -eq 1 ]; then
	[ -x ./gradlew ] || die "gradle wrapper ./gradlew not found or not executable"
	VERIFY_LINE="JAVA_HOME=$VERIFY_JAVA_HOME ./gradlew ${VERIFY_TASKS[*]}"
	echo "==> verification: $VERIFY_LINE"
	if ! JAVA_HOME="$VERIFY_JAVA_HOME" ./gradlew "${VERIFY_TASKS[@]}"; then
		cat >&2 <<EOF

error: verification failed after the rebase.

The rebase succeeded, but the code does not pass:
  $VERIFY_LINE

Fix the fallout, or reset back to the pre-rebase state with:
  git reset --hard $TAG

Nothing was pushed. This script never pushes.
EOF
		exit 1
	fi
	echo "==> verification passed: $VERIFY_LINE"
else
	echo "==> verification skipped (--no-verify)"
fi

cat <<EOF

==> done. Suggested next steps (this script never pushes):
  review: git log --oneline --graph -n 30 $TAG..HEAD
  push  : git push --force-with-lease origin $BRANCH   # only after reviewing; manual step
  clean : git tag -d $TAG                              # once the result is pushed and known good
EOF
