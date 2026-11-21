#!/bin/bash

# Exit immediately if any command fails
set -e

# Verify gh CLI is installed and authenticated
if ! command -v gh &> /dev/null; then
  echo "Error: GitHub CLI (gh) is not installed."
  exit 1
fi

if ! gh auth status &> /dev/null; then
  echo "Error: Not logged into GitHub CLI. Run 'gh auth login' first."
  exit 1
fi

# Check if there are any changes to commit
if [ -z "$(git status --porcelain)" ]; then
  echo "No changes to commit."
  exit 0
fi

# Use the first argument as the commit message, or default to a timestamp
COMMIT_MSG="${1:-Update $(date +'%Y-%m-%d %H:%M:%S')}"
CURRENT_BRANCH=$(git branch --show-current)

# Stage, commit, and push
git add .
git commit -m "$COMMIT_MSG"
git push origin "$CURRENT_BRANCH"

echo "Successfully pushed to '$CURRENT_BRANCH'!"
