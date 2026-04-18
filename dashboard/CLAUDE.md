@AGENTS.md

# Nimbus Dashboard — Status: BETA (0.11.0-beta.1)

The dashboard is the browser-based management UI for a Nimbus controller. It is versioned independently of the controller and is now on the beta channel.

## Beta-era conventions

- **PageShell wraps every page.** It owns the header + consistent loading / empty / error states. Do not reintroduce per-page skeletons or bare status branches.
- **`useApiResource` is the canonical data hook.** No bare `useEffect` + `fetch` in pages. Mutations go through `apiFetch`, which auto-surfaces 4xx/5xx as toasts; opt-out via `{ silent: true }` when the caller renders its own error UI.
- **Semantic severity tokens only.** Use CSS vars `--severity-ok`, `--severity-warn`, `--severity-err`, `--severity-info`. Do not reintroduce hardcoded `emerald-*` / `amber-*` / `destructive` tailwind classes in status UI.
- **Standardized polling.** `POLL.fast` (3s) / `POLL.normal` (5s) / `POLL.slow` (30s). Polling pauses while the tab is hidden.
- **Version + channel.** Source of truth is `dashboard/package.json` (`0.11.0-beta.1`). Injected at build time via `next.config.ts`, exposed by `dashboard/src/lib/version.ts`. The sidebar renders a Beta/Alpha badge reflecting the channel.

## Changelog

See `CHANGELOG.md` for release history.
