# Interaction rules

This document is the source of truth for user-visible interaction state, confirmation, cancellation, and failure behavior. Journey outcomes and acceptance criteria remain owned by the [MVP requirements](../product/mvp-requirements.md). Domain invariants are owned by the [domain model](../architecture/domain-model.md), and storage, attachment, backup, migration, and generation-activation protocols are owned by [data and storage](../architecture/data-and-storage.md).

The MVP is a local-first, single-user Android 14+ application. Every interaction below must work with Android network permission denied.

## Shared interaction contract

### Compose state model

Every editor or operation exposes one immutable UI state containing, as applicable:

- draft field values and pending selections;
- field-level validation messages;
- operation phase: idle, validating, saving/running, recoverable failure, blocking failure, or completed;
- one stable application error category rather than a raw infrastructure or platform exception;
- whether confirmation is currently allowed;
- progress that is explicitly determinate or indeterminate, never fabricated.

The ViewModel remains the owner of draft state while the flow is in the running app. A recoverable failure preserves every entered field and selected media needed for retry. This does not promise unsaved-draft recovery after force-stop, OS process death, uninstall, or device loss.

### Shared entry state

Unless a flow defines an override below, its initial state is determined as follows:

- **New editor:** start in `idle` with no persisted record identity. Required fields are empty except an occurrence date/time defaulted from an explicit launch context or, when no context supplies one, the current local date/time. Optional text, relations, assignments, and media start empty. A launch context may prefill only fields it owns, such as the selected account or timeline day; state from another abandoned draft is never copied implicitly.
- **Existing-record editor:** start in `loading` with the primary action disabled. Load the complete last-saved record before presenting an editable `idle` state. A partial load is never rendered as an empty field. Load failure keeps editing disabled and offers Retry or Back.
- **Selection subflow:** start from the parent draft's current selections, not from defaults. Returning from a picker, preview, or linked-record screen restores the unchanged parent draft plus any explicit new selection.
- **Read or operation flow:** start in `loading`, `checking`, or `selecting` as named by that flow, with no success state and with its primary action disabled until all required input and preconditions are available.
- **Primary action:** Save, Confirm, Continue, Search, or Export begins disabled while required data is loading, while local validation fails, or while another attempt is running. It becomes enabled only when the current complete input is valid. An edit may additionally keep Save disabled until the draft differs from the last-saved state.
- **Draft restoration:** navigation within the running flow, configuration recreation, and any recoverable failure restore all entered fields and selected media. Explicit Discard clears that draft. Opening a fresh create route after discard starts from new-editor defaults. Recovery after force-stop, OS process death, uninstall, or device loss is not promised by MVP.

Each per-flow **Entry-state override** below changes only the named defaults. All other shared entry-state rules continue to apply.

### Validation and durable-save boundary

- Trim text for validation without silently replacing the user's visible draft before confirmation.
- Field and business validation happens before a save attempt and points to the field or selection that needs correction.
- While a save is running, duplicate confirmation is disabled. System Back does not turn an in-flight write into a success or start a second write.
- A financial success state is shown only after the complete financial change is durably saved. No balance, history, timeline, or statistics UI may optimistically claim a successful financial write.
- A note or link success state is shown only after its complete requested change is durably saved. An edit failure leaves the prior saved version unchanged.
- Recoverable I/O failure keeps the draft and offers Retry or, when an external URI is no longer usable, Reselect.
- Space exhaustion, permission loss, corrupt input, and incompatible backup are blocking for the current attempt and explain the next safe action. They are not blindly retried.

### Cancel and Back

- Cancel or System Back from a pristine draft returns to the launch context without writing.
- Cancel or System Back from a dirty draft asks the user to continue editing or discard the unsaved draft. Discard performs no domain write and removes only temporary, unconfirmed media owned by that draft.
- Back from a picker that returned no selection restores the unchanged draft.
- Back from a progress screen requests cancellation only when its operation has a defined safe cancellation boundary. Otherwise it keeps the operation screen visible and explains that the current atomic step must finish.

## Typed creation dispatch

The global `+` chooser and feature-local typed actions all dispatch to one of four explicit drafts. There is no generic Entry draft.

