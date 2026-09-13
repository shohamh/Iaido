# Wayfinder tracker (local-markdown)

This repo has no issue tracker attached, so Wayfinder uses a local-markdown convention instead:

- `map.md` — the map: Destination, Notes, Decisions so far, Not yet specified, Out of scope.
- `tickets/NNN-slug.md` — one file per ticket (child of the map). Frontmatter carries id, title, type, status, assignee, and blocked_by (ids of tickets that must close first).

## Conventions

- **Claim** a ticket by setting `assignee` in its frontmatter before starting work.
- **Frontier** = tickets with `status: open`, `assignee: null`, and every id in `blocked_by` pointing at a `status: closed` ticket (empty `blocked_by` means unblocked).
- **Resolve** a ticket by appending a `## Resolution` section, setting `status: closed`, then adding a one-line gist + link to `map.md`'s "Decisions so far".
- Ticket `type` is one of `research`, `prototype`, `grilling`, `task` (see the wayfinder skill for definitions).
