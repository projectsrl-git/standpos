# Phase prompts

Each file is a self-contained brief for one development phase, meant to be pasted into Claude Code opened at the repository root.

| Phase | File | Status |
| --- | --- | --- |
| 0 · Foundations | [phase-0.md](phase-0.md) | Done (0.1.0, PR #1) |
| 1 · Register and stock | [phase-1.md](phase-1.md) | Ready |
| 2 · SumUp Solo | [phase-2.md](phase-2.md) | Draft |
| 3 · Tickets and reports | [phase-3.md](phase-3.md) | Draft |
| 4 · SaaS onboarding | [phase-4.md](phase-4.md) | Draft |

## How to run a phase

1. Make sure `main` is up to date and the previous phase's PR is merged.
2. Refine the phase file if it is still a draft: fold in the previous PR's open questions and anything the spec changed. Commit the refined prompt.
3. Open Claude Code at the repository root (`CLAUDE.md` is loaded automatically) and paste the phase file.
4. Review the PR against the acceptance criteria, merge, then update `docs/SPEC.md` if decisions changed.

Start every phase in a fresh session: the spec, `CLAUDE.md` and the ADRs carry the context, not the chat history.
