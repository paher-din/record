# Interaction rules

This document is the source of truth for user-visible interaction state, confirmation, cancellation, and failure behavior. Journey outcomes and acceptance criteria remain owned by the [MVP requirements](../product/mvp-requirements.md). Domain invariants are owned by the [domain model](../architecture/domain-model.md), and storage, attachment, backup, migration, and generation-activation protocols are owned by [data and storage](../architecture/data-and-storage.md).

The MVP is a local-first, single-user Android 14+ application. Every interaction below must work in airplane mode or an otherwise network-isolated environment.

## Shared interaction contract

### Compose state model

Every editor or operation exposes one immutable UI state containing, as applicable:

- draft field values and pending selections;
- field-level validation messages;
- exactly one operation phase from the closed set below;
- one stable application error category rather than a raw infrastructure or platform exception;
- whether confirmation is currently allowed;
- progress that is explicitly determinate or indeterminate, never fabricated.

The ViewModel remains the owner of draft state while the flow is in the running app. A recoverable failure preserves every entered field and selected media needed for retry. This does not promise unsaved-draft recovery after force-stop, OS process death, uninstall, or device loss.

### Operation phase model

The operation phase is one of these exact tokens. There are no implicit aliases or unlisted phase substates.

| Phase token | Testable meaning | Primary-action rule | Allowed next phases |
|---|---|---|---|
| `Phase.Loading` | The flow is obtaining an existing record or incoming content required to construct a complete initial state. Partial content is not editable. | Disabled. | `Phase.Idle`, `Phase.Selecting`, `Phase.RecoverableFailure`, `Phase.BlockingFailure` |
| `Phase.Idle` | Complete stable state is available and no selection, check, validation, save, or long operation is active. | Determined by current validation and dirty-state rules. | `Phase.Selecting`, `Phase.Checking`, `Phase.Validating`, `Phase.Saving`, `Phase.Running`, or flow-defined `Phase.Cancelled` |
| `Phase.Selecting` | The flow is waiting for an explicit user or system selection, such as a destination, candidate, image, or typed share target. | Disabled until the required selection is valid; the selection-specific Continue action may then start the listed next phase. | `Phase.Idle`, `Phase.Checking`, `Phase.Validating`, `Phase.Running`, `Phase.Cancelled`, `Phase.RecoverableFailure`, `Phase.BlockingFailure` |
| `Phase.Checking` | The app is performing non-mutating prerequisite, compatibility, or preflight checks. | Disabled except for a flow-defined explicit Cancel. | `Phase.Idle`, `Phase.Selecting`, `Phase.Validating`, `Phase.Running`, `Phase.Cancelling`, `Phase.RecoverableFailure`, `Phase.BlockingFailure` |
| `Phase.Validating` | The app is validating current draft input or selected media before it can be accepted or saved. | Disabled. | `Phase.Idle`, `Phase.Saving`, `Phase.Running`, `Phase.RecoverableFailure`, `Phase.BlockingFailure` |
| `Phase.Saving` | A bounded all-or-nothing record change is being durably saved. | Save/Confirm, Cancel, Discard, app-bar Back, system Back, and predictive-back gesture are disabled or intercepted without navigation. | Exactly one terminal result for the attempt: `Phase.Completed`, `Phase.RecoverableFailure`, or `Phase.BlockingFailure` |
| `Phase.Running` | A search, export, backup, or restore operation is actively progressing after its required input was accepted. | Start/Confirm is disabled; editing or Cancel is available only where a flow explicitly permits it. | `Phase.Running` for a superseding search revision, `Phase.Idle` for a completed repeatable read, otherwise `Phase.Cancelling`, `Phase.Completed`, `Phase.Recovering`, `Phase.RecoverableFailure`, `Phase.BlockingFailure` |
| `Phase.Cancelling` | An explicit cancellation request was accepted and the flow is waiting for the running operation to confirm its actual terminal result. | All navigation and operation actions are disabled. The UI says cancellation is pending, not completed. | `Phase.Cancelled`, `Phase.Completed`, `Phase.RecoverableFailure`, `Phase.BlockingFailure` |
| `Phase.Cancelled` | Cancellation was confirmed and no completed output is offered as success. | Only flow-defined terminal navigation or a Continue/Done action is allowed. | None; exit to the flow-defined return destination. |
| `Phase.Recovering` | The app is resolving an interrupted restore or migration condition before data can be opened safely. | New data operations are disabled; only an explicitly offered recovery action is allowed. | `Phase.Completed`, `Phase.RecoverableFailure`, `Phase.BlockingFailure` |
| `Phase.RecoverableFailure` | The attempt failed, but retained input and verified current data permit correction, Retry, or Reselect. | Enabled only for the explicitly offered safe recovery action. | `Phase.Loading`, `Phase.Idle`, `Phase.Selecting`, `Phase.Checking`, `Phase.Validating`, `Phase.Saving`, `Phase.Running`, `Phase.Recovering` |
| `Phase.BlockingFailure` | The attempt cannot continue until an external condition is remedied or safety recovery completes. | Disabled until remediation; afterward only the explicitly offered safe retry, Reselect, or recovery action is enabled. | `Phase.Loading`, `Phase.Idle`, `Phase.Selecting`, `Phase.Checking`, `Phase.Validating`, `Phase.Saving`, `Phase.Running`, `Phase.Recovering` |
| `Phase.Completed` | The requested success has been fully proven at that flow's durable-success boundary. | Only flow-defined terminal navigation or a Continue/Done action is allowed. | None; exit the flow. A new operation starts with its own declared initial phase. |

Cancel or Back may exit a phase only where that flow's cancellation rules allow it; flow exit is not another phase. Every phase change must be caused by a named UI event or completed operation, and tests must reject any transition not listed above. A flow-specific rule may narrow its allowed next phases or actions, but cannot introduce a new token or widen this table. `Phase.Completed` and `Phase.Cancelled` are distinct terminal outcomes and must never share a user-facing status label.

### Shared entry state

Unless a flow defines an override below, its initial state is determined as follows:

