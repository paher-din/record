# Security and privacy

This document is the source of truth for the MVP security and privacy boundary. Data integrity and recovery mechanics remain owned by [data and storage](../architecture/data-and-storage.md), observable offline behavior by the [MVP requirements](../product/mvp-requirements.md), and implementation discipline by the [engineering conventions](conventions.md).

## Baseline and trust boundary

- The app is local-first with no required account, login, or cloud service. Journeys 1–13 run in airplane mode or an otherwise network-isolated environment, and release artifacts declare no `INTERNET` permission or unexpected network capability.
- Domain facts, attachments, backups, exports, logs, and diagnostics remain under the device owner or a destination the owner explicitly selects.
- Android cloud backup and system device-to-device transfer contain no app database, attachments, diagnostics, preferences with user state, or other app user data. The only supported migration path is the explicit in-app logical backup/restore flow to a user-selected SAF destination.
- MVP does not claim protection against a fully compromised device, a privileged forensic attacker, malicious firmware, or an attacker who already controls the unlocked OS account.
- Integrity checks prove that bytes and relationships are complete and unmodified according to the backup contract. Integrity is not confidentiality: an unencrypted logical backup may still be read by anyone who obtains it.

## MVP threat model

| Threat | Protected outcome | Required control and verification | Accepted residual risk |
|---|---|---|---|
| Casual unauthorized app access on an unlocked/shared device | Ordinary observers cannot immediately browse personal records when optional app lock is enabled | App lock gates UI entry and return-from-background behavior; tests cover lock, failed unlock, lifecycle return, and recovery-route precedence | App lock is UI access control, not database or backup encryption; OS-level access may still expose unencrypted app data on a compromised device |
| Accidental loss, overwrite, interrupted upgrade, or failed restore | One complete verified vault remains usable and portable | Logical backup round trip, migration recovery gate, generation startup validation, rollback, hashes, and user-visible no-partial-success rules | Loss outside retained backups, device destruction before export, and user deletion of all copies remain possible |
| Corrupt, malicious, incompatible, path-traversing, or resource-exhausting import | Current data and private storage are not overwritten by unsafe input | Preflight applies the binding entry/path/size/staging-reserve/200:1 ratio limits and manifest-plus-stream counters in [data and storage](../architecture/data-and-storage.md); fault tests assert current vault remains unchanged | A novel parser/platform vulnerability outside tested and patched dependencies may remain |
| Excessive platform permissions | The app cannot scan unrelated storage by default | Use Android Photo Picker for images and Storage Access Framework for explicit import/export destinations; reject broad storage access unless a future approved requirement proves it necessary | The selected provider can observe files the user explicitly grants through its own trust boundary |
| Sensitive logs or diagnostics | Financial and personal content is not copied into routine diagnostics | Allow only error-category/stage/anonymous-operation-ID event envelopes, UTC-day segment retention metadata, explicit export, and automated forbidden-content checks | OS/vendor logs outside app-authored fields are governed by the platform; user-chosen export destinations may be less private |
| Android system backup or device transfer | OEM/platform backup cannot create an undeclared copy or migration path | Deny backup by manifest policy and explicitly exclude every app-managed domain in API 34 data-extraction and compatible rules; inspect source manifest, merged manifest, and final APK | A compromised OS can still access local app data outside the app threat boundary |

Threat-model changes that add cloud transfer, cross-device sync, shared accounts, broad storage access, encrypted storage, or a new attacker capability require product approval and documentation-first review before implementation.

## App lock and encryption boundary

App Lock defaults off. When enabled, it uses Android `BiometricPrompt` with supported biometric/device credential authentication and never creates an app-owned password. Every cold start and every background-to-foreground transition locks immediately with no grace period. Reliable secure-window protection covers screen content and recent-task previews before any prior frame can appear.

App Lock is UI access control and must not be described as database encryption, backup encryption, secure deletion, or protection from a privileged attacker. Technical recovery safety gates run before Room and before normal shell access. While unauthenticated, a pending gate may expose only the generic data-free recovery surface; diagnostic actions, user data, counts, detailed/terminal status, and the shell require authentication. An unresolved gate remains blocking after authentication. The complete interaction contract is owned by [interaction rules](../experience/interaction-rules.md).