| Choice | Draft owner | Required core selection | Categories | Result type |
|---|---|---|---|---|
| Expense | Finance | Account and positive amount | One or more allocations | `EXPENSE` transaction |
| Income | Finance | Account and positive amount | One or more allocations | `INCOME` transaction |
| Transfer | Finance | Distinct source and destination accounts in the same currency, and positive amount | Forbidden | `TRANSFER` transaction |
| Note | Notes | Non-empty trimmed Markdown body or at least one confirmed image | Not applicable | `Note` |

### Expense and income creation

**Entry point**

- Global `+` → Expense or Income.
- A Finance-owned typed action may open the same editor with an account or date preselected from the launch context.

**Entry-state override**

The selected transaction type is fixed by the typed entry point. A new draft starts with blank amount and allocations; it may prefill only an explicitly selected account and date. Save is initially disabled and remains disabled until account, amount, occurrence time, and exact allocations are valid. Editing an existing income or expense first loads its complete saved fields, assignments, links, and confirmed images under the shared existing-record rule.

**Fields and selections**

- required account;
- required amount in the selected account currency;
- required occurrence date/time;
- one or more required first-level category allocations;
- optional descriptive transaction fields;
- optional shared tags, one optional primary project, links to saved notes, and pending confirmed images.

The UI displays allocation values as positive magnitudes. It does not ask the user to enter a sign: an Expense reduces the selected balance and an Income increases it.

**Validation**

- Account and occurrence date/time must be present and available.
- Amount must be parseable in the account currency, representable in integer minor units, and greater than zero.
- At least one available first-level category is selected.
- Every allocation is positive and the allocation sum equals the transaction amount exactly in minor units.
- Requested tags, project, note links, and images must pass their typed selection and attachment rules before the save can commit.

**Save/confirm**

Confirm saves the transaction, its complete category allocations, requested links/assignments, and confirmed images as one all-or-nothing change. The editor enters a saving state and retains the draft. Only after durable save may it clear the draft, show success, update the balance, and navigate to the transaction detail or its launch context.

**Cancel/Back**

Use the shared dirty-draft rule. No transaction, allocation, relation, or attachment link is written by discard.

**Failure/retry**

Validation failure leaves focusable field errors and the complete draft. A save or image-import failure applies none of the requested change, shows no financial success, leaves prior balances unchanged, and preserves the draft for Retry or Reselect. A failed image preparation may be removed or reselected without clearing unrelated transaction fields.

### Transfer creation

**Entry point**

- Global `+` → Transfer.
- A Finance-owned account action may preselect that account as source while leaving destination explicit.

**Entry-state override**

A new draft fixes the type as Transfer, starts with blank amount and destination, and may prefill only an explicitly selected source account and date. Confirm is initially disabled and remains disabled until both account selections and the amount/date are valid. Editing first loads both saved account selections and every other saved field before enabling changes.

**Fields and selections**

- required source account;
- required destination account;
- required positive amount in their shared currency;
- required occurrence date/time;
- optional descriptive transaction fields;
- optional shared tags, one optional primary project, links to saved notes, and pending confirmed images;
- no category field or category allocation.

**Validation**

- Both accounts must exist, be available, be distinct, and use the same currency.
- Amount must be parseable in that currency, representable in integer minor units, and greater than zero.
- Occurrence date/time is required.
- Requested relations and images must remain valid at confirmation.

**Save/confirm**

Confirm saves both equal-and-opposite account effects and every requested link/assignment/image as one all-or-nothing transfer. Success appears only after both account histories and balances durably reflect the zero-sum transfer.

**Cancel/Back**

Use the shared dirty-draft rule. Neither account is changed by cancellation.

**Failure/retry**

Any save failure applies neither account effect nor any requested link/assignment/image. The UI shows no transfer success and no one-sided balance change. It preserves source, destination, amount, date, optional fields, links, and pending media for correction or Retry.

### Note creation and editing

**Entry point**

- Global `+` → Note.
- Notes → new note.
- Note detail → edit opens a draft based on the last saved version.

**Entry-state override**

A new note starts with blank Markdown, no confirmed image, and the launch-context or current date/time; Save is initially disabled. It becomes enabled when occurrence time is valid and either trimmed Markdown is non-empty or at least one image is confirmed. Edit starts in `loading` and does not expose an editable empty note while the saved body, images, assignments, and transaction links are being loaded.

**Fields and selections**

- Markdown body, which may be empty only when a confirmed image exists;
- required occurrence date/time;
- zero or more pending confirmed images;
- optional shared tags, one optional primary project, and links to saved transactions.

