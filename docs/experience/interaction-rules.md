# Interaction rules

This document is the source of truth for user-visible interaction state, confirmation, cancellation, and failure behavior. Journey outcomes and acceptance criteria remain owned by the [MVP requirements](../product/mvp-requirements.md). Domain invariants are owned by the [domain model](../architecture/domain-model.md), and storage, attachment, backup, migration, and generation-activation protocols are owned by [data and storage](../architecture/data-and-storage.md).

The MVP is a local-first, single-user Android 14+ application. Every interaction below must work with Android network permission denied.

## Shared interaction contract

### Compose state model

Every editor or operation exposes one immutable UI state containing, as applicable:

- draft field values and pending selections;
- field-level validation messages;
- operation phase: idle, validating, committing/running, recoverable failure, blocking failure, or completed;
- one stable application error category rather than a raw Room, file, ZIP, permission, or platform exception;
- whether confirmation is currently allowed;
- progress that is explicitly determinate or indeterminate, never fabricated.

The ViewModel remains the owner of draft state while the flow is in the running app. A recoverable failure preserves every entered field and selected media needed for retry. This does not promise unsaved-draft recovery after force-stop, OS process death, uninstall, or device loss.

### Validation and commit boundary

- Trim text for validation without silently replacing the user's visible draft before confirmation.
- Field and business validation happens before a repository or Use Case commit and points to the field or selection that needs correction.
- While a commit is running, duplicate confirmation is disabled. System Back does not turn an in-flight write into a success or start a second write.
- A financial success state is shown only after the complete Room transaction durably commits. No balance, history, timeline, or statistics UI may optimistically claim a successful financial write.
- A note or relation success state is shown only after its complete requested change commits. An edit failure leaves the prior saved version unchanged.
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

**Fields and selections**

- required account;
- required amount in the selected account currency;
- required occurrence date/time;
- one or more required first-level category allocations;
- optional descriptive transaction fields;
- optional shared tags, one optional primary project, links to saved notes, and pending confirmed images.

The UI displays allocation values as positive magnitudes. It does not ask the user to enter the storage sign: Expense produces one negative account entry and Income one positive account entry at the domain boundary.

**Validation**

- Account and occurrence date/time must be present and available.
- Amount must be parseable in the account currency, representable in integer minor units, and greater than zero.
- At least one available first-level category is selected.
- Every allocation is positive and the allocation sum equals the transaction amount exactly in minor units.
- Requested tags, project, note links, and images must pass their typed selection and attachment rules before the save can commit.

**Save/confirm**

Confirm submits the Transaction, its one AccountEntry, all CategoryAllocations, requested relations, attachment metadata, and attachment links as one atomic owner save. The editor enters a committing state and retains the draft. Only after durable commit may it clear the draft, show success, update the balance, and navigate to the transaction detail or its launch context.

**Cancel/Back**

Use the shared dirty-draft rule. No transaction, allocation, relation, or attachment link is written by discard.

**Failure/retry**

Validation failure leaves focusable field errors and the complete draft. A repository or file failure rolls back the whole requested save, shows no financial success, leaves prior balances unchanged, and preserves the draft for Retry or Reselect. A failed image preparation may be removed or reselected without clearing unrelated transaction fields.

### Transfer creation

**Entry point**

- Global `+` → Transfer.
- A Finance-owned account action may preselect that account as source while leaving destination explicit.

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

Confirm invokes the transfer Use Case. One Transaction, the negative source AccountEntry, the equal positive destination AccountEntry, and all requested relations commit atomically. Success appears only after both account effects durably commit and their algebraic sum is zero.

**Cancel/Back**

Use the shared dirty-draft rule. Neither account is changed by cancellation.

**Failure/retry**

Any commit failure rolls back both entries and every requested relation. The UI shows no transfer success and no one-sided balance change. It preserves source, destination, amount, date, optional fields, links, and pending media for correction or Retry.

### Note creation and editing

**Entry point**

- Global `+` → Note.
- Notes → new note.
- Note detail → edit opens a draft based on the last saved version.

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

**Fields and selections**

- required trimmed account name;
- required currency;
- required opening balance valid in that currency;
- optional occurrence date/time when the product supplies a non-default opening date.