- **New editor:** start in `Phase.Idle` with no persisted record identity. Required fields are empty except an occurrence date/time defaulted from an explicit launch context or, when no context supplies one, the current local date/time. Optional text, relations, assignments, and media start empty. A launch context may prefill only fields it owns, such as the selected account or timeline day; state from another abandoned draft is never copied implicitly.
- **Existing-record editor:** start in `Phase.Loading` with the primary action disabled. Load the complete last-saved record before presenting an editable `Phase.Idle` state. A partial load is never rendered as an empty field. Load failure moves to the applicable failure phase, keeps editing disabled, and offers only its allowed Retry or Back action.
- **Selection subflow:** start from the parent draft's current selections, not from defaults. Returning from a picker, preview, or linked-record screen restores the unchanged parent draft plus any explicit new selection.
- **Read or operation flow:** start in `Phase.Loading`, `Phase.Checking`, or `Phase.Selecting` as named by that flow, with no success state and with its primary action disabled until all required input and preconditions are available.
- **Primary action:** Save, Confirm, Continue, Search, or Export begins disabled while required data is loading, while local validation fails, or while another attempt is running. It becomes enabled only when the current complete input is valid. An edit may additionally keep Save disabled until the draft differs from the last-saved state.
- **Draft restoration:** navigation within the running flow, configuration recreation, and any recoverable failure restore all entered fields and selected media. Explicit Discard clears that draft. Opening a fresh create route after discard starts from new-editor defaults. Recovery after force-stop, OS process death, uninstall, or device loss is not promised by MVP.

Each per-flow **Entry-state override** below changes only the named defaults. All other shared entry-state rules continue to apply.

### Validation and durable-save boundary

- Trim text for validation without silently replacing the user's visible draft before confirmation.
- Field and business validation happens before a save attempt and points to the field or selection that needs correction.
- A valid editor confirmation moves `Phase.Idle` to `Phase.Saving`. Durable success moves to `Phase.Completed`; failure moves to the applicable declared failure phase with the complete draft restored.
- While a save is running, duplicate confirmation is disabled. System Back does not turn an in-flight write into a success or start a second write.
- A financial success state is shown only after the complete financial change is durably saved. No balance, history, timeline, or statistics UI may optimistically claim a successful financial write.
- A note or link success state is shown only after its complete requested change is durably saved. An edit failure leaves the prior saved version unchanged.
- Recoverable I/O failure keeps the draft and offers Retry or, when an external URI is no longer usable, Reselect.
- Space exhaustion, permission loss, corrupt input, and incompatible backup are blocking for the current attempt and explain the next safe action. They are not blindly retried.

### Cancel and Back

- In `Phase.Saving`, app-bar Back, system Back, predictive-back gesture, Cancel, and Discard all remain on the editor and do not request cancellation. The UI waits for the single save result. Success enters `Phase.Completed`; failure restores the complete draft in the declared failure phase. A save that has started can never display “cancelled” and then later surface as successful.
- The remaining draft cancellation rules in this section apply only outside `Phase.Saving`.
- Cancel or System Back from a pristine draft returns to the launch context without writing.
- Cancel or System Back from a dirty draft asks the user to continue editing or discard the unsaved draft. Discard performs no domain write and removes only temporary, unconfirmed media owned by that draft.
- Back from a picker that returned no selection restores the unchanged draft.
- Back from a progress screen never implicitly requests cancellation. A flow either permits Back as an ordinary navigation action or blocks it. When cancellation is supported, only that flow's explicit Cancel control may request it and must wait for the actual terminal acknowledgement.

## Typed creation dispatch

The global `+` chooser and feature-local typed actions all dispatch to one of four explicit drafts. There is no generic Entry draft.

| Choice | Draft owner | Required core selection | Categories | Result type |
|---|---|---|---|---|
| Expense | Finance | Account and positive amount | One or more allocations | `EXPENSE` transaction |
| Income | Finance | Account and positive amount | One or more allocations | `INCOME` transaction |
| Transfer | Finance | Distinct source and destination accounts in the same currency, and positive amount | Forbidden | `TRANSFER` transaction |
| Note | Notes | Non-empty trimmed Markdown body or at least one confirmed image | Not applicable | `Note` |

### Deterministic finance post-save navigation

The following outcomes apply only after `Phase.Completed`. An Editor-context relation, assignment, image, picker, or other child subflow never applies this table; it returns to its parent draft with that draft's navigation context intact. A saved Detail relationship subflow may complete independently but returns to that same Detail.

| Successful operation | Required destination | Back behavior |
|---|---|---|
| Create Expense | Finance-owned `TransactionDetail` for the new expense | Return to the exact launch context with its retained tab, scroll, filters, and date |
| Create Income | Finance-owned `TransactionDetail` for the new income | Return to the exact launch context with its retained tab, scroll, filters, and date |
| Create Transfer | Finance-owned `TransactionDetail` for the new transfer | Return to the exact launch context with its retained tab, scroll, filters, and date |
| Edit Expense, Income, or Transfer | The source Finance-owned `TransactionDetail` | Preserve that detail's existing origin so its subsequent Back remains deterministic |
| Create Account with opening balance | Finance-owned `AccountDetail` for the new account | Return to the exact Finance launch context with its retained state |

Navigation must not occur before durable success. A failed save remains in the editor with its complete draft. Returning from an Editor child subflow must not jump to a top-level destination or a newly synthesized detail route; a saved Detail relationship subflow returns only to its source Detail.

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

Confirm saves the transaction, its complete category allocations, requested links/assignments, and confirmed images as one all-or-nothing change. The editor enters `Phase.Saving` and retains the draft. Only after durable save may it clear the draft, show success, update the balance, and follow the new-versus-edit destination in the deterministic finance post-save navigation table.

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

Confirm saves both equal-and-opposite account effects and every requested link/assignment/image as one all-or-nothing transfer. Success appears only after both account histories and balances durably reflect the zero-sum transfer, then follows the new-versus-edit destination in the deterministic finance post-save navigation table.

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

A new note starts in `Phase.Idle` with blank Markdown, no confirmed image, and the launch-context or current date/time; Save is initially disabled. It becomes enabled when occurrence time is valid and either trimmed Markdown is non-empty or at least one image is confirmed. Edit starts in `Phase.Loading` and does not expose an editable empty note while the saved body, images, assignments, and transaction links are being loaded.

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

The combined editor starts in `Phase.Idle` with blank account name, no currency, blank opening balance, and no captured opening time. It never starts from another account's values. Create is initially disabled and becomes enabled only when name, currency, and opening balance are valid. Returning from currency selection restores name and balance unchanged.

**Fields and selections**

- required trimmed account name;
- required currency;
- required opening balance valid in that currency;
- no visible or editable opening date/time field in MVP.