The MVP does not require or infer a note title. Implementations must not block save on an unapproved title field.

**Validation**

- The note is valid when its trimmed Markdown body is non-empty **or** at least one image has completed validation and is confirmed in the draft.
- Occurrence date/time is required.
- Requested tags, project, transaction links, and images must remain valid at confirmation.

**Save/confirm**

Confirm atomically saves the Note revision and all requested relations and attachment links. For edit, the prior saved note stays authoritative until the complete replacement commits. Success then opens the confirmed note and makes it eligible for the appropriate timeline day.

**Cancel/Back**

Use the shared dirty-draft rule. Discarding an edit leaves the prior saved note unchanged.

**Failure/retry**

Validation identifies the missing content condition without erasing Markdown or pending images. Infrastructure failure shows no success, leaves the prior saved version unchanged, and preserves the draft for Retry or image Reselect.

## Account and opening balance

**Entry point**

Finance → create account opens one combined account-and-opening-balance editor.

**Entry-state override**

The combined editor starts in `idle` with blank account name, no currency, blank opening balance, and the product default opening date if one exists. It never starts from another account's values. Create is initially disabled and becomes enabled only when name, currency, opening balance, and any shown date are valid. Returning from currency selection restores name, balance, and date unchanged.

**Fields and selections**

- required trimmed account name;
- required currency;
- required opening balance valid in that currency;
- optional occurrence date/time when the product supplies a non-default opening date.

The displayed balance is not directly persisted as an editable account field. It is input to an `OPENING_BALANCE` transaction.

**Validation**

Name must remain non-blank after trimming. Currency must be selected. Opening balance must be parseable and representable in integer minor units for that currency. A rejected value creates nothing.

**Save/confirm**

Confirm saves the account and its opening-balance history as one all-or-nothing change. Success is shown only when the account appears with the requested balance and that balance is recomputable from the visible history.

**Cancel/Back**

Use the shared dirty-draft rule. Cancellation creates neither account nor transaction.

**Failure/retry**

Any failure leaves no partial account or opening transaction, shows no success, and preserves name, currency, amount, and date for correction or Retry.

## Transaction–note linking from either side

**Entry point**

- Transaction detail/edit → link notes.
- Note detail/edit → link transactions.

Both entry points edit the same typed transaction–note link; neither creates a generic relation or relation type.

**Entry-state override**

The selector starts in `loading` with Confirm disabled while it loads the fixed source and the complete previously saved link set. It becomes selectable only after that set is known. Confirm remains disabled until the proposed set differs from the saved set and all selected targets are valid. A load failure never presents an empty set as though all links were removed.

**Fields and selections**

The persisted source record is fixed. The user selects zero or more active records of the opposite type. Existing saved links are shown distinctly from proposed additions/removals.

**Validation**

At confirmation, the source and every selected target must still exist and match the expected Transaction/Note endpoint type. Duplicate pairs collapse to one relation. If any requested endpoint is unavailable, the whole requested link change is invalid.

**Save/confirm**

Confirm saves the complete requested add/remove set as one all-or-nothing change. Only afterward are the links shown as saved and navigable from both sides.

**Cancel/Back**

Cancellation discards only the proposed selection and leaves all prior links unchanged.

**Failure/retry**

If validation or save fails, none of the requested changes are saved, prior links remain unchanged, and no orphan or one-sided association appears. Preserve the proposed selection where all selected records are still available; mark unavailable selections for correction before Retry.

## Shared tags and primary project assignment

**Entry point**

A saved or draft Transaction or Note opens its assignment control from the owning detail/editor.

**Entry-state override**

For a saved owner, start in `loading` with Confirm disabled until the complete saved tag set, primary project, and available choices are loaded. For an unsaved owner, start from the parent draft's current assignments. Confirm is disabled until the proposed assignments are valid and differ from the starting set. Load failure retains the prior known assignments and never renders them as unassigned.

**Fields and selections**

- zero or more active shared tags;
- zero or one active primary project.

Tags apply only to Transaction and Note endpoints. A project itself cannot receive MVP tags or attachments. The project selection is a primary context, not an arbitrary relation.

**Validation**

Every selected tag and the optional project must still exist and be available at confirmation. More than one project cannot be selected. The owning entity must be a Transaction or Note.

**Save/confirm**