The displayed balance is not directly persisted as an editable account field. It is input to an `OPENING_BALANCE` transaction.

**Validation**

Name must remain non-blank after trimming. Currency must be selected. Opening balance must be parseable and representable in integer minor units for that currency. A rejected value creates nothing.

**Save/confirm**

Confirm performs one atomic operation creating the Account, its `OPENING_BALANCE` Transaction, and that transaction's unique AccountEntry. Success is shown only after all three commit; the new account then displays a balance recomputable from its history.

**Cancel/Back**

Use the shared dirty-draft rule. Cancellation creates neither account nor transaction.

**Failure/retry**

Any failure leaves no partial account or opening transaction, shows no success, and preserves name, currency, amount, and date for correction or Retry.

## Transaction–note linking from either side

**Entry point**

- Transaction detail/edit → link notes.
- Note detail/edit → link transactions.

Both entry points edit the same typed `TransactionNoteRelation`; neither creates a generic relation or relation type.

**Fields and selections**

The persisted source record is fixed. The user selects zero or more active records of the opposite type. Existing saved links are shown distinctly from proposed additions/removals.

**Validation**

At confirmation, the source and every selected target must still exist and match the expected Transaction/Note endpoint type. Duplicate pairs collapse to one relation. If any requested endpoint is unavailable, the whole requested link change is invalid.

**Save/confirm**

Confirm commits the complete requested add/remove set atomically. Only after commit are the links shown as saved and navigable from both sides.

**Cancel/Back**

Cancellation discards only the proposed selection and leaves all prior links unchanged.

**Failure/retry**

If validation or commit fails, none of the requested changes are saved, prior links remain unchanged, and no orphan or one-sided association appears. Preserve the proposed selection where all selected records are still available; mark unavailable selections for correction before Retry.

## Shared tags and primary project assignment

**Entry point**

A saved or draft Transaction or Note opens its assignment control from the owning detail/editor.

**Fields and selections**

- zero or more active shared tags;
- zero or one active primary project.

Tags apply only to Transaction and Note endpoints. A project itself cannot receive MVP tags or attachments. The project selection is a primary context, not an arbitrary relation.

**Validation**

Every selected tag and the optional project must still exist and be available at confirmation. More than one project cannot be selected. The owning entity must be a Transaction or Note.

**Save/confirm**

For a saved owner, confirm atomically replaces the requested tag set and primary-project assignment. For a new owner, the assignments join that owner's atomic save. Only committed selections appear on detail, project aggregation, timeline, or search projections.

**Cancel/Back**

Cancellation restores the prior saved selections, or the prior draft selections when launched inside an unsaved editor.

**Failure/retry**

If any selection is invalid or the full assignment cannot commit, none of the requested tag/project changes are saved. Prior saved assignments remain unchanged, the proposed selections remain visible for correction, and no success is shown.

## Image attachment import

**Entry point**

A Transaction or Note editor invokes Android Photo Picker (or the explicitly supported system picker fallback). Share-image drafts enter the same pending-media pipeline after the share entry has supplied a URI.

**Fields and selections**

The user chooses an image, reviews a pending preview, and either confirms it into the owner draft or removes/reselects it. UI state holds only a safe pending-media handle or persisted attachment ID, never a physical storage path.

**Validation**

No picker result means no attachment change. A selected item must be readable, satisfy size limits, have an accepted declared/decoded image MIME type, and be decodable. Content verification and SHA-256 calculation must complete before the image is marked confirmed in the draft.

**Save/confirm**

Confirming the preview only confirms the image in the unsaved owner draft; it does not independently persist an attachment. The owner Save invokes the attachment flow defined by [data and storage](../architecture/data-and-storage.md): app-private same-filesystem temporary copy, validation and hashing, serialized content-addressed move/reuse, then one Room transaction for owner facts, finance children where applicable, relations, Attachment metadata, and AttachmentLinks.

**Cancel/Back**

Back from the picker or pending preview leaves the owner draft unchanged unless the user explicitly removes a pending image. Discarding the owner draft schedules safe cleanup of unreferenced temporary/content candidates after the required grace and verification rules; it never deletes content still referenced by another owner.

