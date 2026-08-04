# Information architecture

This document is the source of truth for application destinations, screen ownership, navigation, and navigation-state retention. Observable journey outcomes remain owned by the [MVP requirements](../product/mvp-requirements.md); module boundaries remain owned by the [architecture overview](../architecture/overview.md); interaction state machines are defined in [interaction rules](interaction-rules.md).

## Platform and shell boundary

The MVP is a local-first, single-user native Android application with `minSdk 34`. Journeys 1–13 and every destination in this document must remain usable with Android network permission denied.

The application shell has four persistent bottom-navigation destinations in this exact order:

1. Timeline
2. Finance
3. Notes
4. Projects

These destinations are peer domains. Selecting one switches the active top-level back stack; it never scrolls vertically into another top-level domain. A screen that concatenates Timeline, Finance, Notes, and Projects into one vertically paged surface is non-conforming.

Every top-level app bar exposes the same application-level commands:

- global create (`+`), whose only MVP choices are Expense, Income, Transfer, and Note;
- global Search;
- global Settings.

The shell does not offer a generic **Entry**, **Record**, or **Create anything** destination. The shared timeline and search experience unifies reading and discovery, not the source entity or its write path.

## Destination ownership

| Destination or screen family | Owning feature | Responsibility and write boundary |
|---|---|---|
| Timeline | `:feature:timeline` | Default home and mixed day read model for transactions and notes. It reads projections and never writes source facts. |
| Finance | `:feature:finance` | Accounts, account history, transaction details and editors, categories, balances, monthly income/expense totals, and category statistics. |
| Notes | `:feature:notes` | Note list, Markdown note details and editor, confirmed note images, and note-side transaction linking. |
| Projects | `:feature:timeline` | Project list/detail and aggregation of transactions and notes by their one optional primary project. It is a context read surface, not a generic relation editor. |
| Unified Search | `:feature:timeline` | Rebuildable mixed result projection with transaction/note type filtering. It dispatches selection to the owning detail screen. |
| Global create and application navigation | `:app` | Dispatches the selected typed creation route; it does not implement finance or note writes. |
| Settings, export, backup, and restore entry | `:app`, delegating data work to `:core:data` | Owns application-level data controls and routes into typed progress or restore-wizard states. |
| Attachment preview | Owning editor/detail feature | Displays a selected or confirmed image without exposing its physical path. |

Finance and Notes own all edits to their facts. Timeline, Projects, and Search may display a transaction or note card, but selecting that card opens the source entity's Finance-owned transaction detail or Notes-owned note detail. Any subsequent edit starts from that owning detail screen.

## Navigation graph

The identifiers such as `TL_HOME` below are diagram node IDs only. Labels such as “Timeline home” are screen identities, while Compose navigation route keys are the typed keys in the route table after the diagram; none of the node IDs is a URI route string.

```mermaid
flowchart TD
    SHELL["Application shell"]
    CREATE["Global create chooser"]
    SEARCH["Unified search"]
    SETTINGS["Settings"]

    subgraph TOP["Persistent bottom navigation"]
        TL_HOME["Timeline home"]
        FI_HOME["Finance home"]
        NO_HOME["Notes home"]
        PR_HOME["Projects home"]
    end

    TX_DETAIL["Transaction detail (Finance-owned)"]
    NOTE_DETAIL["Note detail (Notes-owned)"]
    ACCOUNT_DETAIL["Account detail"]
    PROJECT_DETAIL["Project detail"]

    EXPENSE_EDIT["Expense editor"]
    INCOME_EDIT["Income editor"]
    TRANSFER_EDIT["Transfer editor"]
    NOTE_EDIT["Note editor"]
    ACCOUNT_EDIT["Account and opening-balance editor"]
    ATTACHMENT_PREVIEW["Attachment preview"]
    DATA_EXPORT["CSV or backup export progress"]
    RESTORE["Restore wizard"]

    SHELL --> TOP
    SHELL --> CREATE
    SHELL --> SEARCH
    SHELL --> SETTINGS

    CREATE --> EXPENSE_EDIT
    CREATE --> INCOME_EDIT
    CREATE --> TRANSFER_EDIT
    CREATE --> NOTE_EDIT

    TL_HOME --> TX_DETAIL
    TL_HOME --> NOTE_DETAIL
    SEARCH --> TX_DETAIL
    SEARCH --> NOTE_DETAIL
    FI_HOME --> ACCOUNT_DETAIL
    FI_HOME --> TX_DETAIL
    FI_HOME --> ACCOUNT_EDIT
    NO_HOME --> NOTE_DETAIL
    PR_HOME --> PROJECT_DETAIL
    PROJECT_DETAIL --> TX_DETAIL
    PROJECT_DETAIL --> NOTE_DETAIL

    TX_DETAIL --> EXPENSE_EDIT
    TX_DETAIL --> INCOME_EDIT
    TX_DETAIL --> TRANSFER_EDIT
    NOTE_DETAIL --> NOTE_EDIT
    TX_DETAIL --> ATTACHMENT_PREVIEW
    NOTE_DETAIL --> ATTACHMENT_PREVIEW
    EXPENSE_EDIT --> ATTACHMENT_PREVIEW
    INCOME_EDIT --> ATTACHMENT_PREVIEW
    TRANSFER_EDIT --> ATTACHMENT_PREVIEW
    NOTE_EDIT --> ATTACHMENT_PREVIEW

    SETTINGS --> DATA_EXPORT
    SETTINGS --> RESTORE
```

