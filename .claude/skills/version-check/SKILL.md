---
name: version-check
description: "Analyzes git commits since the last version tag and recommends the correct semver bump (major/minor/patch). Use this skill whenever the user asks about versioning, release readiness, what version to bump to, whether to release, or says things like 'version check', 'should I release', 'what changed since last release', 'bump version', or 'check version'. Also trigger when the user finishes merging feature branches and wonders if it's time for a new release."
---

# Version Check

Analyze the commit history since the last release tag and recommend the appropriate semantic version bump.

## How it works

1. Find the latest version tag (format: `v*.*.*`)
2. List all commits since that tag
3. Classify each commit using conventional commit prefixes
4. Determine the correct semver bump
5. Show a clear summary and suggest updating `gradle.properties`

## Step-by-step

### 1. Find the latest tag

```bash
git tag --sort=-version:refname | head -20
```

Pick the highest semver tag. If no tags exist, treat all commits as new and suggest an initial version.

### 2. List commits since tag

```bash
git log <tag>..HEAD --oneline --no-merges
```

If on `development` branch, this shows what would go into the next release. If on `main`, it shows what's been released since the last tag.

### 3. Classify commits

Use conventional commit prefixes to classify:

| Prefix | Type | Bump |
|--------|------|------|
| `feat:` / `feat(scope):` | Feature | minor |
| `fix:` / `fix(scope):` | Bug fix | patch |
| `BREAKING CHANGE:` in body, or `!` after type (e.g. `feat!:`) | Breaking | major |
| Commits that drop support (e.g. "drop Java 8 support") | Breaking | major |
| `chore:`, `docs:`, `style:`, `refactor:`, `test:`, `ci:`, `build:` | Maintenance | patch (if any behavioral change) or none |
| `perf:` | Performance | patch |

Key rules for classification:
- A commit that removes support for something users rely on (runtime versions, APIs, platforms) is **breaking** even without the `!` suffix — look at the commit message semantics, not just the prefix
- `feat` means new user-facing functionality, not just code reorganization
- When in doubt about whether something is breaking, flag it and let the user decide

### 4. Determine the bump

Apply the highest-impact rule:
- Any **breaking** commit → **major** bump
- Any **feature** commit (no breaking) → **minor** bump  
- Only **fixes/chores** → **patch** bump

Pre-1.0 exception: While the project is at `0.x.y`, breaking changes bump **minor** instead of major (semver convention for pre-stable software). Features bump **patch**. Mention this rule when it applies so the user understands.

### 5. Check current version

Read `gradle.properties` and find the `nimbusVersion=x.y.z` line. Compare with the suggested bump.

### 6. Present the summary

Use this format:

```
## Version Check

**Current version:** v0.1.2 (from gradle.properties)
**Last tag:** v0.1.2
**Commits since tag:** 6

### Changes
- **BREAKING** drop Java 8/11 support: minimum runtime is now Java 16
- **feat** add version command + consistent console formatting  
- **feat** versioned JARs + smart start scripts + auto-restart after update
- **fix** compile plugins to Java 16 bytecode + fix logs command encoding crash
- **fix** screen prompt overlap + remove search separator lines
- **fix** JDK auto-download with multi-provider fallback

### Recommendation
**Bump to: v0.2.0** (minor — breaking changes in pre-1.0 project)

Reason: Contains breaking change (Java 16 minimum) + 2 new features.
Pre-1.0 convention: breaking changes bump minor, not major.

To apply:
  Edit gradle.properties: nimbusVersion=0.2.0
  Then tag: git tag v0.2.0
```

### 7. Offer to apply

Ask the user if they want you to:
- Update `gradle.properties` with the new version
- Optionally create and push the tag

Never apply changes without asking first — version bumps are release decisions.

## Edge cases

- **No tags found**: Suggest `v0.1.0` as the initial release, or let the user pick
- **No commits since tag**: Report "Already up to date, nothing to release"
- **Mixed branches**: If commits exist on `development` but not `main`, note that these changes haven't been released yet and suggest merging to `main` first
- **Pre-release tags** (e.g. `v0.2.0-beta.1`): Handle as lower than stable (`v0.2.0-beta.1 < v0.2.0`)