For a saved owner, confirm replaces the requested tag set and primary-project assignment as one all-or-nothing change. For a new owner, the assignments are part of that owner's all-or-nothing save. Only saved selections appear on detail, project aggregation, timeline, or search results.

**Cancel/Back**

Cancellation restores the prior saved selections, or the prior draft selections when launched inside an unsaved editor.

**Failure/retry**

If any selection is invalid or the full assignment cannot be saved, none of the requested tag/project changes are saved. Prior saved assignments remain unchanged, the proposed selections remain visible for correction, and no success is shown.

## Image attachment import

**Entry point**

A Transaction or Note editor invokes Android Photo Picker (or the explicitly supported system picker fallback). Share-image drafts enter the same pending-media pipeline after the share entry has supplied a URI.

**Entry-state override**

Entry preserves the complete parent draft and starts in `selecting` with no new image and no attachment success. After a selection, the preview starts in `validating`; Add/Confirm remains disabled until the image is readable and accepted. Returning from preview restores the parent draft and only the explicitly confirmed image. Reopening import after removal starts with no pending selection rather than reviving the removed image.

**Fields and selections**

The user chooses an image, reviews a pending preview, and either confirms it into the owner draft or removes/reselects it. The UI never exposes an internal storage location.

**Validation**

No picker result means no attachment change. A selected item must be readable, satisfy size limits, be an accepted image type, and be decodable. All required image validation must complete before the image is marked confirmed in the draft.

**Save/confirm**

Confirming the preview only adds the image to the unsaved owner draft; it does not save an independent attachment. The image becomes durably visible only when the complete owning Transaction or Note saves successfully. The required storage reliability behavior is defined by [data and storage](../architecture/data-and-storage.md).

**Cancel/Back**

Back from the picker or pending preview leaves the owner draft unchanged unless the user explicitly removes a pending image. Discarding the owner draft saves no new image and never removes an image already used by a saved record.

**Failure/retry**

Unreadable or invalid content adds no image and leaves the rest of the draft intact. Import or owner-save failure shows no success, leaves a saved owner unchanged, exposes no missing image as an attachment, and preserves the owner draft for Retry or Reselect.

## System share drafts

**Shared entry-state override**

The share flow starts in `loading` while the app determines whether the incoming content contains usable text, an image, or both. No saved record, destination type, or success state exists at entry. After readable content is available, the flow enters `selecting` with Note/Expense/Income/Transfer unselected and Continue disabled until the user chooses one. The chosen typed editor then starts as a new draft, prefilled only from the incoming content as defined below; all other fields use that editor's new-entry defaults. Returning from the chosen editor within the running flow restores the share-populated draft. Explicit Discard or an empty-share result leaves nothing to restore.

### Shared text

**Entry point**

Android Sharesheet → this app → choose Note draft or a typed Transaction draft (Expense, Income, or Transfer).

**Fields and selections**

For Note, shared text prepopulates the Markdown body. For a Transaction, it prepopulates the approved descriptive field only; account, amount, transaction type requirements, categories where applicable, and date remain explicit typed draft fields.

**Validation**

Shared text is trimmed for the empty-share check. Empty text with no readable shared image produces an empty-share state and no draft fact. Once a destination is chosen, all validation rules of that Note or typed Transaction apply.

**Save/confirm**

Entering from share creates only unsaved draft state. Nothing is saved until the user explicitly confirms the owning Note or Transaction and its complete durable save succeeds.

**Cancel/Back**

Leaving follows the shared dirty-draft rule. Discard returns without saving shared content.

**Failure/retry**

A recoverable save failure preserves the populated text and all completed fields. A failed transaction shows no financial success and creates no partial fact.

### Shared image

**Entry point**

Android Sharesheet → this app → choose Note draft or a typed Transaction draft.

**Fields and selections**

The shared image enters as pending media in `validating` and follows the Image attachment import rules. The chosen owner editor supplies all other required fields, and its primary action remains disabled until the image and all owner fields are valid.

**Validation**

The URI must be readable as a valid image. If it is not and no non-empty shared text remains, show the empty-share state and save nothing. The owning draft must independently satisfy its Note or typed Transaction validation.

**Save/confirm**

The image becomes saved only as part of the confirmed owner's complete all-or-nothing save. It is never an independent record or visible orphan attachment.

**Cancel/Back**

Discard removes the unsaved owner draft and leaves no visible image record.

**Failure/retry**