The displayed balance is not directly persisted as an editable account field. It is input to an `OPENING_BALANCE` transaction.

**Validation**

Name must remain non-blank after trimming. Currency must be selected. Opening balance must be parseable and representable in integer minor units for that currency. A rejected value creates nothing.

**Save/confirm**

The first valid Create press captures the device's current local date-time into the draft as the opening transaction's effective time, then enters `Phase.Saving`. Confirm saves the account and its opening-balance history as one all-or-nothing change. Success is shown only when the account appears with the requested balance and that balance is recomputable from the visible history, then navigation opens the new Finance-owned `AccountDetail` as defined in the deterministic finance post-save navigation table.

**Cancel/Back**

Before `Phase.Saving`, use the shared dirty-draft rule. Cancellation creates neither account nor transaction. Explicitly discarding the draft also clears any captured opening time; only a later fresh account draft captures a new time on its first valid Create press.

**Failure/retry**

Any failure leaves no partial account or opening transaction and shows no success. The complete draft retains name, currency, amount, and the opening time captured by the first Create press. Retry reuses that same captured time rather than reading the device clock again; only discard followed by a new draft causes recapture.

## Transaction–note linking from either side

**Entry point**

- A saved Transaction or Note **Detail** → edit links: **saved Detail context**.
- Any Transaction or Note **Editor**, whether editing an existing record or creating a new one → edit the parent draft's links: **Editor context**.

Both entry points edit the same typed transaction–note link; neither creates a generic relation or relation type.

**Fields and selections**

The interaction context and source type are fixed for the subflow. The user selects zero or more active saved records of the opposite type. Saved Detail context starts from the complete saved link set; Editor context starts from the parent draft's current link selection.

**Validation**

At confirmation, every selected target must still exist and match the expected Transaction/Note endpoint type. A saved Detail source must also still exist. Duplicate pairs collapse to one link. If any requested endpoint is unavailable, the whole proposed link set is invalid.

### Saved Detail context

**Entry state**

Start in `Phase.Loading` with Confirm disabled while loading the fixed saved source and its complete saved link set. Enter `Phase.Idle` only after both are known. Confirm remains disabled until the proposed set differs from the saved set and every target is valid. A load failure enters the applicable failure phase and never presents an empty set as though all links were removed.

**Confirm**

Confirm enters `Phase.Saving` and replaces the saved source's complete transaction–note link set as one all-or-nothing change. `Phase.Completed` is reached only after every requested link is visible from both sides. The subflow returns to the exact saved Detail and never jumps to an Editor or top-level destination.

**Failure/retry**

Validation or save failure leaves the entire prior saved link set unchanged and creates no orphan or one-sided association. The applicable failure phase retains the proposed selection for correction or Retry, except unavailable targets are clearly marked and must be resolved first.

### Any Editor context

**Entry state**

Start in `Phase.Idle` from the parent draft's current selected opposite records. An existing-record Editor has already loaded its complete saved links into that draft; a new Editor starts from its new-draft selection. Opening the selector writes no relationship. Done is enabled only when every selected target is valid and the proposed set differs from the parent draft's starting set.

**Confirm**

Done performs no durable save. It returns the validated selection to the same parent Editor and preserves every other parent field and media selection. For an existing-record Editor, parent Save makes the edited owner and its complete requested relationship set visible together. For a new Editor, parent Save makes the new owner and its complete requested relationship set visible together. The internal identity and relationship invariants remain owned by the [domain model](../architecture/domain-model.md) and [data and storage](../architecture/data-and-storage.md).

**Failure/retry**

Selection validation failure remains in the selector with the parent draft unchanged. If parent Save fails, no requested owner edit or relationship change becomes visible; the complete parent draft and selected records return in the applicable failure phase for Retry. Discarding an existing-record Editor leaves the prior saved owner and links unchanged. Discarding a new Editor makes neither owner nor links visible.

**Cancel/Back**

Before any `Phase.Saving`, Cancel or Back discards only the subflow's proposed changes and returns to the exact parent context. Saved Detail links remain unchanged; an Editor regains its starting draft selection and all other draft fields. The `Phase.Saving` Back/Cancel prohibition applies only after saved Detail confirmation or parent Save has begun.

## Shared tags and primary project assignment

**Entry point**

- A saved Transaction or Note **Detail** → edit tags/project: **saved Detail context**.
- Any Transaction or Note **Editor**, including an existing-record Editor and a new Editor → edit the parent draft's tags/project: **Editor context**.

**Fields and selections**

- zero or more active shared tags;
- zero or one active primary project.

Tags apply only to Transaction and Note endpoints. A project itself cannot receive MVP tags or attachments. The project selection is a primary context, not an arbitrary relation.

**Validation**

Every selected tag and the optional project must still exist and be available at confirmation. More than one project cannot be selected. The owning entity must be a Transaction or Note.

### Saved Detail assignment context

**Entry state**

Start in `Phase.Loading` with Confirm disabled until the fixed saved source, complete saved tag set, saved primary project, and available choices are loaded. Then enter `Phase.Idle`; Confirm remains disabled until the proposed assignments are valid and differ from the saved set. Load failure retains the last known assignments and never renders them as unassigned.

**Confirm**

Confirm enters `Phase.Saving` and replaces the saved source's requested tag set and primary-project assignment as one all-or-nothing change. `Phase.Completed` is reached only when every selected tag and the zero-or-one selected project are visible together on the saved Detail and relevant read views. The subflow returns to that same Detail.

**Failure/retry**

Validation or save failure leaves the entire prior saved tag/project assignment unchanged. The applicable failure phase retains the proposed selections for correction or Retry, with unavailable choices marked rather than silently removed.

### Any Editor assignment context

**Entry state**

Start in `Phase.Idle` from the parent draft's current tags and primary project. An existing-record Editor has already loaded its complete saved assignments into that draft; a new Editor starts from its new-draft assignments. Opening the selector writes no assignment.

**Confirm**

Done performs no durable save. It returns the validated tag set and zero-or-one project to the same parent Editor with every other field, link, and media selection intact. For an existing-record Editor, parent Save makes the edited owner and all requested assignment/link changes visible together. For a new Editor, parent Save makes the new owner and all requested assignments/links visible together. The internal relationship invariants remain owned by the [domain model](../architecture/domain-model.md) and [data and storage](../architecture/data-and-storage.md).

**Failure/retry**

