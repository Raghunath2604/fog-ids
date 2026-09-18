# Git workflow

## Branch model

- `main` is protected: no direct pushes, merge only via reviewed pull request.
- Each track works on its own long-lived feature branch and opens PRs from
  short-lived branches off it, or straight off `main` for small changes —
  either is fine as long as the PR target is `main` and review happens.

| Branch | Owner | Scope |
|---|---|---|
| `feature/ml-model-training` | Member A | everything under `ml/` |
| `feature/ifogsim-topology` | Member B | `simulation/` topology and application graph |
| `feature/ids-integration` | Member B | `simulation/` metrics, run harness, scenario wiring |
| `feature/security-control-plane` | Member C | `security-control/` fast path, monitor, quarantine |
| `feature/attack-injection` | Member C | `security-control/` attack modules |

Branch names are directories, not people — if a track's work naturally
splits into two reviewable chunks (as Member B's did: topology first, then
metrics/scenarios on top of it), open two PRs rather than one large one.
Small, single-purpose PRs review faster and bisect cleanly if something
breaks later.

## Why merges stay clean across three people

Each track owns one top-level folder (`ml/`, `simulation/`, `security-control/`)
and touches `docs/` only to add its own interface documentation. Two people
editing the same file at the same time should be rare by construction. The
one shared file to watch is `docs/interfaces.md` — when your track finishes
something another track depends on, that edit goes in the same PR as the
code, so the interface is documented at the moment it becomes real instead
of drifting out of sync.

## Commit messages

`<area>: <what changed>`, imperative mood, no trailing period:

```
simulation: add three-fog-node corridor topology
simulation: fix verdict-logging cost model (see results/NOTES.md)
ml: freeze feature spec for all three tiers
security-control: implement quarantine state machine with dwell time
```

## Pull requests

- Every PR needs review from **someone outside that track** — with three
  people this is a real second pair of eyes, not a formality. Member A
  reviews Member B's PRs or Member C's; never review your own track.
- PR description states: what changed, what it unblocks for the other two
  tracks (if anything), and what's still stubbed. Copy the relevant stub
  callouts straight from code comments — don't make the reviewer hunt for
  them.
- Squash or regular merge, either is fine; **always use a merge commit for
  cross-track integration** (`--no-ff`) so `git log --graph` on `main`
  shows where each track's work actually joined, which matters when
  something breaks and you need to know which PR introduced it.
- CI (see `.github/workflows/build.yml`) must pass before merge: it builds
  `simulation/` and runs `run_all.sh` at a short simulated time, so a PR
  that breaks the build or crashes a scenario never reaches `main`.

## Resolving the one interface that isn't nailed down yet

`docs/interfaces.md` section 4 (proxy rerouting) is flagged as an open
design question, not a frozen contract. Whoever gets there first (likely
Member C, once quarantine logic needs it) proposes an approach in the PR
description and tags the other two for a quick discussion before merging —
this is the one place a design decision in one track's PR genuinely
constrains another track's future work, so it gets a deliberate check
rather than a silent merge.

## Setting up the remote

```
git remote add origin <your-github-repo-url>
git push -u origin main
git push -u origin feature/ifogsim-topology
git push -u origin feature/ids-integration
```

Then on GitHub: Settings → Branches → add a protection rule on `main`
requiring at least one review and passing status checks before merge.
Invite Members A and C as collaborators; each pushes their own feature
branches and opens PRs against `main` the same way.