**Failure/retry**

Unreadable or invalid content adds no image and leaves the rest of the draft intact. File or owner-commit failure shows no success, leaves a saved owner unchanged, exposes no link to missing content, and preserves the owner draft for Retry or Reselect. Orphan-candidate cleanup is internal and cannot be presented as a saved attachment.

## System share drafts

### Shared text

**Entry point**

Android Sharesheet → this app → choose Note draft or a typed Transaction draft (Expense, Income, or Transfer).

**Fields and selections**

For Note, shared text prepopulates the Markdown body. For a Transaction, it prepopulates the approved descriptive field only; account, amount, transaction type requirements, categories where applicable, and date remain explicit typed draft fields.

**Validation**

Shared text is trimmed for the empty-share check. Empty text with no readable shared image produces an empty-share state and no draft fact. Once a destination is chosen, all validation rules of that Note or typed Transaction apply.

**Save/confirm**

Entering from share creates only in-memory draft state. Nothing is saved until the user explicitly confirms the owning Note or Transaction and its complete durable save succeeds.

**Cancel/Back**

Leaving follows the shared dirty-draft rule. Discard returns without saving shared content.

**Failure/retry**

A recoverable save failure preserves the populated text and all completed fields. A failed transaction shows no financial success and creates no partial fact.

### Shared image

**Entry point**

Android Sharesheet → this app → choose Note draft or a typed Transaction draft.

**Fields and selections**

The shared image enters as pending media and follows the Image attachment import rules. The chosen owner editor supplies all other required fields.

**Validation**

The URI must be readable as a valid image. If it is not and no non-empty shared text remains, show the empty-share state and save nothing. The owning draft must independently satisfy its Note or typed Transaction validation.

**Save/confirm**

The image becomes saved only as an AttachmentLink of the confirmed owner inside the owner's complete atomic save. It is never an independent record or orphan attachment.

**Cancel/Back**

Discard removes the unsaved owner draft and leaves no visible image record.

**Failure/retry**

URI permission or read failure keeps any other draft values, identifies the media problem, and offers Reselect when possible. Owner-save failure shows no success and creates neither a partial owner nor a visible orphan image.

## Unified search and type filtering

**Entry point**

Search from any top-level app bar opens one Unified Search screen and retains the launching tab as return context.

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

**Fields and selections**

The user chooses a writable destination. MVP exports all saved transactions; it has no selective record filter. The progress state identifies CSV, not backup.

**Validation**

At least one saved transaction must exist and a writable destination must be confirmed. The exporter validates UTF-8 output, one header row, consistent columns, and one row per saved transaction whose values match the user-visible transaction detail. CSV is an interoperability artifact and never a restore source.

**Save/confirm**

After destination confirmation, stream the export with honest determinate progress when total work is known, otherwise indeterminate phase text. Report success only after the complete destination artifact is closed and verified. CSV is a separate artifact and is not included in the logical backup ZIP.

**Cancel/Back**

Before writing, cancellation returns to Settings. During writing, Cancel requests cooperative cancellation at a safe stream boundary. A cancelled or incomplete destination is deleted best-effort or remains explicitly marked/named incomplete; it is never reported as a completed CSV. Saved app data is unchanged.

**Failure/retry**

Permission loss, space exhaustion, provider failure, or validation failure shows no success and does not expose an incomplete artifact as complete. Preserve the operation kind and offer Retry after a new destination is selected when required.

## Complete logical backup export

**Entry point**

Settings → Data → Create complete backup → Android Storage Access Framework destination confirmation.

**Fields and selections**

The only user selection is the destination. MVP always creates a complete logical ZIP; it does not select individual entities and does not add encryption options. The ZIP contains the versioned JSONL datasets, attachment binaries, and `manifest.json` defined by [data and storage](../architecture/data-and-storage.md). It does **not** contain transaction CSV.

**Validation**

Before publication, validate a consistent logical read, every payload size/hash/count, the manifest, and the complete package. A destination must be writable and sufficient private staging space must be available.

**Save/confirm**