Selection validation failure remains in the selector and does not change the parent draft. If parent Save fails, no requested owner edit, tag change, or project change becomes visible; the complete parent draft and selections remain available for Retry. Discarding an existing-record Editor leaves all prior saved assignments unchanged. Discarding a new Editor makes neither owner nor assignments visible.

**Cancel/Back**

Before `Phase.Saving`, Cancel or Back restores the saved Detail's prior assignments or the Editor's prior draft selections and returns to that exact context. During saved Detail confirmation or parent Save, the shared `Phase.Saving` navigation lock applies.

## Image attachment import

**Entry point**

A Transaction or Note editor invokes Android Photo Picker (or the explicitly supported system picker fallback). Share-image drafts enter the same pending-media pipeline after the share entry has supplied a URI.

**Entry-state override**

Entry preserves the complete parent draft and starts in `Phase.Selecting` with no new image and no attachment success. After a selection, the preview enters `Phase.Validating`; accepted validation returns to `Phase.Idle` with Add/Confirm governed by the draft rules. Returning from preview restores the parent draft and only the explicitly confirmed image. Reopening import after removal starts in `Phase.Selecting` with no pending selection rather than reviving the removed image.

**Fields and selections**

The user chooses an image, reviews a pending preview, and either confirms it into the owner draft or removes/reselects it. The UI never exposes an internal storage location.

**Validation**

No picker result means no attachment change. A selected item must be readable, satisfy size limits, be an accepted image type, and be decodable. All required image validation must complete before the image is marked confirmed in the draft.

**Save/confirm**

Confirming the preview only adds the image to the parent Editor draft; it does not save an independent attachment. The image becomes durably visible only when the complete owning Transaction or Note saves successfully. The required storage reliability behavior is defined by [data and storage](../architecture/data-and-storage.md).

**Cancel/Back**

Back from the picker or pending preview leaves the owner draft unchanged unless the user explicitly removes a pending image. Discarding the owner draft saves no new image and never removes an image already used by a saved record.

**Failure/retry**

Unreadable or invalid content adds no image and leaves the rest of the draft intact. Import or parent-save failure shows no success, leaves the prior saved record unchanged, exposes no missing image as an attachment, and preserves the parent draft for Retry or Reselect.

## System share drafts

**Shared entry-state override**

The share flow starts in `Phase.Loading` while the app determines whether the incoming content contains usable text, an image, or both. No saved record, destination type, or success state exists at entry. After readable content is available, the flow enters `Phase.Selecting` with Note/Expense/Income/Transfer unselected and Continue disabled until the user chooses one. The chosen typed editor then enters `Phase.Idle` as a new draft, prefilled only from the incoming content as defined below; all other fields use that editor's new-entry defaults. Returning from the chosen editor within the running flow restores the share-populated draft. Explicit Discard or an empty-share result leaves nothing to restore.

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

The shared image enters as pending media in `Phase.Validating` and follows the Image attachment import rules. Accepted validation returns to the chosen editor's `Phase.Idle`; its primary action remains disabled until the image and all owner fields are valid.

**Validation**

The URI must be readable as a valid image. If it is not and no non-empty shared text remains, show the empty-share state and save nothing. The owning draft must independently satisfy its Note or typed Transaction validation.

**Save/confirm**

The image becomes saved only as part of the confirmed owner's complete all-or-nothing save. It is never an independent record or visible orphan attachment.

**Cancel/Back**

Discard removes the new parent draft and leaves no visible image record.

**Failure/retry**

URI permission or read failure keeps any other draft values, identifies the media problem, and offers Reselect when possible. Owner-save failure shows no success and creates neither a partial owner nor a visible orphan image.

## App Lock

App Lock is optional UI access control and defaults to off. It uses Android `BiometricPrompt` with the device's supported biometric or device credential capability; the app never creates, stores, resets, or recovers its own password. App Lock does not claim database or backup encryption.

### Privacy & Security setting

**Entry point**

Settings → Privacy & Security.

**Entry-state override**

The screen starts in `Phase.Loading` with the toggle disabled while reading the enabled state and platform-authentication capability. It enters `Phase.Idle` with App Lock off for a fresh installation. No enable/disable success is shown until the platform authentication result is known.

**Fields and selections**

One App Lock toggle plus a non-sensitive capability/status explanation. There is no password, PIN, grace-period, timeout, or custom recovery field.

**Validation**

Enable is available only when Android reports an eligible biometric or device credential authenticator. Enabling or disabling requires a fresh platform-authentication result; a cached app session cannot silently change the setting.

**Save/confirm**

Changing the toggle enters `Phase.Checking` and invokes the platform prompt. Successful authentication durably changes the setting and returns to `Phase.Idle` with the actual enabled state. Enabling immediately activates secure-window protection and the next required lock boundary. Disabling removes App Lock only after successful authentication; it does not alter the vault, backups, diagnostics, or system recovery state.

**Cancel/Back**

Cancelling or dismissing the platform prompt enters `Phase.RecoverableFailure`, preserves the prior enabled state, and offers Retry or return to `Phase.Idle`. Back outside a prompt returns to Settings without changing the setting.

**Failure/retry**

Authentication failure preserves the prior setting and stays in `Phase.RecoverableFailure`; platform lockout or loss of an eligible authenticator is `Phase.BlockingFailure` with only the platform remediation and Retry actions. The app never offers a bypass or custom-password fallback.

### Lock and unlock route

**Entry point**

When App Lock is enabled, every cold start and every background-to-foreground transition immediately enters `AppLockRoute`. There is no grace period. Before the prior activity frame can be displayed, the application applies reliable secure-window protection so screen content and the recent-task preview cannot reveal user data.

The technical migration/restore recovery gate is evaluated before Room initialization. If it is pending while authentication is absent, the app may render only `LockedRecoverySurface`: a generic, data-free statement that protected recovery is in progress or requires attention. It cannot show record counts, filenames, paths, diagnostics, failure details, terminal outcomes, or any previous user-data frame.

**Entry-state override**

The route starts in `Phase.Checking` while requesting Android authentication. It contains no draft, vault data, diagnostic envelope, navigation state, or prior-screen preview. If a generic recovery condition exists, only its boolean presence affects the generic surface.

**Fields and selections**

There are no app-owned credential fields. The only actions are the platform authentication prompt and Retry when allowed.

**Validation**

Only a current successful `BiometricPrompt`/device-credential result authenticates the foreground session. A stale success from a prior foreground session is invalid after any background transition.

**Save/confirm**

