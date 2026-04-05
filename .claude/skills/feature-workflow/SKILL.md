---
name: feature-workflow
description: "Manages the git feature branch workflow: creates feature/fix branches from development, makes PRs back to development, and suggests version bumps after merges. Use this skill when the user says things like 'start feature', 'new branch', 'create branch for', 'start working on', 'finish feature', 'create PR', 'merge to development', 'ready to release', or any request to begin or wrap up work on a feature or bugfix. Also use when the user asks about the branching strategy or wants to know what branches exist."
---

# Feature Workflow

Manage the feature branch lifecycle: create branches, make PRs to `development`, and suggest version bumps when work is merged.

## Branching model

```
main (production releases)
 └── development (integration branch)
      ├── feat/versioned-jars
      ├── fix/console-encoding
      └── feat/scaling-improvements
```

- **Feature branches** are created from and merged back into `development`
- **`development` → `main`** merges happen only for releases
- **Direct commits to `development` or `main`** should be avoided for non-trivial changes

## Commands

The user can invoke this skill at different stages of work. Detect the intent from context:

### Starting work: "start feature", "new branch", "start working on X"

1. Make sure we're on `development` and it's up to date:
   ```bash
   git checkout development
   git pull origin development
   ```

2. Ask the user what they're working on if not clear from context.

3. Derive a branch name using conventional naming:
   - `feat/<short-description>` for new features
   - `fix/<short-description>` for bug fixes
   - `refactor/<short-description>` for refactoring
   - `chore/<short-description>` for maintenance
   
   Use lowercase, hyphens for spaces, keep it short (2-4 words). Examples:
   - "versioned jar support" → `feat/versioned-jars`
   - "fix console encoding crash" → `fix/console-encoding`
   - "refactor update checker" → `refactor/update-checker`

4. Suggest the branch name and ask for confirmation before creating it.

5. Create and switch to the branch:
   ```bash
   git checkout -b <branch-name>
   ```

6. Confirm: "You're on `<branch-name>`. Ready to work!"

### Finishing work: "finish feature", "create PR", "done with this", "PR to development"

1. Check the current branch and its state:
   ```bash
   git status
   git branch --show-current
   git log development..HEAD --oneline
   ```

2. If there are uncommitted changes, ask if the user wants to commit first.

3. Push the branch:
   ```bash
   git push -u origin <current-branch>
   ```

4. Create a PR to `development` using `gh pr create`:
   - Title: derive from the branch name and commits (e.g. "feat: versioned JAR support + auto-restart after update")
   - Body: summarize the commits, include a test plan section
   - Base branch: `development`
   
   ```bash
   gh pr create --base development --title "<title>" --body "<body>"
   ```

5. Show the PR URL to the user.

6. After the PR is created, suggest: "After this is merged, run `/version-check` to see if a version bump is warranted."

### Checking status: "what branches", "branch status", "workflow status"

Show an overview:
```bash
git branch -a --sort=-committerdate | head -20
git log development..HEAD --oneline 2>/dev/null
```

Report:
- Current branch and its state relative to `development`
- Open PRs (via `gh pr list --base development`)
- Whether `development` is ahead of `main` (unreleased changes)

### Preparing a release: "release", "merge to main", "ready to release"

1. Suggest running `/version-check` first to determine the version
2. After version is confirmed, outline the release steps:
   - Ensure `development` is clean and tested
   - Update `gradle.properties` with new version on `development`
   - Create PR from `development` → `main`
   - After merge, tag `main` with the version
   - The GitHub Actions release workflow handles the rest

Don't execute release steps without explicit confirmation — releases are high-impact.

## Important behaviors

- **Never force-push** or use destructive git operations without explicit user consent
- **Always confirm** branch names and PR titles before creating them
- **Stay on the user's branch** — don't switch branches unexpectedly
- **Check for stale branches** — if `development` has moved ahead, suggest rebasing before creating a PR
- If `gh` CLI is not available, fall back to providing the GitHub URL for manual PR creation
- When creating PRs, check if a PR already exists for the current branch to avoid duplicates
