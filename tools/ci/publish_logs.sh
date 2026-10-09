#!/usr/bin/env bash
# Pushes ./logs to a branch so the results can be read without opening the Actions page.
# Usage: publish_logs.sh <branch>     (needs GITHUB_TOKEN, GITHUB_REPOSITORY, GITHUB_SHA)
set -u
BRANCH="$1"
D="$(mktemp -d)"
cp -r logs/. "$D"/ 2>/dev/null || true
echo "$GITHUB_SHA" > "$D/commit.txt"
cd "$D"
git init -q && git config user.name ci && git config user.email ci@users.noreply.github.com
git add -A && git commit -qm "ci logs for $GITHUB_SHA"
git push -qf "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "HEAD:refs/heads/${BRANCH}" || echo "log push failed"