Authentication success enters `Phase.Completed`. If the already-evaluated recovery gate is healthy, continue to the shell. If recovery is pending or has a protected terminal outcome, continue to the authenticated detailed recovery route; unresolved recovery still cannot enter the shell.

**Cancel/Back**

Prompt cancellation or authentication failure enters `Phase.RecoverableFailure` and remains on the lock route with Retry. App-bar Back is absent; system/predictive Back may only background or exit the app and can never reveal the shell or prior frame.

**Failure/retry**

Platform lockout or unavailable credentials enter `Phase.BlockingFailure`. Retry is enabled only when the platform permits another authentication attempt. No diagnostic export, restore detail, user data, Settings route, or “continue anyway” action is available before authentication.

## Unified search and type filtering

**Entry point**

Search from any top-level app bar opens one Unified Search screen and retains the launching tab as return context.

**Entry-state override**

A fresh Search screen starts in `Phase.Idle` with blank query, All selected, no current request revision, no result claim, and the Search trigger disabled. Returning from a source detail restores the last completed query, filter, results, and list position in `Phase.Idle`. Every query-text or type-filter change creates a newer UI-scoped request revision and makes it the only current revision. A trimmed non-empty current revision enters `Phase.Running`; another edit while running supersedes it through the allowed `Phase.Running` self-transition. Query and filter remain editable throughout `Phase.Running`.

**Fields and selections**

- query text;
- exactly one type filter: All, Transactions, or Notes.

All is the default for a new search. Query and filter are retained when opening a result and returning.

**Validation**

Only a trimmed non-empty query executes. An empty query invalidates the current request revision, enters `Phase.Idle`, shows the search prompt, retains the active filter, and does not label any content as results. Only results carrying the still-current query/filter revision are valid for display or selection.

**Save/confirm**

Search performs no domain save. A completion may replace the visible result set and return to `Phase.Idle` only when its revision is still current. Results or errors from any superseded revision are ignored and cannot change loading, empty, result, or error state. All combines matching transactions and notes; a typed filter shows only that type. Only a result from the current completed revision can open its Finance- or Notes-owned source detail.

**Cancel/Back**

Clearing the query invalidates the current revision and returns to the prompt while retaining the filter. Back, app-bar close, and leaving Search for any non-result route invalidate the current revision before returning to the launching tab with its scroll/filter/date state intact. Completion after invalidation cannot update visible Search state. Opening a current result is the exception: it preserves the completed revision and restores it on Back from source detail.

**Failure/retry**

Failure for the current revision enters `Phase.RecoverableFailure`, remains distinct from zero results, preserves query/filter, and offers Retry for a newer revision. Do not present a partial page or previous revision as the current complete result set. A failure from a superseded or invalidated revision is ignored exactly like stale results and never replaces current visible state.

## Transaction CSV export

**Entry point**

Settings → Data → Export transactions as CSV → Android Storage Access Framework destination confirmation.

**Entry-state override**

Entry starts in `Phase.Checking` with CSV fixed as the operation kind, no destination, no progress, and Export disabled. After the app confirms that at least one transaction can be exported, it enters `Phase.Selecting`; Export becomes enabled only after the user confirms a writable destination. Starting export opens the modal full-screen `CsvExportProgress` route in `Phase.Running`. Returning from the destination picker without a selection restores `Phase.Selecting` and never creates a success state. Retry retains the CSV operation kind but requires reselection when the prior destination is no longer writable.

**Fields and selections**

The user chooses a writable destination. MVP exports all saved transactions; it has no selective record filter. The progress state identifies CSV, not backup.

**Validation**

At least one saved transaction must exist and a writable destination must be confirmed. The exporter validates UTF-8 output, one header row, consistent columns, and one row per saved transaction whose values match the user-visible transaction detail. CSV is an interoperability artifact and never a restore source.

**Save/confirm**

The modal progress route shows honest determinate progress when total work is known, otherwise indeterminate phase text. `Phase.Completed` is reached only after the complete destination artifact is available and verified. CSV is a separate artifact and is not included in the logical backup ZIP. Completion closes the modal and returns to Settings → Export with a clear Success result and the completed destination.

**Cancel/Back**

Before export starts, cancellation returns to Settings → Export. In `Phase.Running`, bottom navigation is hidden or disabled, app-bar Back, system Back, predictive-back gesture, and outside/modal dismissal cannot leave the progress route; explicit Cancel is the only exit request. Pressing Cancel enters `Phase.Cancelling`, disables Cancel, and waits for the operation's actual terminal acknowledgement. A confirmed cancellation enters `Phase.Cancelled`, offers no completed CSV, closes the modal, and returns to Settings → Export with a clear Cancelled result. If completion won the race before cancellation was acknowledged, the actual result is `Phase.Completed` and Settings → Export shows Success; the UI never claims cancellation early.

**Failure/retry**

Permission loss, space exhaustion, destination failure, or validation failure shows no success and does not expose an incomplete artifact as complete. After the terminal failure is known, close the modal and return to Settings → Export with a clear Failed result, preserved operation kind, and Retry; require destination reselection when the prior destination is no longer writable. Saved app data remains unchanged.

## Complete logical backup export

**Entry point**

Settings → Backup → Create complete backup → Android Storage Access Framework destination confirmation.

**Entry-state override**

Entry starts in `Phase.Checking` with Complete Backup fixed as the operation kind, no destination, no progress, and Create Backup disabled. It moves to `Phase.Selecting` only when backup prerequisites are available, and the primary action becomes enabled only after a writable destination is confirmed. Starting backup opens the modal full-screen `BackupExportProgress` route in `Phase.Running`. Returning from destination selection restores the operation kind but not an unconfirmed destination.

**Fields and selections**

The only user selection is the destination. MVP always creates a complete logical ZIP; it does not select individual entities and does not add encryption options. Its complete contents are defined by [data and storage](../architecture/data-and-storage.md). It does **not** contain transaction CSV.

**Validation**

The destination must be writable, enough working space must be available, and the complete backup must pass all integrity and completeness checks defined by [data and storage](../architecture/data-and-storage.md) before success can be reported.

**Save/confirm**

The modal progress route identifies user-meaningful phases such as collecting records and images, validating the backup, and writing the selected destination. `Phase.Completed` is reached only when the complete backup is verified and available at the selected destination. Completion closes the modal and returns to Settings → Backup with a clear Success result and the completed destination.

**Cancel/Back**