Progress identifies ordered phases such as reading facts, writing datasets, writing attachments, validating, and copying to destination. Build and atomically publish the complete package in app-private storage first. For SAF, report success only after the complete provider copy closes and verifies, with rename used when supported. Only a verified final URI is a successful backup.

**Cancel/Back**

Cancel requests cooperative cancellation before the next safe stream/publication boundary. Temporary private or provider residue remains incomplete and is cleaned best-effort; it is never listed as restorable. Cancellation does not change current app data.

**Failure/retry**

Any read, write, hash, space, permission, provider, or verification failure shows no success and leaves current app data unchanged. Incomplete artifacts remain visibly/internal-state incomplete and cannot be selected from the app's restorable-backup list. Retry may require destination reselection.

## Overwrite restore wizard

Restore is one focused flow with bottom navigation hidden. The UI must never use “partially restored,” “some data restored,” or equivalent success wording. The only successful outcome is a complete replacement that passes generation activation and the startup gate.

### 1. Candidate selection and preflight

**Entry point**

Settings → Data → Restore complete backup opens the Restore Wizard, then Android Storage Access Framework selects a candidate ZIP.

**Fields and selections**

One candidate URI. Preflight may show non-sensitive summary information such as backup creation time, format version, and validated entity/attachment counts only after reading them safely.

**Validation**

Before asking for overwrite confirmation, validate the entire package exactly as required by [data and storage](../architecture/data-and-storage.md): supported `formatVersion`, normalized traversal-safe unique paths, size limits, all SHA-256 values, JSONL counts, required logical content, relationships, image ownership, and enough temporary storage. `databaseSchemaVersion` is provenance, not the compatibility gate. CSV is never accepted as restore input.

**Save/confirm**

Preflight performs no change to the active generation. Only a fully valid candidate advances to confirmation.

**Cancel/Back**

Cancel closes the wizard, releases the candidate, and returns to Settings with current data unchanged.

**Failure/retry**

Unreadable, corrupt, incompatible, unsafe, incomplete, or oversized input is rejected before confirmation. Current data remains unchanged. The failure names the safe category and permits Reselect; it does not offer a “restore anyway” bypass.

### 2. Overwrite confirmation

**Entry point**

Only successful preflight enters confirmation.

**Fields and selections**

The screen identifies that the complete current vault will be replaced as one operation and that the existing generation is retained as the rollback point during activation. It presents the already validated backup summary; no entity-level merge choices exist.

**Validation**

Confirmation remains disabled if the candidate's validation token is stale or the source changed. In that case preflight runs again. Restore cannot start while migration recovery is pending.

**Save/confirm**

One explicit confirmation begins staging import. Confirmation is not restore success.

**Cancel/Back**

Cancel returns to candidate selection or Settings without modifying the active generation.

**Failure/retry**

If the candidate becomes unavailable or preconditions change, return to preflight with current data unchanged.

### 3. Import, validation, and activation progress

**Entry point**

Explicit overwrite confirmation.

**Fields and selections**

No editable fields. Progress exposes truthful phases: staging import, staged invariant validation, activation pending, startup validation, and finalization. It does not expose implementation paths or private values.

**Validation**

The isolated staging generation must pass record counts, foreign keys, `EntityRef` XOR, finance transaction matrix, recomputed balances, typed associations, endpoint ownership, and attachment hashes before activation.

**Save/confirm**

Activation follows the binding generation protocol in [data and storage](../architecture/data-and-storage.md):

1. Import and validate candidate `C` while old generation `O` remains active.
2. Atomically persist `activeGenerationId=C`, `previousGenerationId=O`, `candidateGenerationId=C`, `state=PENDING`.
3. On startup, process migration recovery **before** Room or generation opening. If `migrationRecoveryState=MIGRATION_RECOVERY_PENDING`, normal opening and restore completion remain blocked while its recorded recovery resumes idempotently.
4. Revalidate candidate checksums and domain invariants while activation is `PENDING`.
5. Only successful startup validation may atomically clear `candidateGenerationId` and set `state=HEALTHY` while retaining the previous generation according to policy.

The UI cannot emit Restore Success before step 5 is durably complete.

**Cancel/Back**