URI permission or read failure keeps any other draft values, identifies the media problem, and offers Reselect when possible. Owner-save failure shows no success and creates neither a partial owner nor a visible orphan image.

## Unified search and type filtering

**Entry point**

Search from any top-level app bar opens one Unified Search screen and retains the launching tab as return context.

**Entry-state override**

A fresh Search screen starts in `idle` with blank query, All selected, no result claim, and the Search trigger disabled. Returning from a source detail restores the prior query, type filter, completed results, and list position. A trimmed non-empty query enables Search; starting it enters `loading` without labelling stale or partial content as the new result. Changing the filter retains the query and follows the same validity rule.

**Fields and selections**

- query text;
- exactly one type filter: All, Transactions, or Notes.

All is the default for a new search. Query and filter are retained when opening a result and returning.

**Validation**

Only a trimmed non-empty query executes. An empty query shows a prompt/empty state, retains the active filter, and does not label any content as results.

**Save/confirm**

Search performs no domain save. A completed query replaces the visible result set only when that query/filter request completes. All combines matching transaction and note projections; a typed filter shows only that type. Selecting a result opens its Finance- or Notes-owned source detail.

**Cancel/Back**

Clearing the query returns to the prompt while retaining the filter. Back returns to the launching tab with its scroll/filter/date state intact.

**Failure/retry**

Failure is distinct from zero results. Do not present a partial page as the complete result set. Preserve query and filter, keep the last clearly labelled prior result state only if it cannot be mistaken for the failed query, and offer Retry.

## Transaction CSV export

**Entry point**

Settings → Data → Export transactions as CSV → Android Storage Access Framework destination confirmation.

**Entry-state override**

Entry starts in `checking` with CSV fixed as the operation kind, no destination, no progress, and Export disabled. After the app confirms that at least one transaction can be exported, it enters `selecting`; Export becomes enabled only after the user confirms a writable destination. Returning from the destination picker without a selection restores `selecting` and never creates a success state. Retry retains the CSV operation kind but requires reselection when the prior destination is no longer writable.

**Fields and selections**

The user chooses a writable destination. MVP exports all saved transactions; it has no selective record filter. The progress state identifies CSV, not backup.

**Validation**

At least one saved transaction must exist and a writable destination must be confirmed. The exporter validates UTF-8 output, one header row, consistent columns, and one row per saved transaction whose values match the user-visible transaction detail. CSV is an interoperability artifact and never a restore source.

**Save/confirm**

After destination confirmation, show honest determinate progress when total work is known, otherwise indeterminate phase text. Report success only after the complete destination artifact is available and verified. CSV is a separate artifact and is not included in the logical backup ZIP.

**Cancel/Back**

Before export starts, cancellation returns to Settings. During export, Cancel requests cancellation at the next safe boundary; until cancellation is acknowledged, the screen does not claim completion. A cancelled or incomplete result is never reported or offered as a completed CSV. Saved app data is unchanged.

**Failure/retry**

Permission loss, space exhaustion, provider failure, or validation failure shows no success and does not expose an incomplete artifact as complete. Preserve the operation kind and offer Retry after a new destination is selected when required.

## Complete logical backup export

**Entry point**

Settings → Data → Create complete backup → Android Storage Access Framework destination confirmation.

**Entry-state override**

Entry starts in `checking` with Complete Backup fixed as the operation kind, no destination, no progress, and Create Backup disabled. It moves to `selecting` only when backup prerequisites are available, and the primary action becomes enabled only after a writable destination is confirmed. Returning from destination selection restores the operation kind but not an unconfirmed destination. Retry preserves the user's place in the backup flow and requests a new destination only when required.

**Fields and selections**

The only user selection is the destination. MVP always creates a complete logical ZIP; it does not select individual entities and does not add encryption options. Its complete contents are defined by [data and storage](../architecture/data-and-storage.md). It does **not** contain transaction CSV.

**Validation**

The destination must be writable, enough working space must be available, and the complete backup must pass all integrity and completeness checks defined by [data and storage](../architecture/data-and-storage.md) before success can be reported.

**Save/confirm**

Progress identifies user-meaningful phases such as collecting records and images, validating the backup, and writing the selected destination. Report success only when the complete backup is verified and available at the selected destination.

**Cancel/Back**

Cancel requests cancellation at the next safe boundary. Until cancellation is acknowledged, the screen does not claim completion. Any incomplete result is never offered as restorable. Cancellation does not change current app data.

