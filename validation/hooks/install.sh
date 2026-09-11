#!/usr/bin/env bash
# Installs this repo's git hooks into .git/hooks. Safe to re-run.
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
hooks_dir="$(git rev-parse --git-path hooks)"
src="$repo_root/validation/hooks/pre-push"
dest="$hooks_dir/pre-push"

if [ -e "$dest" ] && ! cmp -s "$src" "$dest"; then
  cp "$dest" "$dest.bak"
  echo "install: existing hook backed up to $dest.bak"
fi

cp "$src" "$dest"
chmod +x "$dest"
echo "install: pre-push hook installed at $dest"
