# MVP requirements

This document defines the observable MVP behavior for the owner's daily use. A financial action is successful only after it has been durably saved.

## Journey 1: Create an account with an opening-balance transaction

### Preconditions

The user can provide an account name, currency, and opening balance.

### User action

The user enters the account details and confirms creation.

### Success state

After the account and its opening-balance transaction are durably saved together, the account appears with the requested balance. The opening balance remains visible as history so the current balance can be recomputed from the account's complete history.

### Validation failure

If required details are missing or invalid, the app identifies the fields to correct, creates neither the account nor its opening-balance transaction, and preserves the entered values.

### Infrastructure failure

If the save cannot complete, the app shows no success state, leaves no partial account or opening-balance transaction, and preserves the unsaved account draft where recovery is possible.

## Journey 2: Record income and expense with one or more category allocations

### Preconditions

At least one account and one first-level category are available.

### User action

The user chooses income or expense, enters an amount and details, distributes the amount across one or more categories, and confirms the transaction.

### Success state

After durable save, the transaction appears in account history and the timeline. Expense amounts have negative product semantics, income amounts have positive product semantics, and the category allocation totals equal the transaction amount's magnitude.

### Validation failure

If the amount is invalid, no category is selected, or allocation totals do not equal the transaction amount's magnitude, the app explains the mismatch, saves nothing, and preserves the draft.

### Infrastructure failure

If the save cannot complete, the app shows no financial success, creates no partial transaction or allocations, and preserves the unsaved draft where recovery is possible.

## Journey 3: Transfer between two distinct accounts in the same currency

### Preconditions

At least two accounts exist and the user can select a source and destination.

### User action

The user selects both accounts, enters the transfer amount and details, and confirms the transfer.

### Success state

When the source and destination are distinct and their currencies match, the transfer is durably saved all-or-nothing. Only then does the app show success and reflect the transfer consistently in both account histories and balances.

### Validation failure

If the accounts are the same, their currencies differ, or the amount is invalid, the app explains the issue, saves no part of the transfer, and preserves the draft.

### Infrastructure failure

If any part of the save cannot complete, neither account is changed, no success is shown, and the unsaved transfer draft is preserved where recovery is possible.

## Journey 4: Create and edit a Markdown note

### Preconditions

The user can open note creation or an existing note.

### User action

The user writes or edits basic Markdown content and confirms the note.

### Success state

After durable save, the note opens with the confirmed content and appears in the appropriate timeline day.

### Validation failure

If the note does not meet the visible input rules, the app explains what must change, saves no invalid revision, and keeps the edited content available.

### Infrastructure failure

If the save cannot complete, the prior saved version remains unchanged, no success is shown, and the unsaved edits are preserved where recovery is possible.

## Journey 5: Link multiple notes to one transaction and multiple transactions to one note

### Preconditions

At least one saved transaction and one saved note exist.

### User action

From either a transaction or a note, the user selects one or more records of the other type and confirms the links.

### Success state

After durable save, every confirmed link is visible and navigable from both sides. One transaction can link to multiple notes, and one note can link to multiple transactions.

### Validation failure

If a selected record is unavailable or a requested link is invalid, the app identifies the issue, adds no invalid or one-sided link, and preserves the remaining selection where recovery is possible.

### Infrastructure failure

If the links cannot be saved completely, the app shows no success, leaves the previously saved links unchanged, creates no orphan or one-sided relation, and preserves the pending selection where recovery is possible.

## Journey 6: Assign shared tags and at most one primary project

### Preconditions

A transaction or note is open for editing, and shared tags or projects are available for selection.

### User action

The user assigns any number of shared tags and optionally chooses one primary project, then confirms the change.

### Success state

After durable save, all selected tags and zero or one primary project appear on the transaction or note. The same tag can be used across both record types.

### Validation failure

If more than one primary project is requested or a selection is no longer available, the app explains the issue, saves no invalid assignment, and preserves valid pending choices where recovery is possible.

### Infrastructure failure

If assignments cannot be saved completely, the previous saved assignments remain unchanged, no success is shown, and pending choices are preserved where recovery is possible.

## Journey 7: Attach an image through a system picker

### Preconditions

A transaction or note is open, and the device can present the system image picker.

### User action

The user opens the system picker, selects an image, reviews the pending attachment, and confirms the record.

### Success state

After durable save, the image is visible from the transaction or note and remains available during later offline use.

### Validation failure