Cancellation is accepted only before the activation-record switch and then safely abandons/quarantines staging while `O` remains active. After the atomic switch to `PENDING`, Back/Cancel cannot claim cancellation; the flow must complete startup validation or rollback. The screen explains that recovery is finishing and prevents duplicate restore starts.

**Failure/retry**

- Failure before activation keeps `O` active and marks staging for safe cleanup.
- A crash after the `PENDING` switch is resumed idempotently from the complete activation record.
- Candidate startup-validation failure atomically rolls the activation record back to `O`, clears candidate/previous activation fields as defined by the protocol, then quarantines `C` only after the restored record is durable.
- Cleanup never removes a generation referenced by active, previous, or candidate ID.
- Migration recovery failure keeps `migrationRecoveryState=MIGRATION_RECOVERY_PENDING`, keeps Room closed, and exposes explicit recovery; it never creates an empty database or opens a mixed database/WAL/SHM set.

None of these failures may be described as partial restore success. Retry starts from a fresh preflight only after the original active generation or explicit migration recovery is safely available.

### 4. Terminal success

**Entry point**

The candidate generation has passed startup revalidation and the activation record is durably `HEALTHY`.

**Fields and selections**

No editable fields. The result states that the complete backup was restored and may show validated counts without revealing private content.

**Validation**

Success requires the complete entity, relation, assignment, attachment ownership/hash, and recomputed-balance round trip promised by Journey 11. A merely imported or `PENDING` candidate is not success.

**Save/confirm**

Continue enters Timeline backed by the new active generation. The prior generation remains governed by rollback retention; the UI does not promise immediate deletion.

**Cancel/Back**

The operation is already complete. Back or Continue leaves the result and opens the application; it does not undo restore.

**Failure/retry**

If final state cannot be proven `HEALTHY`, do not render this state. Route to recovery/failure instead.

### 5. Terminal failure and recovery

**Entry point**

Pre-activation failure, completed rollback after candidate startup failure, or explicit pending migration recovery.

**Fields and selections**

No private diagnostic details. Show stable error category, failed phase, whether the original vault is active or recovery is still required, and the next safe action.

**Validation**

Never infer safety from file presence alone. The app reads the atomically persisted activation/recovery record and verifies the active or recovered generation before enabling normal navigation.

**Save/confirm**

After verified rollback, Continue opens the unchanged original vault and may offer a fresh restore attempt. While migration recovery remains pending, normal Room-backed navigation stays blocked and only the explicit recovery/retry path is available.

**Cancel/Back**

Back cannot bypass a pending migration-recovery gate. After verified rollback to the original vault, Back may return to Settings.

**Failure/retry**

Retry is idempotent for the recorded recovery stage. It never loops automatic migration attempts, never activates unvalidated staging, and never offers a merge or partial-acceptance path.

## Interaction acceptance matrix

Future ViewModel and Compose tests must cover at least:

| Interaction | Success assertion | Required failure assertion |
|---|---|---|
| Expense / Income | One committed account effect and exact allocations | No optimistic success; complete draft retained; no partial allocation |
| Transfer | Two distinct same-currency account effects sum to zero | Neither account changes on any failure |
| Note | Body or confirmed image is sufficient; no title required | Prior saved revision unchanged; draft retained |
| Opening balance | Account + opening Transaction + AccountEntry all exist | All three absent after failed save |
| Transaction–note linking | Every selected link navigates from both sides | Prior link set unchanged; no one-sided relation |
| Tags / project | Many tags and at most one project commit together | Prior assignments unchanged |
| Image import | Confirmed owner and image remain available offline | No visible link to missing content; draft retained |
| Share text/image | Only an unsaved typed draft exists before confirm | Leaving saves nothing; no orphan image |
| Search/filter | All/Transaction/Note results dispatch to source | Query/filter retained; partial results not presented as complete |
| CSV export | Complete verified CSV, all transactions exactly once | Cancel/failure never reports incomplete output as complete |
| Logical backup | Complete verified ZIP without CSV | Temporary/provider residue is not restorable |
| Restore | Complete candidate reaches activation `HEALTHY` | Old generation remains/returns active; no partial-success wording; migration gate cannot be bypassed |
