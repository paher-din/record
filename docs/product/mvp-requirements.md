# MVP requirements

This document defines the observable MVP behavior for the owner's daily use within the [product vision](vision.md).

“Durably saved” means the complete record remains present after the user leaves the screen and restarts the app. Success is never shown for a partial state.

## Journey 1: Create an account with an opening-balance transaction

### Preconditions

The user can provide an account name, one of the MVP account currencies (`CNY`, `USD`, `EUR`, `JPY`, `HKD`, or `GBP`), and an opening balance.

### User action

The user enters the account details and confirms creation.

### Success state

After the account and its opening-balance transaction are durably saved together, the account appears with the requested balance. The opening balance remains visible as history so the current balance can be recomputed from the account's complete history. Account currency is immutable in MVP, and unlike currencies are never added into one displayed amount.

### Validation failure

If the account name is blank after trimming, no currency is selected, or the opening balance is not a valid amount in that currency, the app identifies the fields to correct and creates neither the account nor its opening-balance transaction. The cross-cutting draft-preservation rule applies.

### Infrastructure failure

If the save cannot complete, the app shows no success state and leaves no partial account or opening-balance transaction. The cross-cutting draft-preservation rule applies.

## Journey 2: Record income and expense with one or more category allocations

### Preconditions

At least one account is available. The first-level category catalog may be empty on a first install; the category picker must provide an inline create entry, and categories can also be managed from Settings → Categories.

### User action

The user chooses income or expense, enters an amount, occurrence date/time, and optional transaction-level plain-text memo, selects one or more available first-level categories or creates one inline or from an offered template, distributes the amount across those categories, and confirms the transaction.

### Success state

After durable save, the transaction appears in account history. An expense reduces the selected account balance, an income increases it, and the category allocation totals equal the transaction amount's magnitude. M2 closes this finance-owned result; M5 adds and verifies the transaction's production Timeline visibility, after which the complete cross-slice Journey 2 success assertion is satisfied.

### Validation failure

If the amount is missing, zero, or not a valid amount in the selected account's currency, if no available category is selected, or if allocation totals do not equal the transaction amount's magnitude, the app explains the issue and saves neither the transaction nor any allocation. If a newly selected category becomes archived before confirmation, the parent draft remains intact and requires the user to reselect an active category or restore the archived category. The cross-cutting draft-preservation rule applies.

### Infrastructure failure

If the save cannot complete, the app shows no financial success and creates no partial transaction or allocation. The cross-cutting draft-preservation rule applies.

## Journey 3: Transfer between two distinct accounts in the same currency

### Preconditions

At least two accounts exist and the user can select a source and destination.

### User action

The user selects both accounts, enters the transfer amount, occurrence date/time, and optional transaction-level plain-text memo, and confirms the transfer.

### Success state

When the source and destination are distinct and their currencies match, the transfer is durably saved all-or-nothing. Only then does the app show success and reflect the transfer consistently in both account histories and balances.

### Validation failure

If the accounts are the same, their currencies differ, or the amount is missing, zero, negative, or not valid in their currency, the app explains the issue and saves no part of the transfer. The cross-cutting draft-preservation rule applies.

### Infrastructure failure

If any part of the save cannot complete, neither account is changed and no success is shown. The cross-cutting draft-preservation rule applies.

## Cross-cutting finance lifecycle

Income, Expense, and Transfer can be edited without changing their Transaction type. An edit replaces the complete requested finance aggregate atomically; stale or failed edits leave the prior saved aggregate and both affected balances unchanged.

An effective non-opening Transaction can be withdrawn after confirmation. Withdrawal is recoverable: it retains the same Transaction identity and history while excluding all of its AccountEntry effects from effective balances together. A withdrawn Transaction is read-only until explicitly restored; Restore re-enables the same aggregate and all of its account effects atomically. `OPENING_BALANCE` cannot be edited, withdrawn, or restored independently. MVP exposes no Transaction hard delete and creates no compensating transaction type for this action.

Income, Expense, and Transfer each have at most one optional transaction-level plain-text memo. After Unicode trim it contains at most 500 Unicode code points; an empty result is absent. Category and individual allocation rows do not have notes.

## Journey 4: Create and edit a Markdown note

### Preconditions

The user can open note creation or an existing note.

### User action

The user writes or edits a basic Markdown body, optionally confirms an image attachment, and confirms the note.

### Success state

After durable save, the note opens with the confirmed content and attachments and appears in the appropriate timeline day.

### Validation failure

A note is saveable only when its trimmed Markdown body is non-empty or it has at least one confirmed image attachment. If neither condition is met, the app requests content, saves no revision, and applies the cross-cutting draft-preservation rule.

### Infrastructure failure

If the save cannot complete, the prior saved version remains unchanged and no success is shown. The cross-cutting draft-preservation rule applies.

## Journey 5: Link multiple notes to one transaction and multiple transactions to one note

### Preconditions

At least one saved transaction and one saved note exist.

### User action

From either a transaction or a note, the user selects one or more records of the other type and confirms the links.