SQLCipher is deferred from MVP. It may be reconsidered only when V1 has an explicit threat model and a recovery-capable design covering key creation, secure storage, rotation, loss, backup/export, migration, failed unlock, device transfer, and rollback. Enabling SQLCipher or any at-rest encryption requires prior product approval and an accepted ADR; a dependency addition alone cannot authorize it.

MVP logical backups and CSV exports are not encrypted by the app. The UI must state that destination confidentiality depends on the selected storage/provider and must not imply that SHA-256 or ZIP packaging encrypts content.

## Permissions and external content

- Prefer Photo Picker for image selection and SAF for user-selected import/export. Do not request broad storage or media-library access for convenience.
- Request a runtime permission only at the action that needs it, explain the product purpose, and keep unrelated journeys usable after denial.
- Persist URI access only when the platform flow and durable operation require it; release it when no longer needed. Never treat an external filename or path as trusted identity.
- Validate selected/shared content by readable bytes, size, accepted type, and decodability before it can become visible. A provider MIME declaration is not sufficient evidence.
- Source manifest, merged manifest, and final APK declare no `INTERNET` permission, unexpected network component, or related network permission. Offline acceptance runs Journeys 1–13 in airplane mode or an otherwise network-isolated environment; it does not claim that toggling a runtime permission controls networking or Android system backup.
- Adding any network capability requires a new approved scope, threat review, and disclosure of endpoint, payload, retention, and failure behavior.

## Analytics and third-party SDK policy

MVP includes no commercial analytics SDK, crash-reporting SDK, advertising SDK, or closed-source tracking SDK. Release dependency review must inspect transitive dependencies and manifests, not only direct dependency names. A library with undeclared telemetry, unnecessary permissions, or no practical removal path is rejected.

The app never uploads logs, diagnostics, usage events, backups, or user content automatically. “Anonymous analytics” is not an exception to this rule.

## Logging rules

Application-authored local diagnostic events may contain only:

- a stable error category;
- a non-sensitive operation stage;
- a random anonymous operation ID that is not derived from a domain ID, device ID, account, path, or content.

These three fields are the complete retained/exportable application event allowlist. A log event contains no timestamp. Platform timestamps, process metadata, and raw logging output are not copied into retained event entries or exported event entries. In particular, code, logs, crash text, test snapshots derived from real data, and diagnostic exports must never contain:

- amounts, balances, currency values tied to a record, merchants, payees, transaction descriptions, category names, or user-entered labels;
- note title/text/Markdown, shared text, search queries, tag names, project names, or attachment content/metadata copied from user input;
- attachment bytes, previews, hashes exposed as user identity, backup payloads, database rows, or exported CSV content;
- encryption keys, keystore material, secrets, tokens, passwords, authorization headers, or credentials;
- identifying physical paths, filenames, provider URIs, database names containing user/device identity, device IDs, hardware IDs, or domain entity IDs;
- raw exception messages or stack arguments when they may embed any forbidden value.

`:core:data` maps raw failures to stable typed errors before UI or diagnostic recording. Where a stack trace is needed during development, it must be proven free of forbidden values or remain outside retained/exportable app diagnostics; production diagnostics use sanitized categories and stages only.

## Local retention and user control

For single-person local development, retained diagnostics use an app-private ring buffer partitioned into UTC-day segments. An injected clock chooses the current segment and supplies segment-level storage metadata for creation, UTC-day rollover, rotation, and expiry. Segment metadata is not a log-event field, is not associated with an individual event, and contains no user time zone, identity, path, or content. Retain the current UTC-day segment plus at most the preceding six UTC-day segments as a retention window of 7 days, and keep total serialized ring-buffer storage at or below 1 MiB by rotating the oldest segment/events as needed. Tests cover all seven UTC-day segments, rollover into an eighth day, exact 1 MiB, one serialized byte over, simultaneous age/size pressure, restart, and clear. A settings screen reports only segment coverage window, event count, estimated size, and retention rule, and offers **Clear diagnostics** without affecting user records.

