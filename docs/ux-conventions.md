# UI conventions checklist

Enforced in review for every UI change (referenced by
[architecture.md](architecture.md) and [CONTRIBUTING.md](../CONTRIBUTING.md)).
The persona to protect: a non-technical operator who must never need to
read YAML, playbooks or inventories.

## Feedback — every action resolves visibly

- Every mutation ends in visible success or visible failure: an inline
  alert where a form exists, otherwise the central toast layer
  (`ToastProvider` + mutation `meta` in `src/api/queryClient.ts`).
  Opt a mutation out with `meta.silentError` only when the page renders
  the error inline.
- Pending state disables **only the acting element** (row, button,
  checkbox), never all siblings — track the pending target via mutation
  `variables` or local state.
- Live data keeps itself fresh (`refetchInterval`); pages a user watches
  (jobs, rollouts, remote sessions) poll until terminal.

## Confirmation — destructive or wide-reaching actions ask first

- Confirm when the action is destructive (delete, revoke, deny, disable)
  or affects more than one device (group apply, bulk actions).
- The confirm modal names the consequence and the affected count
  ("…and its role assignments will be removed", "12 devices, 3 offline"),
  states "This cannot be undone." only where true, and shows a pending
  label on the confirm button.
- Cancel must always leave state untouched.

## Errors — what went wrong and what to do next

- Error text says what failed and how to recover ("…could not be saved.
  Try again" / retry button / back link). Never show raw server strings —
  map known messages.
- Load errors never spin forever: every detail page has an error branch
  with retry and a way back.
- Session expiry drops to the login screen; network errors show the
  "server unreachable" notice and never log anyone out.

## Empty states — say what it is and how to get one

- An empty list explains what would appear there and links the action
  that creates the first entry.
- "No results" from a filter offers one-click "Clear filters".

## Forms and inputs

- Typed inputs from argument specs; invalid values flag inline with
  `isInvalid`, `aria-invalid` and an associated message — never silent
  coercion. Saving is blocked while a value is invalid.
- Dirty forms prompt before navigation (`NavigationGuard`).
- Never render YAML/JSON to non-experts: raw variables, commit hashes and
  similar plumbing live behind the persisted "Expert details" switch in the
  account menu. Where something is hidden, say so inline and offer a link
  that turns the switch on (see the job dialog).

## Accessibility

- No color-only status: pair color with text or a labelled badge
  (`StatusDot` pattern).
- Label every icon-only or ambiguous control (`aria-label`), keep
  `aria-sort` on sortable headers and `role="status"` on result counts.
- Modals get real titles; live regions (`aria-live`) for toasts.

## Consistency

- react-bootstrap components and Bootstrap utilities only; no new CSS or
  state libraries.
- Both themes (light/dark) must stay legible — prefer `bg-body-*` /
  semantic utilities over hardcoded colors; deliberate always-dark areas
  (log terminal, VNC canvas) are fine.
- Mobile: usable at 375 px — responsive grid, offcanvas navigation.
- Shortcuts: "/" focuses the page's table search, Ctrl/Cmd+K the global
  search.