### Typed route contract

Typed route keys are stable screen contracts for future Compose implementation. Arguments use domain IDs, never Room entities or physical file paths.

| Route key | Required argument or launch state | Back destination |
|---|---|---|
| `TimelineHome` | Retained timeline tab state | Prior task or app exit from the root |
| `FinanceHome` | Retained finance tab state | Prior task or app exit from the root |
| `NotesHome` | Retained notes tab state | Prior task or app exit from the root |
| `ProjectsHome` | Retained projects tab state | Prior task or app exit from the root |
| `UnifiedSearch` | Query and type-filter state | The exact top-level destination that launched Search |
| `Settings` | Selected top-level destination as return context | The exact top-level destination that launched Settings |
| `TransactionDetail` | `transactionId` plus optional origin context | The exact originating Timeline, Finance, Project, or Search state |
| `NoteDetail` | `noteId` plus optional origin context | The exact originating Timeline, Notes, Project, or Search state |
| `AccountDetail` | `accountId` | Prior Finance state |
| `ProjectDetail` | `projectId` | Prior Projects state |
| `ExpenseEditor` / `IncomeEditor` / `TransferEditor` | New typed draft or an existing `transactionId` | Launching detail or top-level state |
| `NoteEditor` | New note draft or existing `noteId` | Launching detail or top-level state |
| `AccountOpeningEditor` | New account draft | Prior Finance state |
| `AttachmentPreview` | In-memory pending-media handle or persisted attachment ID | Owning editor/detail with its state intact |
| `DataExportProgress` | Explicit export kind: transaction CSV or logical backup | Settings after terminal state or cancellation |
| `RestoreWizard` | No implicit candidate; the user selects one inside the wizard | Settings after cancellation or terminal state |

An origin context is navigation-only state needed to return to the previous scroll position, filter, query, or date. It is not persisted as a domain relation.

## Bottom-navigation visibility

Bottom navigation is visible on:

- all four top-level homes;
- their browse/detail surfaces, including account, transaction, note, and project details;
- Unified Search, Settings, and non-destructive export progress.

Bottom navigation is hidden for focused or destructive flows:

- every transaction, note, account, tag, or project editor;
- attachment preview, whether launched from a draft or saved detail;
- the complete Restore Wizard from candidate selection through its terminal result.

Hiding bottom navigation does not discard the selected tab or its back stack. Back or a terminal action restores the same shell context. System Back from a dirty editor follows the discard rule in [interaction rules](interaction-rules.md); it must not silently destroy a draft.

## Per-tab state retention

Each top-level destination owns an independent navigation back stack and a retained state bundle. Switching tabs and opening then closing a child screen must preserve that bundle.

| Tab | Retained scroll position | Retained filters | Retained date state |
|---|---|---|---|
| Timeline | Mixed-card list position and offset | Active timeline filters, if any | Selected day |
| Finance | Active account/statistics list position and offset | Selected account/category/type filters | Selected statistics month or transaction-history date range |
| Notes | Note-list position and offset | Active note/tag/project filters | Selected note-date filter or date anchor |
| Projects | Project-list or current project-feed position and offset | Active project/entity-type filters | Selected project-feed date filter or date anchor |

Retention is required for tab switching, source-detail round trips, Search round trips, and configuration recreation while the task remains in the running app. Unsaved-draft recovery after force-stop, OS process death, uninstall, or device loss is outside the MVP promise, consistent with the cross-cutting acceptance rule.

Compose implementation must hoist these values out of disposable list items and scope them to each top-level back-stack entry. Restoring one tab must not overwrite another tab's state. A tab reselect may expose an explicit “return to root” or “scroll to current date” action later, but reselecting a tab must not implicitly reset state in MVP.

## Cross-domain navigation rules

1. Timeline is the default launch destination and a read-only mixed projection.
2. A transaction card from Timeline, Projects, or Search opens `TransactionDetail`; a note card opens `NoteDetail`.
3. Back from the source detail returns to the exact origin with its scroll position, filters, query, and selected date intact.
4. Editing is dispatched by source type. Timeline, Projects, and Search never host a generic editor and never mutate a projection.
5. Transaction-to-note and note-to-transaction links open the linked source detail in the owning feature. Back returns to the originating detail.
6. A primary-project chip or assignment may open `ProjectDetail`; this navigation does not change the fact's assignment.
7. Missing or soft-deleted targets render an unavailable state and a safe Back action; they do not redirect to an unrelated record.

## Navigation acceptance checks

Future Compose navigation and UI tests must demonstrate:

- launch opens Timeline and the bottom-navigation order is Timeline, Finance, Notes, Projects;
- all four tabs retain independent scroll position, filter, and date state across switching;
- top-level domains cannot be reached by vertically scrolling another domain;
- global `+` exposes exactly Expense, Income, Transfer, and Note;
- global Search and Settings are reachable from every top-level app bar;
- editors, attachment preview, and Restore Wizard hide bottom navigation and restore the prior context on return;
- cards from Timeline and Search dispatch to the correct source detail and Back restores origin state;
- no screen, route key, button, or domain type named generic Entry is introduced.