Diagnostic export is always user initiated from Settings → Diagnostics or an authenticated detailed recovery route, creates a newly sanitized package, previews only segment coverage window/event count/estimated size and allowed field categories, and uses SAF for an explicitly selected destination. Package-summary metadata may contain segment-derived `windowStart`, `windowEnd`, and an injected-UTC-clock package generation time. These values describe the package/segments only, are not associated with any individual event, and are not log-event fields; every exported event remains the strict three-field envelope. Its full-screen modal follows the Running/Cancelling/terminal contract in [interaction rules](../experience/interaction-rules.md). Export never includes the database, attachments, backups, CSV, UI screenshots, environment secrets, or raw logs. No background job shares or uploads it. Cancelling export leaves no app-reported complete artifact; failure modifies neither vault nor ring buffer; the user can clear retained diagnostics before or after export.

Development builds follow the same content prohibition. Synthetic fixtures must be visibly synthetic and contain no copied personal records. Debug convenience is not a reason to log user content or sensitive paths.

## Backup and diagnostic disclosure

Before backup, CSV, or diagnostic export, the UI identifies the artifact type and its privacy boundary. A complete backup contains private records and images; CSV contains transaction details; diagnostics contain only the allowed redacted fields. These artifacts are distinct and cannot be substituted for one another.

Restore integrity validation, attachment hashes, and manifest hashes detect inconsistency; they do not hide data. Future backup encryption requires product approval, a recovery design, compatibility/version rules, and an ADR before implementation.

Android cloud backup and system device-to-device transfer are disabled for every app-managed user-data domain. Implementation must combine API 34 `android:dataExtractionRules`, compatible `android:fullBackupContent` exclusion rules, and `android:allowBackup="false"`; it must not rely on `allowBackup` alone where OEM transfer behavior differs. This policy excludes the database, files/attachments, shared preferences containing app state, diagnostics, recovery metadata, and any external app-managed user data. Only explicit logical backup/restore through user-selected SAF is supported for migration.

## Security verification gates

Before an MVP release:

- inspect source manifest, merged manifest, and final APK and assert no `INTERNET` permission, unexpected network component, or related network permission; then execute Journeys 1–13 in airplane mode or an otherwise network-isolated environment;
- inspect source manifest, merged manifest, final APK, API 34 `dataExtractionRules`, compatible full-backup rules, and `allowBackup` semantics; assert every app-managed user-data domain is excluded from cloud backup and device-to-device transfer;
- inspect direct/transitive dependencies and merged manifests for analytics, crash, advertising, tracking, and unexpected permissions;
- run the forbidden-log-content checks using synthetic sentinel amounts, merchants, note text, attachment names, secrets, tokens, passwords, and identifying paths, and assert no sentinel reaches retained or exported diagnostics;
- assert every retained/exported event contains exactly error category, stage, and anonymous operation ID and no timestamp; separately verify UTC-day segment metadata, seven-segment retention, eighth-day rollover, exact 1-MiB boundary, and package-summary `windowStart`/`windowEnd`/generation time without per-event association;
- verify Photo Picker/SAF flows, permission denial, revocation, corrupt input, and destination failure;
- verify malicious ZIP resource limits and manifest/stream double counting from [data and storage](../architecture/data-and-storage.md), with current vault unchanged after every rejection;
- verify App Lock defaults off, uses only platform credentials, locks immediately on cold/foreground entry, protects screen/task previews, never claims encryption, hides recovery/diagnostic details before authentication, and cannot bypass a pending recovery gate;
- verify diagnostic envelope, modal export navigation lock, explicit export, cancellation race, failure/retry, no automatic share/upload, rotation, and clear controls;
- complete the migration and logical-backup recovery gates in the [testing strategy](testing-strategy.md).

Any sensitive diagnostic leak, undeclared network/permission behavior, system-backup or device-transfer inclusion, automatic upload, App Lock/recovery bypass, tracking SDK, malicious-ZIP limit failure, or misleading confidentiality claim blocks release.
