# Product vision

See [MVP requirements](mvp-requirements.md) for the user journeys and acceptance criteria that implement this vision.

## Core proposition

Connect “what I spent” with “what happened” in one local, private, portable timeline.

## Primary user

The owner of the app, using it daily on Android 14+ without a required account. It is optimized for one person's continued Codex-assisted development and personal dogfooding.

## Problem

Finance apps lose life context; note apps cannot enforce financial truth.

## Product principles

Timeline-first, domain-specific creation, local-first, recoverable, open export, no silent data loss.

## Success criterion

Thirty consecutive days of real use with reliable linking, search, export, wipe, and full restore.

## MVP goals

- accounts and opening balance;
- income, expense, same-currency transfer;
- first-level categories;
- shared tags;
- basic Markdown notes;
- transaction-note many-to-many relation;
- at most one primary project per transaction or note;
- image attachments;
- day timeline;
- unified search;
- monthly income/expense and category statistics;
- full ZIP export and overwrite restore;
- transaction CSV export;
- shared text/image becomes an unsaved draft.

## Non-goals

- budgets, recurring transactions, tasks, reminders;
- subcategories;
- cross-currency transfer and exchange-rate history;
- arbitrary file attachments;
- OCR, AI features, voice transcription;
- SQLCipher as default;
- cloud backup, record-level sync, real-time multi-device, collaboration;
- generic arbitrary entity relations;
- full double-entry chart of accounts;
- iOS, KMP, Flutter, React Native clients.

## Product claim boundary

The MVP is not full double-entry accounting and is not cloud synchronization.