**Failure/retry**

Any read, write, space, permission, destination, or verification failure shows no success and leaves current app data unchanged. An incomplete result cannot be selected from the app's restorable-backup list. Retry may require destination reselection.

## Overwrite restore wizard

Restore is one focused flow with bottom navigation hidden. The UI must never use “partially restored,” “some data restored,” or equivalent success wording. The only successful outcome is a complete replacement that passes every preflight, activation, recovery, and post-restart validation gate defined by [data and storage](../architecture/data-and-storage.md).

**Entry-state override**

A new restore flow starts in `selecting` with no candidate, no summary, no progress, no success state, and Continue disabled. Selecting a candidate enters `checking`; overwrite confirmation remains unavailable until complete preflight succeeds. If the app re-enters while an earlier restore or migration recovery still requires resolution, it starts in `recovering` instead of showing a fresh candidate picker. Normal data navigation and a new restore attempt remain disabled until that recovery reaches a verified safe result.

### 1. Candidate selection and preflight

**Entry point**

Settings → Data → Restore complete backup opens the Restore Wizard, then Android Storage Access Framework selects a candidate ZIP.

**Fields and selections**

One selected backup. Preflight may show non-sensitive summary information such as backup creation time, compatibility, and validated record/image counts only after reading them safely.

**Validation**

Before asking for overwrite confirmation, validate the entire package, its compatibility, completeness, integrity, relationships, image ownership, resulting balances, and required working-space availability according to [data and storage](../architecture/data-and-storage.md). CSV is never accepted as restore input.

**Save/confirm**

Preflight does not change current data. Only a fully valid candidate enables Continue and advances to confirmation.

**Cancel/Back**

Cancel closes the wizard, releases the candidate selection, and returns to Settings with current data unchanged.

**Failure/retry**

Unreadable, corrupt, incompatible, unsafe, incomplete, or oversized input is rejected before confirmation. Current data remains unchanged. The failure names the safe category and permits Reselect; it does not offer a “restore anyway” bypass.

### 2. Overwrite confirmation

**Entry point**

Only successful preflight enters confirmation.

**Entry-state override**

The confirmation screen starts with Confirm Restore disabled until the complete validated summary is present and the user acknowledges that all current data will be replaced. If validation is no longer current, the flow returns to `checking` and disables confirmation until preflight succeeds again.

**Fields and selections**

The screen identifies that the complete current vault will be replaced as one all-or-nothing operation and that the existing vault remains the recovery point until the replacement is proven safe. It presents the already validated backup summary; no entity-level merge choices exist.

**Validation**

Confirmation remains disabled if the candidate changed or its prior validation is no longer current. In that case preflight runs again. Restore cannot start while an earlier restore or migration recovery is unresolved.

**Save/confirm**

One explicit confirmation begins the complete restore operation. Confirmation is not restore success.

**Cancel/Back**

Cancel returns to candidate selection or Settings without changing current data.

**Failure/retry**

If the candidate becomes unavailable or preconditions change, return to preflight with current data unchanged and Confirm Restore disabled.

### 3. Restore progress

**Entry point**

Explicit overwrite confirmation.

**Entry-state override**

Progress starts in `running` at the first truthful restore phase, with duplicate confirmation disabled and no success state. Cancel is enabled only while the operation can still honor cancellation without exposing a replacement. Once safe cancellation is no longer available, Cancel becomes disabled and the screen explains that validation or recovery must finish.

**Fields and selections**

No editable fields. Progress exposes truthful user-meaningful phases: preparing replacement, validating all records and images, applying complete replacement, verifying startup, and finalizing. It does not expose implementation paths, record internals, or private values.

**Validation**

Before replacement, the candidate must pass all architecture-defined checks needed to prove that every record, relation, assignment, image, and recomputed balance is complete and consistent.

**Save/confirm**

The internal generation-activation and migration-recovery protocols are owned exclusively by [data and storage](../architecture/data-and-storage.md). Their user-visible contract is:

1. Current data remains unchanged while the complete candidate is prepared and validated.
2. Replacement is applied as one operation; records, relationships, images, and balances never become visible piecemeal.
3. Any required migration recovery completes before the app opens data for normal use.
4. The replacement is revalidated during startup before it is accepted as the current vault.
5. Restore Success is emitted only after the replacement is verified usable; until then the prior vault remains the recovery point.

**Cancel/Back**