Before backup starts, cancellation returns to Settings → Backup. In `Phase.Running`, bottom navigation is hidden or disabled, app-bar Back, system Back, predictive-back gesture, and outside/modal dismissal cannot leave the progress route; explicit Cancel is the only exit request. Pressing Cancel enters `Phase.Cancelling`, disables Cancel, and waits for the operation's actual terminal acknowledgement. Confirmed cancellation enters `Phase.Cancelled`, offers no restorable backup, closes the modal, and returns to Settings → Backup with a clear Cancelled result. If completion won the race before cancellation was acknowledged, the actual result is `Phase.Completed` and Settings → Backup shows Success; the UI never claims cancellation early.

**Failure/retry**

Any read, write, space, permission, destination, or verification failure shows no success and leaves current app data unchanged. After the terminal failure is known, close the modal and return to Settings → Backup with a clear Failed result and Retry. Retry keeps Complete Backup as the operation kind, reruns prerequisites, and requires destination reselection only when the prior destination is no longer writable. An incomplete result cannot be selected from the app's restorable-backup list.

## Diagnostic export

**Entry point**

Settings → Diagnostics. The route is available only after App Lock authentication when App Lock is enabled. An authenticated detailed recovery route may expose the same action when safe; `LockedRecoverySurface` cannot.

**Entry-state override**

Diagnostics starts in `Phase.Loading` while reading the redacted ring-buffer envelope, then enters `Phase.Idle`. The stable screen shows only time range, event count, estimated size, retention rule, and the latest terminal export result. It never renders event payload, user content, identifiers, paths, or raw exception text. Export starts disabled until the envelope is available and non-empty.

**Fields and selections**

The user may clear diagnostics or choose **Export diagnostics**. Export enters `Phase.Selecting` and uses Android Storage Access Framework to choose one destination. The selected artifact is only the sanitized diagnostic envelope and allowed redacted events; the user cannot add the vault, database, attachments, backup, CSV, screenshots, or arbitrary files.

**Validation**

The current diagnostics set must be non-empty, the destination must be writable, and the export must pass the security-owned redaction/allowlist check before it can complete. Summary values remain non-sensitive and do not expose individual events.

**Save/confirm**

After explicit destination confirmation, open full-screen modal `DiagnosticExportProgress` in `Phase.Running`. Progress is truthful and contains no event payload. `Phase.Completed` is reached only after the complete sanitized artifact is verified at the destination, then the modal returns Success to Diagnostics. Export never modifies the vault and never automatically shares or uploads the artifact.

**Cancel/Back**

Before export starts, Back or Cancel returns to Diagnostics unchanged. During `Phase.Running`, bottom navigation, app-bar/system/predictive Back, and outside dismissal are blocked. Explicit Cancel enters `Phase.Cancelling` and waits for the actual terminal result. Confirmed cancellation enters `Phase.Cancelled` and returns Cancelled to Diagnostics; if completion wins the race, the actual result is Success.

**Failure/retry**

Permission, space, destination, write, or redaction verification failure returns Failed to Diagnostics, leaves the vault and retained ring buffer unchanged, exposes no incomplete artifact as complete, and offers Retry. Destination reselection is required only when the previous destination is unusable. Retry never uploads or shares automatically.

## Overwrite restore wizard

Restore is one focused flow with bottom navigation hidden. The UI must never use “partially restored,” “some data restored,” or equivalent success wording. The only successful outcome is a complete replacement that passes every preflight, activation, recovery, and post-restart validation gate defined by [data and storage](../architecture/data-and-storage.md).

**Entry-state override**

A new user-initiated restore starts in `Phase.Selecting` with no candidate, no summary, no progress, no success state, and Continue disabled. Selecting a candidate enters `Phase.Checking`; successful preflight returns to `Phase.Idle` with overwrite confirmation governed by the rules below. A pending verification or recovery never enters this fresh-candidate state; app startup routes it through the blocked recovery gate below.

### 0. App-start recovery gate

**Entry point**

Every app startup evaluates the architecture-owned migration-recovery gate first and then any generation-activation verification or restore recovery required by [data and storage](../architecture/data-and-storage.md), all before Room initialization. If none is pending, the App Lock access decision runs before the normal shell may open. If recovery is pending and App Lock is off or already authenticated, navigation enters `RestoreRecoveryRoute`. If App Lock is enabled and unauthenticated, the app renders only `LockedRecoverySurface` until authentication; the technical recovery coordinator may continue safely, but protected details and terminal routing wait behind authentication.

**Entry state**

After any required App Lock authentication, `RestoreRecoveryRoute` starts in `Phase.Recovering` while safe verification or recovery is progressing, or `Phase.BlockingFailure` when progress requires explicit user action. Bottom navigation and all normal shell destinations are absent. The screen exposes only a non-sensitive status, the current user-meaningful phase, and actions that are safe for that verified state. Before authentication, only the generic locked recovery surface is allowed.

**Fields and selections**

There are no editable restore fields and no candidate picker. After authentication, the only optional user choices are an enabled Retry action and an explicit, user-initiated export of a sanitized diagnostic report when the failure state permits it. Before authentication, neither diagnostics nor terminal details are available.

**Validation**

The route cannot infer success from a previous progress screen or partially completed work. It follows the migration-before-activation ordering and complete-vault safety gates owned by [data and storage](../architecture/data-and-storage.md).

**Save/confirm**

- Successful verification of the replacement enters the existing terminal-success state in `Phase.Completed`.
- Successful rollback/recovery to the unchanged original vault enters the terminal-failure state in `Phase.RecoverableFailure`, clearly states that restore failed, and enables Continue to the verified original data.
- An unresolved or failed recovery remains on `RestoreRecoveryRoute` in `Phase.Recovering` or `Phase.BlockingFailure`; it never opens ordinary shell content.

**Cancel/Back**

Back, predictive Back, and app-bar navigation cannot bypass this route into the shell. They may only leave/background the app according to platform behavior, which immediately requires App Lock again when enabled. A new restore, global create, Search, Settings, and bottom navigation remain unavailable until one complete vault is verified safe.

**Failure/retry**

Retry continues only the architecture-approved verification/recovery path. Repeated failure remains blocking and keeps Retry and the sanitized diagnostic outlet available when safe. It never offers “continue anyway,” partial acceptance, or a fresh candidate selection while recovery is unresolved.

### 1. Candidate selection and preflight

**Entry point**