### Success state

After durable save, every confirmed link is visible and navigable from both sides. One transaction can link to multiple notes, and one note can link to multiple transactions.

### Validation failure

Every selected record must still exist when the user confirms. If any selected record is unavailable, none of the requested link changes are saved and the prior saved links remain unchanged.

### Infrastructure failure

If the requested links cannot all be saved, the app shows no success, saves none of the requested link changes, leaves the prior saved links unchanged, and creates no orphan or one-sided association.

## Journey 6: Assign shared tags and at most one primary project

### Preconditions

A transaction or note is open for editing. The shared tag and project catalogs may both be empty on a first install; their selectors provide inline create entries, while tags can also be managed from Settings → Tags and projects from the Projects tab.

### User action

The user selects available shared tags and optionally one available primary project, creating a tag or project inline when needed, then confirms the change.

### Success state

After durable save, all selected tags and zero or one selected primary project appear on the transaction or note. The same tag can be used across both record types.

### Validation failure

Every newly selected tag and project must still exist and be active when the user confirms. The user may select at most one project. If a newly selected item becomes archived, the parent draft and proposed assignments remain available and require reselect or restore; none of the requested assignment changes are saved and the prior saved assignments remain unchanged. An archived item already referenced by the saved record may remain as unchanged history, but it cannot be newly assigned.

### Infrastructure failure

If all requested assignments cannot be saved, none of the requested tag or project changes are saved, the prior saved assignments remain unchanged, and no success is shown.

## Journey 7: Attach an image through a system picker

### Preconditions

A transaction or note is open, and the device can present the system image picker.

### User action

The user opens the system picker, selects an image, reviews the pending attachment, and confirms the record.

### Success state

After durable save, the selected image is visible as an attachment of the confirmed transaction or note and remains available during later offline use.

### Validation failure

If the picker returns no selection, no attachment is added. If the selected item cannot be opened as an image, the app explains the failure, adds no attachment, and applies the cross-cutting draft-preservation rule.

### Infrastructure failure

If the image cannot be retained or the record cannot be saved completely, the app shows no success, leaves the prior saved record unchanged, and saves no orphan image. The cross-cutting draft-preservation rule applies.

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

For a trimmed non-empty query, the app shows matching transactions and notes together when no type filter is active, shows only the selected type when filtered, and lets the user open each result.

### Validation failure

A search executes only for a trimmed non-empty query. An empty query shows the search prompt or empty state, retains the active type filter, and does not claim search results.

### Infrastructure failure

If search cannot complete, the app does not present partial results as complete. The query and filter remain available for retry under the cross-cutting draft-preservation rule.

## Journey 10: View monthly income, expense, and category statistics

### Preconditions

The user can select a month, including a month with no transactions.

### User action

The user opens statistics and selects a month.

### Success state

The app shows that month's income total, expense total, and category breakdown based on saved transactions and their allocations. Historical allocations to an archived category remain included and display that category's current name and archived status. A month without applicable activity is shown as a valid zero or empty state.

### Validation failure

If no calendar month is selected, the app requests a month and retains the last valid view without claiming new statistics.

### Infrastructure failure

If statistics cannot be computed from the saved history, the app shows an explicit failure instead of potentially incorrect totals and allows a retry without changing financial records.

## Journey 11: Export and restore a complete logical ZIP

### Preconditions

The user can choose a destination for export or select a candidate ZIP for restore.

### User action

The user exports a logical ZIP, then verifies the required round trip by intentionally wiping or overwriting current data, selecting that ZIP, and confirming restore only after validation succeeds.

### Success state

The export → wipe or overwrite → restore round trip succeeds only when every account, transaction, note, category, tag, project, image, and resulting account balance matches the exported state. Category, Tag, and Project current names and active/archived states also match. Each category allocation remains on the same transaction; each applied tag remains on the same transaction or note; each primary project assignment remains on the same transaction or note, including references to archived catalog items; each transaction-note association remains between the same records; and each image remains attached to the same confirmed owning transaction or note. Restore validates the entire ZIP before asking for confirmation and replaces current data only as one complete operation.

### Validation failure

If the entire ZIP cannot be read or any required content, association, assignment, image ownership, or resulting balance cannot be validated, the app rejects the ZIP before asking for confirmation and leaves current data unchanged.

### Infrastructure failure

Any failure during export, wipe or overwrite, or restore leaves the data that was current when the failed operation began unchanged. No incomplete artifact is reported as successful, no partial replacement occurs, and the app reports the failure.

## Journey 12: Export transaction CSV

### Preconditions

The user can choose a destination for a transaction export.

### User action

The user requests a CSV export of all effective, non-soft-deleted transactions and confirms the destination.

### Success state

The CSV contains every effective, non-soft-deleted Transaction exactly once, including every effective opening balance, income, expense, and transfer. Soft-deleted Transactions are excluded. For each row, its values match all corresponding user-visible values on that transaction's detail screen. The result opens as a table with one header row and consistent columns in the app's declared supported spreadsheet workflow. The app reports success only after export completes. CSV is an analytical/interoperability artifact, not a forensic backup; only the complete logical ZIP preserves soft-deleted history for full restoration.