Cancellation is accepted only before complete replacement begins and leaves current data unchanged. After that boundary, Back/Cancel cannot claim cancellation; the flow must finish validation or recovery. The screen explains that recovery is finishing and prevents duplicate restore starts.

**Failure/retry**

- Failure before replacement leaves the original vault unchanged.
- Interruption during replacement resumes the architecture-defined recovery before normal data use.
- Failure during startup verification returns to the original vault as one complete recovery outcome.
- While safe recovery is unresolved, normal data navigation and new restore attempts remain blocked rather than opening uncertain data.
- Cleanup or diagnostics never become a user-visible success substitute.

None of these failures may be described as partial restore success. Retry starts from a fresh preflight only after the original vault or completed replacement is verified safe.

### 4. Terminal success

**Entry point**

The complete replacement has passed startup revalidation and the app has verified it is safe for normal use.

**Entry-state override**

This state can only be entered from completed verification. It starts in `completed` with Continue enabled, no Retry action, and no editable restore input. It is never restored from an unverified progress snapshot.

**Fields and selections**

No editable fields. The result states that the complete backup was restored and may show validated counts without revealing private content.

**Validation**

Success requires the complete record, relation, assignment, image ownership/integrity, and recomputed-balance round trip promised by Journey 11. A candidate that has only been copied, parsed, or partially checked is not success.

**Save/confirm**

Continue enters Timeline using the completely restored vault. The UI makes no claim about internal recovery-point retention or cleanup timing.

**Cancel/Back**

The operation is already complete. Back or Continue leaves the result and opens the application; it does not undo restore.

**Failure/retry**

If the complete replacement cannot be proven safe for normal use, do not render this state. Route to recovery/failure instead.

### 5. Terminal failure and recovery

**Entry point**

Pre-replacement failure, verified return to the original vault after replacement failure, or unresolved restore/migration recovery.

**Entry-state override**

Entry starts in `recoverable failure`, `blocking failure`, or `recovering` according to the verified outcome. Retry is enabled only when current data is known safe and the failed operation is retryable. Back is enabled only when normal navigation is safe. While recovery is unresolved, both a new restore and normal navigation remain disabled; only the explicit recovery action or status is available.

**Fields and selections**

No private diagnostic details. Show a stable error category, failed phase, whether the original vault is available or recovery is still required, and the next safe action.

**Validation**

Normal navigation is enabled only after the architecture-owned recovery process verifies a complete safe vault. The UI never infers safety from an interrupted progress indicator or the presence of a selected backup.

**Save/confirm**

After a verified return to the original vault, Continue opens that unchanged vault and may offer a fresh restore attempt. While restore or migration recovery remains unresolved, normal data navigation stays blocked and only the explicit recovery path is available.

**Cancel/Back**

Back cannot bypass an unresolved migration-recovery gate. After the original vault is verified safe, Back may return to Settings.

**Failure/retry**

Retry continues the architecture-owned safe recovery or starts a fresh restore only after recovery completes. It never offers a bypass, merge, or partial-acceptance path.

## Interaction acceptance matrix

Future ViewModel and Compose tests must cover at least:

| Interaction | Success assertion | Required failure assertion |
|---|---|---|
| Expense / Income | One saved account effect and exact allocations | No optimistic success; complete draft retained; no partial allocation |
| Transfer | Two distinct same-currency account effects sum to zero | Neither account changes on any failure |
| Note | Body or confirmed image is sufficient; no title required | Prior saved revision unchanged; draft retained |
| Opening balance | Account and matching opening-balance history both exist | Neither appears after failed save |
| Transaction–note linking | Every selected link navigates from both sides | Prior link set unchanged; no one-sided relation |
| Tags / project | Many tags and at most one project save together | Prior assignments unchanged |
| Image import | Confirmed owner and image remain available offline | No visible link to missing content; draft retained |
| Share text/image | Only an unsaved typed draft exists before confirm | Leaving saves nothing; no orphan image |
| Search/filter | All/Transaction/Note results dispatch to source | Query/filter retained; partial results not presented as complete |
| CSV export | Complete verified CSV, all transactions exactly once | Cancel/failure never reports incomplete output as complete |
| Logical backup | Complete verified ZIP without CSV | Incomplete output is not restorable |
| Restore | Complete replacement passes startup verification | Original vault remains/returns usable; no partial-success wording; migration gate cannot be bypassed |
