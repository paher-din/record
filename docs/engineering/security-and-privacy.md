# Security and privacy

This document is the source of truth for the MVP security and privacy boundary. Data integrity and recovery mechanics remain owned by [data and storage](../architecture/data-and-storage.md), observable offline behavior by the [MVP requirements](../product/mvp-requirements.md), and implementation discipline by the [engineering conventions](conventions.md).

## Baseline and trust boundary

- The app is local-first with no required account, login, cloud service, or network permission for Journeys 1–13.
- Domain facts, attachments, backups, exports, logs, and diagnostics remain under the device owner or a destination the owner explicitly selects.
- MVP does not claim protection against a fully compromised device, a privileged forensic attacker, malicious firmware, or an attacker who already controls the unlocked OS account.
- Integrity checks prove that bytes and relationships are complete and unmodified according to the backup contract. Integrity is not confidentiality: an unencrypted logical backup may still be read by anyone who obtains it.

## MVP threat model

| Threat | Protected outcome | Required control and verification | Accepted residual risk |
|---|---|---|---|
| Casual unauthorized app access on an unlocked/shared device | Ordinary observers cannot immediately browse personal records when optional app lock is enabled | App lock gates UI entry and return-from-background behavior; tests cover lock, failed unlock, lifecycle return, and recovery-route precedence | App lock is UI access control, not database or backup encryption; OS-level access may still expose unencrypted app data on a compromised device |
| Accidental loss, overwrite, interrupted upgrade, or failed restore | One complete verified vault remains usable and portable | Logical backup round trip, migration recovery gate, generation startup validation, rollback, hashes, and user-visible no-partial-success rules | Loss outside retained backups, device destruction before export, and user deletion of all copies remain possible |
| Corrupt, malicious, incompatible, or path-traversing import | Current data and private storage are not overwritten by unsafe input | Preflight validates format, normalized paths, duplicates, limits, counts, hashes, relationships, balances, and attachments before confirmation; fault tests assert current vault remains unchanged | A novel parser/platform vulnerability outside tested and patched dependencies may remain |
| Excessive platform permissions | The app cannot scan unrelated storage by default | Use Android Photo Picker for images and Storage Access Framework for explicit import/export destinations; reject broad storage access unless a future approved requirement proves it necessary | The selected provider can observe files the user explicitly grants through its own trust boundary |
| Sensitive logs or diagnostics | Financial and personal content is not copied into routine diagnostics | Allow only redacted category/stage/anonymous-operation-ID events, bounded local retention, explicit export, and automated forbidden-content checks | OS/vendor logs outside app-authored fields are governed by the platform; user-chosen export destinations may be less private |

Threat-model changes that add cloud transfer, cross-device sync, shared accounts, broad storage access, encrypted storage, or a new attacker capability require product approval and documentation-first review before implementation.

## App lock and encryption boundary

An optional app lock may use platform authentication to prevent casual UI access. It must not be described as database encryption, backup encryption, secure deletion, or protection from a privileged attacker. Recovery safety gates run before normal shell access; app lock cannot provide a bypass around pending migration or restore recovery.

SQLCipher is deferred from MVP. It may be reconsidered only when V1 has an explicit threat model and a recovery-capable design covering key creation, secure storage, rotation, loss, backup/export, migration, failed unlock, device transfer, and rollback. Enabling SQLCipher or any at-rest encryption requires prior product approval and an accepted ADR; a dependency addition alone cannot authorize it.

MVP logical backups and CSV exports are not encrypted by the app. The UI must state that destination confidentiality depends on the selected storage/provider and must not imply that SHA-256 or ZIP packaging encrypts content.

## Permissions and external content

- Prefer Photo Picker for image selection and SAF for user-selected import/export. Do not request broad storage or media-library access for convenience.
- Request a runtime permission only at the action that needs it, explain the product purpose, and keep unrelated journeys usable after denial.
- Persist URI access only when the platform flow and durable operation require it; release it when no longer needed. Never treat an external filename or path as trusted identity.
- Validate selected/shared content by readable bytes, size, accepted type, and decodability before it can become visible. A provider MIME declaration is not sufficient evidence.
- No normal MVP feature requires Android network permission. Adding any network capability requires a new approved scope, threat review, and disclosure of endpoint, payload, retention, and failure behavior.