If the selected item is not a usable image or violates a visible limit, the app explains the issue, does not attach it, and preserves the rest of the draft.

### Infrastructure failure

If the image cannot be retained or the record cannot be saved, the app shows no success, leaves the saved record unchanged, and preserves the draft and pending selection where recovery is possible.

## Journey 8: Browse a mixed day timeline using persistent bottom navigation

### Preconditions

The app has saved transactions, notes, or both, or can show an empty day.

### User action

The user selects the timeline from persistent bottom navigation and moves between days or opens an item.

### Success state

The selected day shows its transactions and notes together in a coherent timeline. The bottom navigation remains available, and opening an item leads to its details without losing the selected-day context.

### Validation failure

If a requested day or item is no longer available, the app shows a clear empty or unavailable state and keeps timeline navigation usable.

### Infrastructure failure

If saved items cannot be loaded, the app distinguishes the failure from an empty day, does not present incomplete content as complete, and allows the user to retry without losing navigation context.

## Journey 9: Search transactions and notes from one entry point with type filtering

### Preconditions

The user can open the unified search entry point.

### User action

The user enters a query and optionally filters results to transactions or notes.

### Success state

The app shows matching transactions and notes together when no type filter is active, shows only the selected type when filtered, and lets the user open each result.

### Validation failure

If the query is empty or otherwise invalid under the visible search rules, the app prompts for a usable query without discarding the entered text or active filter.

### Infrastructure failure

If search cannot complete, the app does not present partial results as complete and preserves the query and filter so the user can retry.

## Journey 10: View monthly income, expense, and category statistics

### Preconditions

The user can select a month, including a month with no transactions.

### User action

The user opens statistics and selects a month.

### Success state

The app shows that month's income total, expense total, and category breakdown based on saved transactions and their allocations. A month without applicable activity is shown as a valid zero or empty state.

### Validation failure

If the requested month is invalid or unavailable, the app identifies the issue and retains the last valid view.

### Infrastructure failure

If statistics cannot be computed from the saved history, the app shows an explicit failure instead of potentially incorrect totals and allows a retry without changing financial records.

## Journey 11: Export and restore a complete logical ZIP

### Preconditions

The user can choose a destination for export or select a candidate ZIP for restore.

### User action

The user exports a complete logical ZIP, or selects one for overwrite restore and confirms replacement only after validation succeeds.

### Success state

Export produces the complete artifact required to restore the user's logical data, including relationships and images. Restore validates the whole artifact before asking for confirmation, then replaces and activates the current logical data as one complete operation. The restored content is available after activation.

### Validation failure

If the candidate ZIP is incomplete, incompatible, corrupt, or internally inconsistent, the app rejects it before confirmation or activation, explains that it cannot be restored, and leaves current data unchanged.

### Infrastructure failure

If export cannot complete, no incomplete artifact is reported as successful. If restore fails after validation, the app does not partially replace or activate the candidate data, keeps current data usable, and reports the failure.

## Journey 12: Export transaction CSV

### Preconditions

The user can choose a destination for a transaction export.

### User action

The user requests a CSV export of transactions and confirms the destination.

### Success state

The app produces a readable transaction CSV for interoperability and reports success only after the export is complete. The CSV is not presented as the complete restore artifact; the logical ZIP serves that purpose.

### Validation failure

If the destination or export selection is invalid, the app explains the issue and allows correction without changing saved data.

### Infrastructure failure

If the export cannot complete, the app does not report success or present an incomplete CSV as complete, and saved data remains unchanged.

## Journey 13: Receive shared text or image as an unsaved draft

### Preconditions

Another app shares supported text or an image to this app.

### User action

The user chooses this app as the share target, reviews the populated draft, completes any required details, and either confirms or leaves without saving.

### Success state

Shared content first appears only as an unsaved draft. It becomes a saved note or attachment only after the user explicitly confirms and durable save completes.

### Validation failure

If the shared content cannot form a valid record, the app explains what is missing or unusable, saves nothing, and preserves usable shared content in the draft where recovery is possible.

### Infrastructure failure

If the draft cannot be saved, the app shows no success, creates no partial record, and preserves the shared content and user edits where recovery is possible.

## Cross-cutting acceptance

- 30 consecutive days dogfood.
- core workflows work without network permission.
- no ads, analytics, or closed-source tracking SDK in release.
- release blockers: any data loss, non-recomputable balance, orphan relation, or backup that cannot restore.