Settings → Restore → Select complete backup opens the Restore Wizard, then Android Storage Access Framework selects a candidate ZIP.

**Fields and selections**

One selected backup. Preflight may show non-sensitive summary information such as backup creation time, compatibility, and validated record/image counts only after reading them safely.

**Validation**

Before asking for overwrite confirmation, validate the entire package, its compatibility, completeness, integrity, relationships, image ownership, resulting balances, and required working-space availability according to [data and storage](../architecture/data-and-storage.md). CSV is never accepted as restore input.

**Save/confirm**

Preflight does not change current data. Only a fully valid candidate enables Continue and advances to confirmation.

**Cancel/Back**

Before preflight starts in `Phase.Selecting`, explicit Cancel enters `Phase.Cancelled` and returns to Settings → Restore with a clear Cancelled result. During `Phase.Checking`, explicit Cancel enters `Phase.Cancelling`; Back, gestures, and bottom navigation do not leave while the check acknowledges cancellation. Confirmed cancellation enters `Phase.Cancelled` and returns to Settings → Restore. Current data remains unchanged, and no restore confirmation is shown after cancellation was accepted.

**Failure/retry**

Unreadable, corrupt, incompatible, unsafe, incomplete, or oversized input is rejected before confirmation. Current data remains unchanged. The failure names the safe category and permits Reselect; it does not offer a “restore anyway” bypass.

### 2. Overwrite confirmation

**Entry point**

Only successful preflight enters confirmation.

**Entry-state override**

The confirmation screen starts in `Phase.Idle` with Confirm Restore disabled until the complete validated summary is present and the user acknowledges that all current data will be replaced. If validation is no longer current, the flow returns to `Phase.Checking` and disables confirmation until preflight succeeds again. This screen is the final cancellable point.

**Fields and selections**

The screen identifies that the complete current vault will be replaced as one all-or-nothing operation and that the existing vault remains the recovery point until the replacement is proven safe. It presents the already validated backup summary; no entity-level merge choices exist.

**Validation**

Confirmation remains disabled if the candidate changed or its prior validation is no longer current. In that case preflight runs again. Restore cannot start while an earlier restore or migration recovery is unresolved.

**Save/confirm**

One explicit final Confirm enters `Phase.Running` and begins the complete restore commit/recovery gate. Confirmation is not restore success, but from this event onward the operation is non-cancellable until startup validation produces complete success or complete failure/rollback.

**Cancel/Back**

Before final Confirm, explicit Cancel enters `Phase.Cancelled` and returns to Settings → Restore with a clear Cancelled result and current data unchanged. After final Confirm, this rule no longer applies.

**Failure/retry**

If the candidate becomes unavailable or preconditions change, return to preflight with current data unchanged and Confirm Restore disabled.

### 3. Restore progress

**Entry point**

Explicit overwrite confirmation.

**Entry-state override**

Progress starts in `Phase.Running` immediately after final Confirm, with duplicate confirmation disabled and no success state. Cancel is absent/disabled from the first progress frame. App-bar Back, system Back, predictive-back gesture, bottom navigation, and outside dismissal cannot leave the flow. If startup or interruption requires the blocked recovery route, the operation enters `Phase.Recovering` there. The lock remains until startup validation produces `Phase.Completed` or a verified failure/rollback outcome.

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

No cancellation is accepted after final Confirm. Back/Cancel cannot claim cancellation or return to Settings; bottom navigation remains unavailable. The flow must finish startup validation or recovery and then route to terminal success or terminal failure/rollback. The screen explains that verification/recovery is required and prevents duplicate restore starts.

**Failure/retry**

- Failure before replacement leaves the original vault unchanged.
- Interruption during replacement resumes the architecture-defined recovery before normal data use.
- Failure during startup verification returns to the original vault as one complete recovery outcome.
- While safe recovery is unresolved, normal data navigation and new restore attempts remain blocked rather than opening uncertain data.
- Cleanup or diagnostics never become a user-visible success substitute.

None of these failures may be described as cancellation or partial restore success. Retry starts from Settings → Restore with a fresh preflight only after the original vault or completed replacement is verified safe.

### 4. Terminal success

**Entry point**

The complete replacement has passed startup revalidation and the app has verified it is safe for normal use.

**Entry-state override**

This state can only be entered from completed verification. It starts in `Phase.Completed` with Continue enabled, no Retry action, and no editable restore input. It is never restored from an unverified progress snapshot.

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

Entry starts in `Phase.RecoverableFailure`, `Phase.BlockingFailure`, or `Phase.Recovering` according to the verified outcome. Retry is enabled only when current data is known safe or the architecture-owned blocked recovery can safely continue. Back is enabled only when normal navigation is safe. While recovery is unresolved, `RestoreRecoveryRoute` continues to block both a new restore and normal shell navigation; only the explicit Retry, status, or sanitized diagnostic action is available.

**Fields and selections**

No private diagnostic details. Show a stable error category, failed phase, whether the original vault is available or recovery is still required, and the next safe action. Diagnostics are available only through an explicit user action and follow the project's sanitized-diagnostics boundary.

**Validation**

Normal navigation is enabled only after the architecture-owned recovery process verifies a complete safe vault. The UI never infers safety from an interrupted progress indicator or the presence of a selected backup.

**Save/confirm**

After a verified return to the original vault, Continue opens that unchanged vault and may offer a fresh restore attempt. While restore or migration recovery remains unresolved, normal data navigation stays blocked and only the explicit recovery path is available.

**Cancel/Back**

Back cannot bypass an unresolved migration-recovery gate. After the original vault is verified safe, Back may return to Settings → Restore.

**Failure/retry**

Retry continues the architecture-owned safe recovery or starts a fresh restore only after recovery completes. It never offers a bypass, merge, or partial-acceptance path.

## Interaction acceptance matrix

Future ViewModel and Compose tests must cover at least:

### Required state and navigation acceptance points