## Analytics and third-party SDK policy

MVP includes no commercial analytics SDK, crash-reporting SDK, advertising SDK, or closed-source tracking SDK. Release dependency review must inspect transitive dependencies and manifests, not only direct dependency names. A library with undeclared telemetry, unnecessary permissions, or no practical removal path is rejected.

The app never uploads logs, diagnostics, usage events, backups, or user content automatically. “Anonymous analytics” is not an exception to this rule.

## Logging rules

Application-authored local diagnostic events may contain only:

- a stable error category;
- a non-sensitive operation stage;
- a random anonymous operation ID that is not derived from a domain ID, device ID, account, path, or content.

The platform logging facility may add its own timestamp and process metadata; application code adds no other payload. In particular, code, logs, crash text, test snapshots derived from real data, and diagnostic exports must never contain:

- amounts, balances, currency values tied to a record, merchants, payees, transaction descriptions, category names, or user-entered labels;
- note title/text/Markdown, shared text, search queries, tag names, project names, or attachment content/metadata copied from user input;
- attachment bytes, previews, hashes exposed as user identity, backup payloads, database rows, or exported CSV content;
- encryption keys, keystore material, secrets, tokens, passwords, authorization headers, or credentials;
- identifying physical paths, filenames, provider URIs, database names containing user/device identity, device IDs, hardware IDs, or domain entity IDs;
- raw exception messages or stack arguments when they may embed any forbidden value.

`:core:data` maps raw failures to stable typed errors before UI or diagnostic recording. Where a stack trace is needed during development, it must be proven free of forbidden values or remain outside retained/exportable app diagnostics; production diagnostics use sanitized categories and stages only.

## Local retention and user control

For single-person local development, retained diagnostics use an app-private bounded ring buffer: keep at most the most recent 7 days or 1 MiB, whichever limit is reached first, and rotate older events. A settings screen reports whether diagnostics exist, their approximate size and time range, and offers **Clear diagnostics** without affecting user records.

Diagnostic export is always user initiated, creates a newly sanitized package, previews the included field categories and date range, and uses SAF for an explicitly selected destination. Export never includes the database, attachments, backups, CSV, UI screenshots, environment secrets, or raw logs. No background job uploads it. Cancelling export leaves no app-reported complete artifact; the user can clear retained diagnostics before or after export.

Development builds follow the same content prohibition. Synthetic fixtures must be visibly synthetic and contain no copied personal records. Debug convenience is not a reason to log user content or sensitive paths.

## Backup and diagnostic disclosure

Before backup, CSV, or diagnostic export, the UI identifies the artifact type and its privacy boundary. A complete backup contains private records and images; CSV contains transaction details; diagnostics contain only the allowed redacted fields. These artifacts are distinct and cannot be substituted for one another.

Restore integrity validation, attachment hashes, and manifest hashes detect inconsistency; they do not hide data. Future backup encryption requires product approval, a recovery design, compatibility/version rules, and an ADR before implementation.

## Security verification gates

Before an MVP release:

- deny network permission and execute Journeys 1–13;
- inspect direct/transitive dependencies and merged manifests for analytics, crash, advertising, tracking, and unexpected permissions;
- run the forbidden-log-content checks using synthetic sentinel amounts, merchants, note text, attachment names, secrets, tokens, passwords, and identifying paths, and assert no sentinel reaches retained or exported diagnostics;
- verify Photo Picker/SAF flows, permission denial, revocation, corrupt input, and destination failure;
- verify app lock never claims encryption and cannot bypass a pending recovery gate;
- verify diagnostic rotation, explicit export, cancellation, and clear controls;
- complete the migration and logical-backup recovery gates in the [testing strategy](testing-strategy.md).

Any sensitive diagnostic leak, undeclared network/permission behavior, automatic upload, tracking SDK, or misleading confidentiality claim blocks release.