### Validation failure

If no effective, non-soft-deleted transactions exist or no writable destination is confirmed, the app explains the issue, produces no CSV, and leaves saved data unchanged.

### Infrastructure failure

If the export cannot complete, the app does not report success or present an incomplete CSV as complete, and current data remains unchanged.

## Journey 13: Receive shared text or image as an unsaved draft

### Preconditions

Another app shares text or a system-provided image to this app.

### User action

The user chooses this app as the share target, chooses a note or transaction draft, reviews the populated draft, completes that record's required details, and either confirms or leaves without saving.

### Success state

Shared text or image first creates only an unsaved note or transaction draft. It becomes saved only after the user explicitly confirms the owning note or transaction and durable save completes. A shared image is saved only as an attachment of that confirmed owner and is never saved as an orphan.

### Validation failure

If shared text is empty after trimming and no shared image can be opened, the app shows an empty-share state and saves nothing. If the chosen note or transaction fails its journey's validation rules, no record or image is saved. The cross-cutting draft-preservation rule applies.

### Infrastructure failure

If the draft cannot be saved completely, the app shows no success and creates no partial record or orphan image. The cross-cutting draft-preservation rule applies.

## Cross-cutting catalog lifecycle

Category, Tag, and Project catalogs are empty-capable. A first install does not seed personalized categories, tags, or projects. Every empty catalog state provides the type-appropriate create entry, and all catalog lifecycle operations work offline.

All three catalog types use the same name rule: after trimming, a name contains 1–40 Unicode code points and is unique within its catalog under case-insensitive Unicode normalization across both active and archived items. A collision with an archived item offers Restore instead of creating a duplicate. Catalog names are ordinary user content, not secrets, but they never enter diagnostic logs.

When Category is empty or the user wants another classifier, Finance may offer fixed context-specific creation templates. Expense offers 餐饮、娱乐、购物、旅游、生活缴费、交通、医疗、服饰、教育、服务、其他; Income offers 工资、奖金、理财收益、退款、其他收入. A template is not persisted until the user chooses it and completes the ordinary Category create flow. It creates no template identity or type restriction: an existing active normalized match is selected, an archived match offers Restore, and a newly created Category remains reusable by either Income or Expense. Transfer never uses Category.

- **Category:** first-level only, with no parent/child hierarchy in MVP. It can be created inline from an Expense/Income category picker or managed from Settings → Categories. The user can create, rename, archive, and restore it; MVP exposes no hard delete. An archived category cannot receive a new allocation. Existing allocations and statistics remain intact and show its current name and archived status.
- **Tag:** it can be created inline from a tag selector or managed from Settings → Tags. The user can create, rename, archive, and restore it; MVP exposes no hard delete. An archived tag cannot receive a new assignment. Existing tag assignments remain intact and display the tag's current name and archived status. Tags still apply only to Transaction and Note.
- **Project:** it can be created inline from a project selector or from the Projects tab's empty/list action. The user can create, rename, archive, and restore it. The user-visible Archive action is recoverable and never a physical purge. An archived project cannot receive a new primary assignment; existing primary assignments remain intact and show the project's current name and archived status in historical details. Projects defaults to the active list, can switch to archived items for Restore, exposes no hard-purge action in MVP, and still cannot receive tags or attachments.

Create, rename, archive, and restore report success only after the complete catalog change is durably saved. Validation or storage failure leaves the saved catalog unchanged and retains the catalog form plus any parent record draft for correction or Retry. Cancelling or navigating Back before durable completion creates or changes nothing.

Successful inline creation returns to the same picker, automatically selects the new item, and preserves every other parent-draft field and selection. The parent record is still unsaved until its own confirmation succeeds. If an inline create fails or is cancelled, the parent draft remains unchanged and no catalog item is created.

## Cross-cutting acceptance

- 30 consecutive days dogfood.
- Journeys 1–13 pass in airplane mode or an otherwise network-isolated environment. Source manifest, merged manifest, and final APK inspection show no `INTERNET` permission, unexpected network component, or related network permission.
- Empty-state create, inline create and auto-select, rename, archive, archived-history rendering, restore, duplicate-name rejection, cancellation, and storage-failure retention pass offline for Category, Tag, and Project without adding another numbered journey.
- The app database, attachments, diagnostics, preferences containing user state, and every other item of user data are excluded from Android cloud backup and system device-to-device transfer. The only supported migration path is an explicit complete logical backup and overwrite restore through a user-selected Storage Access Framework destination.
- Recoverable operation failures that return control to the running app preserve all entered fields and selected media for retry. The MVP does not promise unsaved-draft recovery after force-stop, OS process death, uninstall, or device loss.
- no ads, analytics, or closed-source tracking SDK in release.
- release blockers: any data loss, non-recomputable balance, orphan relation, or backup that cannot restore.