- **Saved Detail relationships:** from a saved Detail, replace transaction–note links and tag/project assignments independently. Assert each complete requested set becomes visible together only after `Phase.Completed`, returns to the same Detail, and leaves the prior set unchanged on validation/save failure.
- **Existing-record Editor relationships:** from an Editor for a saved record, change links, tags, and project, then Done. Assert only the parent draft changes and no relationship read view changes before parent Save. Assert parent Save makes the owner edit and complete relationship set visible together; failure retains the complete draft/selections; Discard leaves all prior saved relationships unchanged.
- **New Editor relationships:** choose links, tags, and project for a new Transaction or Note. Assert only the parent draft changes before Save. Parent Save must make owner and every requested relationship visible together; failure or Discard must make neither owner nor relationship visible while failure retains the complete draft for Retry.
- **Saving navigation lock:** for every editor, dispatch app-bar Back, system Back, predictive-back gesture, Cancel, Discard, and duplicate Confirm during `Phase.Saving`. Assert that all remain on the same editor, do not request cancellation, and produce exactly one result: `Phase.Completed` or a declared failure phase with the complete draft restored. No event sequence may show Cancelled and later show Success.
- **CSV modal progress:** assert that entering `CsvExportProgress` in `Phase.Running` hides/disables bottom navigation and rejects app-bar/system/predictive Back and outside dismissal. Explicit Cancel must enter `Phase.Cancelling` without claiming cancellation; confirmed cancellation must enter `Phase.Cancelled` and return to Settings → Export with Cancelled, while a completion that wins the race returns Success. Failure returns Failed, and no incomplete file is offered as complete.
- **Backup modal progress:** run the same navigation-lock, cancellation acknowledgement, and completion-race cases on `BackupExportProgress`. Success/Cancelled/Failed must return to Settings → Backup; Failed exposes deterministic Retry and requests destination reselection only when required; no incomplete result is restorable.
- **Search revision ordering:** start revision A, edit query/filter to start revision B while A runs, then complete A after B. Assert that only B may update visible results/error/phase. Repeat with a stale A error. Clear or leave Search while a revision runs and assert that later completion changes no visible Search state; opening a current completed result still restores that completed revision on Back.
- **App-start restore routing:** with no pending work, startup may enter the access gate and then the shell only when App Lock permits it. With pending replacement verification or migration/restore recovery, assert that startup enters authenticated `RestoreRecoveryRoute` or unauthenticated `LockedRecoverySurface` before the shell. Verified replacement reaches terminal success only after authentication when lock is enabled; verified rollback reaches terminal failure and enables the original data only after authentication; unresolved/repeated failure keeps shell navigation blocked and exposes Retry/diagnostics only on the authenticated detailed route.
- **App Lock lifecycle:** assert default off; successful platform-authenticated enable/disable; failed/cancelled prompts preserve the prior setting; enabled cold start and every background-to-foreground transition immediately show a secure lock surface; recent-task preview and the first pre-auth frame expose no prior content; success routes to the shell only when recovery is healthy; cancel, Back, failure, and platform lockout never bypass the route.
- **Locked recovery ordering:** assert the technical recovery gate executes before Room; an enabled unauthenticated lock exposes only the generic recovery surface; detailed status, diagnostics, user data, counts, and terminal outcomes remain hidden until authentication; authentication routes to detailed recovery, and unresolved recovery still cannot enter the shell.
- **Diagnostic export modal:** assert Diagnostics shows only time range, event count, estimated size, and retention rule; SAF selection requires explicit confirmation; Running/Cancelling blocks every navigation escape; cancellation waits for acknowledgement; Success/Cancelled/Failed returns to Diagnostics; failures preserve the vault and ring buffer; no path automatically shares or uploads.
- **Restore cancellation boundary:** Cancel in candidate selection enters `Phase.Cancelled`; Cancel during `Phase.Checking` enters `Phase.Cancelling` and waits before reaching `Phase.Cancelled`; both return to Settings → Restore unchanged. After final Confirm enters `Phase.Running`, assert Cancel is absent and every Back/bottom-navigation path is blocked through `Phase.Recovering` until startup validation reaches `Phase.Completed` or verified Failure/Rollback.
- **Opening time:** use a controlled device clock. Assert that the opening date/time field is absent, the first valid Create press captures the clock once, a failed save plus Retry reuses that captured time even after the clock advances, and Discard followed by a fresh draft captures the new clock value.
- **Finance destination table:** assert new Expense/Income/Transfer → Finance-owned transaction detail, new Account → account detail, and owner edit → source detail. An Editor child subflow returns to its parent draft without independent save; a saved Detail relationship subflow returns to that Detail after its own success. Back restores the exact retained launch state and no owner-save navigation occurs before `Phase.Completed`.

| Interaction | Success assertion | Required failure assertion |
|---|---|---|
| Expense / Income | One saved account effect and exact allocations | No optimistic success; complete draft retained; no partial allocation |
| Transfer | Two distinct same-currency account effects sum to zero | Neither account changes on any failure |
| Note | Body or confirmed image is sufficient; no title required | Prior saved revision unchanged; draft retained |
| Opening balance | Account and matching opening-balance history both exist with the first-confirmation time | Neither appears after failed save; Retry retains fields and captured time |
| Transaction–note linking | Saved Detail set replaces independently; any Editor set becomes visible only with parent Save | Saved set unchanged or Editor changes remain draft-only; selections remain available after failure |
| Tags / project | Saved Detail assignments replace independently; any Editor assignments become visible only with parent Save | Saved assignments unchanged or Editor changes remain draft-only; failure retains selections |
| Image import | Confirmed owner and image remain available offline | No visible link to missing content; draft retained |
| Share text/image | Only an unsaved typed draft exists before confirm | Leaving saves nothing; no orphan image |
| Search/filter | Only the current request revision can render All/Transaction/Note results and dispatch to source | Stale/invalidated results and errors are ignored; current query/filter remains editable |
| CSV export | Complete verified CSV, all transactions exactly once, returns Success to Settings → Export | Modal navigation lock holds; Cancel waits for confirmation; incomplete output is never complete |
| Logical backup | Complete verified ZIP without CSV returns Success to Settings → Backup | Modal lock holds; Cancel waits for confirmation; Failed supports Retry; incomplete output is not restorable |
| App Lock | Platform-authenticated enable/disable and unlock follow the recovery-aware route with secure-window protection | Failure/cancel/Back stays locked or preserves the prior setting; no shell, previous frame, diagnostics, or recovery detail leaks before authentication |
| Diagnostic export | Complete verified redacted artifact returns Success to Settings → Diagnostics | Modal lock holds; Cancel waits; failure changes no vault/ring-buffer data and never auto-shares/uploads |
| Restore | Preflight cancellation reaches Cancelled; final Confirm can succeed only after startup verification and blocked cross-start routing when required | After final Confirm no cancellation/navigation escape; original vault returns usable or shell stays blocked; migration gate cannot be bypassed |
