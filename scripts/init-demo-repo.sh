#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO_REPO="$ROOT_DIR/demo-repos/mall-order-service"

if [ ! -d "$DEMO_REPO" ]; then
  echo "Demo repository directory not found: $DEMO_REPO" >&2
  exit 1
fi

cd "$DEMO_REPO"

if [ -d ".git" ] || [ -f ".git" ]; then
  echo "Demo repository already initialized: $DEMO_REPO"
  exit 0
fi

git init -b main
git config user.name "RepoSage Demo"
git config user.email "demo@reposage.local"
git add README.md pom.xml docs src
git commit -m "Prepare mall order service demo repository"

echo "Demo repository initialized: $DEMO_REPO"
