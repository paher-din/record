"""Synthetic capacity scenarios, NOT user statistics or backup acceptance evidence.

Reads Schema-v1 DDL/indexes and LogicalFormat field order. Writes only a new run directory
under record2/build/lightweight-backup/capacity. No device, production DB, deletion or migration.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from fractions import Fraction
import hashlib
import json
import math
from pathlib import Path
import re
import sqlite3
import statistics
import uuid


ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "app/schemas/com.paherdin.record2.storage.RecordDatabase/1.json"
FORMAT = ROOT / "app/src/main/kotlin/com/paherdin/record2/backup/LogicalFormat.kt"
ENTITY_ROOT = ROOT / "app/src/main/kotlin/com/paherdin/record2/storage"
MIB = 1_048_576
LIMITS = {"total_jsonl_b": 256 * MIB, "single_segment_b": 128 * MIB, "rows": 500_000}
TIME = 1_700_000_000_000


@dataclass(frozen=True)
class Profile:
    name: str
    transactions_daily: str
    notes_daily: str
    turns_daily: str
    turns_per_session: int
    proposal_probability: str
    applied_probability: str
    references_per_turn: str
    links_per_transaction: str
    payload_b: int
    memo_b: int
    note_b: int
    user_message_b: int
    assistant_message_b: int


PROFILES = [
    Profile("light", "1", ".15", ".2", 4, ".05", ".5", ".3", ".1", 2048, 40, 256, 80, 512),
    Profile("ordinary", "4", "1", "2", 8, ".2", ".6", "1", ".25", 4096, 80, 1024, 160, 1024),
    Profile("heavy", "12", "4", "8", 12, ".35", ".7", "2", ".5", 8192, 160, 4096, 320, 2048),
    Profile("extreme", "40", "12", "30", 20, ".5", ".8", "3", "1", 16384, 320, 16384, 640, 4096),
]


def encoded(value: object) -> bytes:
    # Input alphabet has no C1/control characters; matches StrictJson.quote on these samples.
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def ceil_rate(n: int, rate: str) -> int:
    value = n * Fraction(rate)
    return (value.numerator + value.denominator - 1) // value.denominator


def identity(label: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "record-capacity-model/" + label))


def body(size: int, label: str = "") -> str:
    assert len(label.encode("utf-8")) <= size
    return label + "x" * (size - len(label.encode("utf-8")))


def embedded(size: int, label: str) -> str:
    # Four generic operation-shaped objects expose JSON-as-TEXT quote escaping. Not C0 fixtures.
    operation_count = 4 if size >= 160 else 2
    value = {"kind": label, "operations": [{"kind": "note", "text": ""} for _ in range(operation_count)]}
    spare = size - len(encoded(value))
    assert spare >= 0
    value["operations"][0]["text"] = "x" * spare
    result = encoded(value).decode("utf-8")
    assert len(result.encode("utf-8")) == size
    return result


def source_fields() -> dict[str, list[str]]:
    source = FORMAT.read_text(encoding="utf-8").split("internal data class ManifestSegment", 1)[0]
    pattern = r'[A-Z_]+\(\s*"data/([^\"]+)\.jsonl",\s*listOf\((.*?)\)\s*,?\s*\)'
    fields = {m[0]: re.findall(r'"([A-Za-z][A-Za-z0-9]*)"', m[1])
              for m in re.findall(pattern, source, flags=re.S)}
    assert len(fields) == 12, "LogicalFormat changed; audit the model instead of silently proceeding"
    return fields


class Shapes:
    def __init__(self, profile: Profile, fields: dict[str, list[str]]):
        self.profile = profile
        self.fields = fields
        p = profile
        uid = identity("sample")
        self.rows = {
            "account": dict(id=uid, name="日常账户", currency="CNY", version=1, createdAt=TIME, updatedAt=TIME),
            "category": dict(id=uid, name="餐饮", direction="EXPENSE", version=1, createdAt=TIME, updatedAt=TIME),
            "record_transaction": dict(id=uid, direction="EXPENSE", amountMinor=12345, currency="CNY",
                accountId=uid, categoryId=uid, memo=body(p.memo_b), occurredAt=TIME,
                version=1, createdAt=TIME, updatedAt=TIME),
            "note": dict(id=uid, body=body(p.note_b), occurredAt=TIME, version=1,
                createdAt=TIME, updatedAt=TIME, deletedAt=None),
            "transaction_note_link": dict(transactionId=uid, noteId=uid),
            "native_finance_draft": dict(slot=1, draftId=uid, draftRevision=1, state="EDITING",
                mode="NEW", recordId=uid, baseVersion=None, inputJson=embedded(256, "draft"), updatedAt=TIME),
            "change_proposal": dict(id=uid, sessionId=uid, originRunId=uid, lastWriterRunId=uid,
                creationRequestId=uid, creationPayloadJson=embedded(p.payload_b, "payload"), revision=1,
                payloadJson=embedded(p.payload_b, "payload"), payloadDigest="a" * 64, payloadFormatVersion=1,
                dataGeneration=uid, state="APPLIED", activeSlot=None, createdAt=TIME, updatedAt=TIME),
            "commit_operation": dict(id=uid, proposalId=uid, confirmedRevision=1, createdGeneration=uid,
                frozenPayloadJson=embedded(p.payload_b, "payload"), payloadDigest="a" * 64,
                payloadFormatVersion=1, state="APPLIED", createdAt=TIME, finishedAt=TIME,
                resultJson=embedded(256, "operation-result"), rejectionCode=None),
            "agent_session": dict(id=uid, title=body(32), createdAt=TIME, updatedAt=TIME,
                historyVersion=p.turns_per_session, nextMessageSequence=2 * p.turns_per_session + 1),
            "agent_run": dict(id=uid, sessionId=uid, dataGeneration=uid, processOwnerId=uid,
                receiverIdentity=body(64, "synthetic-receiver"), authEpoch=1, proposalCreationRequestId=uid,
                historyBoundarySequence=2 * p.turns_per_session, historyBoundaryVersion=p.turns_per_session,
                state="COMPLETED", activeSlot=None, startedAt=TIME, finishedAt=TIME, safeErrorCode=None),
            "agent_message": dict(id=uid, sessionId=uid, runId=uid, sequence=2 * p.turns_per_session,
                role="ASSISTANT", body=body(p.assistant_message_b), createdAt=TIME,
                resultKind="ANSWER", resultJson=None, proposalId=None, proposalRevision=None),
            "agent_message_reference": dict(messageId=uid, ordinal=0, transactionId=uid, noteId=None),
            "record_data_state": dict(id=1, dataGeneration=uid, businessRevision=1,
                processOwnerId=uid, restoreReceipt=None),
            "ai_control": dict(id=1, mode="OFF", selectedReceiverIdentity=None, grantReceiverIdentity=None,
                grantScopeVersion=None, grantDisclosureVersion=None, grantedAt=None, authEpoch=1),
        }
        for table, names in fields.items():
            assert set(names) == set(self.rows[table]), (table, names)

    def sample(self, table: str, variant: str = "default") -> dict:
        row = dict(self.rows[table])
        if table == "note" and variant == "deleted":
            row["deletedAt"] = TIME
        if table == "change_proposal" and variant == "not-applied":
            row["state"] = "DISCARDED"
        if table == "change_proposal" and variant == "rejected":
            row["state"] = "REJECTED"
        if table == "commit_operation" and variant == "rejected":
            row.update(state="REJECTED", resultJson=None, rejectionCode="CONFLICT")
        if table == "agent_message":
            if variant == "user":
                row.update(role="USER", body=body(self.profile.user_message_b), resultKind="NONE")
            elif variant == "proposal":
                row.update(resultKind="PROPOSAL_READY", resultJson=embedded(128, "proposal"),
                           proposalId=identity("sample"), proposalRevision=1)
            elif variant == "reply-duplicated":
                row["resultJson"] = encoded({"answer": row["body"]}).decode("utf-8")
        return row

    def line(self, table: str, variant: str = "default") -> bytes:
        row = self.sample(table, variant)
        return encoded({key: row[key] for key in self.fields[table]}) + b"\n"

    def contributions(self, table: str, variant: str = "default") -> tuple[int, int]:
        row = self.sample(table, variant)
        variable = {
            "record_transaction": ["memo"], "note": ["body"],
            "change_proposal": ["creationPayloadJson", "payloadJson"],
            "commit_operation": ["frozenPayloadJson"], "agent_message": ["body"],
        }.get(table, [])
        raw = sum(len(row[key].encode("utf-8")) for key in variable)
        return len(self.line(table, variant)), raw


def scenario(shapes: Shapes, days: int, *, all_operations: bool = False,
             duplicate_answer: bool = False) -> dict:
    p = shapes.profile
    transactions = ceil_rate(days, p.transactions_daily)
    visible_notes = ceil_rate(days, p.notes_daily)
    notes = ceil_rate(visible_notes, "1.1")
    turns = ceil_rate(days, p.turns_daily)
    proposals = ceil_rate(turns, p.proposal_probability)
    applied = ceil_rate(proposals, p.applied_probability)
    operations = proposals if all_operations else applied
    sessions = math.ceil(turns / p.turns_per_session)
    counts = {
        "account": 1, "category": 9, "record_transaction": transactions, "note": notes,
        "transaction_note_link": ceil_rate(transactions, p.links_per_transaction),
        "native_finance_draft": 1, "change_proposal": proposals, "commit_operation": operations,
        "agent_session": sessions, "agent_run": turns, "agent_message": 2 * turns,
        "agent_message_reference": ceil_rate(turns, p.references_per_turn),
        "record_data_state": 1, "ai_control": 1,
    }
    mixtures = {table: [(count, "default")] for table, count in counts.items()}
    mixtures["note"] = [(visible_notes, "default"), (notes - visible_notes, "deleted")]
    mixtures["change_proposal"] = [(applied, "default"),
        (proposals - applied, "rejected" if all_operations else "not-applied")]
    mixtures["commit_operation"] = [(applied, "default"), (operations - applied, "rejected")]
    mixtures["agent_message"] = [(turns, "user"),
        (turns - proposals, "reply-duplicated" if duplicate_answer else "default"), (proposals, "proposal")]
    tables = {}
    total_rows = total_bytes = 0
    for table, count in counts.items():
        logical_bytes = raw_bytes = 0
        if table in shapes.fields:
            for n, variant in mixtures[table]:
                line_bytes, variable_bytes = shapes.contributions(table, variant)
                logical_bytes += n * line_bytes
                raw_bytes += n * variable_bytes
            total_rows += count
            total_bytes += logical_bytes
        tables[table] = {"rows": count, "exported": table in shapes.fields,
                        "logical_jsonl_utf8_b": logical_bytes, "raw_body_payload_utf8_b": raw_bytes,
                        "json_metadata_and_escaping_b": logical_bytes - raw_bytes}
    for value in tables.values():
        value["logical_share_percent"] = round(100 * value["logical_jsonl_utf8_b"] / total_bytes, 3)
    exceeded = []
    if total_rows > LIMITS["rows"]:
        exceeded.append("rows")
    if total_bytes > LIMITS["total_jsonl_b"]:
        exceeded.append("total_jsonl_b")
    exceeded.extend("segment:" + table for table, entry in tables.items()
                    if entry["logical_jsonl_utf8_b"] > LIMITS["single_segment_b"])
    return {"profile": p.name, "days": days, "years_365": days / 365,
            "variant": "all-proposals-confirmed" if all_operations else (
                "answer-body-repeated-in-resultJson" if duplicate_answer else "requested-baseline"),
            "visible_note_rows": visible_notes, "turns": turns, "proposals": proposals,
            "applied_operations": applied, "total_export_rows": total_rows,
            "total_database_rows_excluding_room_metadata": sum(counts.values()),
            "total_jsonl_utf8_b": total_bytes, "total_jsonl_mib": round(total_bytes / MIB, 3),
            "payload_copy_raw_b": (2 * proposals + operations) * p.payload_b,
            "tables": tables, "exceeded_model_limits": exceeded,
            "zip_64mib_verdict": "not-inferred: entropy/compression and per-segment STORED fallback required"}


def first_exceed(shapes: Shapes, key: str, limit: int) -> dict:
    def value(days: int) -> int:
        result = scenario(shapes, days)
        if key.startswith("segment:"):
            return result["tables"][key.split(":", 1)[1]]["logical_jsonl_utf8_b"]
        return result[key]
    upper = 365
    while value(upper) <= limit:
        upper *= 2
        assert upper <= 365 * 100_000
    lower = 0
    while lower + 1 < upper:
        middle = (lower + upper) // 2
        if value(middle) > limit:
            upper = middle
        else:
            lower = middle
    return {"first_exceed_day": upper, "years_365": round(upper / 365, 4),
            "last_within_value": value(upper - 1), "first_exceed_value": value(upper), "limit": limit}


def shape_record(shapes: Shapes, entity: dict, index: int) -> dict:
    table = entity["tableName"]
    variant = "user" if table == "agent_message" and index % 2 == 0 else "default"
    if table == "note" and index % 11 == 0:
        variant = "deleted"
    row = shapes.sample(table, variant)
    for field in entity["fields"]:
        key = field["columnName"]
        if key not in row:
            row[key] = None if not field.get("notNull", False) else (1 if field["affinity"] == "INTEGER" else "shape")
    for key in ("id", "draftId", "proposalId", "creationRequestId", "proposalCreationRequestId"):
        if key in row and isinstance(row[key], str):
            row[key] = identity(f"{table}/{key}/{index}")
    if "sessionId" in row:
        row["sessionId"] = identity(f"session/{index // shapes.profile.turns_per_session}")
    if table == "agent_message":
        row["runId"] = identity(f"run/{index // 2}")
    if table == "transaction_note_link":
        row["transactionId"], row["noteId"] = identity(f"transaction/{index}"), identity(f"note/{index}")
    if table == "agent_message_reference":
        row["messageId"] = identity(f"message/{index}")
        row["ordinal"] = index % 3
    if "sequence" in row:
        row["sequence"] = index
    return row


def calibrate(schema: dict, shapes: Shapes, sample_rows: int, directory: Path) -> dict:
    path = directory / f"shape-{shapes.profile.name}-{sample_rows}.db"
    assert not path.exists(), "never replace an existing calibration DB"
    db = sqlite3.connect(path, isolation_level=None)
    try:
        db.execute("PRAGMA page_size=4096")
        db.execute("PRAGMA foreign_keys=OFF")
        for entity in schema["entities"]:
            name = entity["tableName"]
            db.execute(entity["createSql"].replace("${TABLE_NAME}", name))
            for index in entity.get("indices", []):
                db.execute(index["createSql"].replace("${TABLE_NAME}", name))
        for query in schema["setupQueries"]:
            db.execute(query)
        db.execute("PRAGMA user_version=1")
        base_bytes = db.execute("PRAGMA page_count").fetchone()[0] * 4096
        objects = {}
        for entity in schema["entities"]:
            table = entity["tableName"]
            count = 1 if table in {"account", "native_finance_draft", "record_data_state", "ai_control"} else (
                9 if table == "category" else sample_rows)
            fields = [field["columnName"] for field in entity["fields"]]
            sql = f'INSERT INTO "{table}" ({",".join(chr(34)+key+chr(34) for key in fields)}) VALUES ({",".join("?" for _ in fields)})'
            before = db.execute("PRAGMA page_count").fetchone()[0] * 4096
            db.execute("BEGIN")
            for n in range(count):
                row = shape_record(shapes, entity, n)
                db.execute(sql, [row[key] for key in fields])
            db.execute("COMMIT")
            after = db.execute("PRAGMA page_count").fetchone()[0] * 4096
            indexes = [index[1] for index in db.execute(f'PRAGMA index_list("{table}")')]
            dbstat = None
            try:
                placeholders = ",".join("?" for _ in [table, *indexes])
                dbstat = dict(db.execute(f"SELECT name,SUM(pgsize) FROM dbstat WHERE name IN ({placeholders}) GROUP BY name", [table, *indexes]))
            except sqlite3.OperationalError:
                pass
            objects[table] = {"rows": count, "incremental_allocated_b": after - before,
                              "incremental_b_per_row": (after - before) / count,
                              "indexes_including_implicit": indexes, "dbstat_object_b": dbstat}
        page_bytes = db.execute("PRAGMA page_count").fetchone()[0] * 4096
        wal = wal_samples(db, schema, shapes)
        return {"profile": shapes.profile.name, "sample_rows_per_growing_table": sample_rows,
                "path": str(path.relative_to(ROOT)), "sqlite_version": sqlite3.sqlite_version,
                "sqlite_source_id": db.execute("SELECT sqlite_source_id()").fetchone()[0],
                "page_size": 4096, "empty_schema_b": base_bytes, "page_bytes_before_wal_trials": page_bytes,
                "all_ddl_and_explicit_indexes_from_schema": True, "foreign_keys_enforced": False,
                "business_validity_claim": False, "tables": objects, "small_transaction_wal": wal}
    finally:
        db.close()


def wal_samples(db: sqlite3.Connection, schema: dict, shapes: Shapes) -> dict:
    assert db.execute("PRAGMA journal_mode=WAL").fetchone()[0] == "wal"
    db.execute("PRAGMA wal_autocheckpoint=0")
    path = Path(db.execute("PRAGMA database_list").fetchone()[2])
    wal_path = Path(str(path) + "-wal")
    entities = {entity["tableName"]: entity for entity in schema["entities"]}
    result = {}
    for kind in ("note-update", "finance-update", "ai-turn-insert", "proposal-insert", "operation-insert"):
        db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        changes = []
        for n in range(8):
            before = wal_path.stat().st_size if wal_path.exists() else 0
            db.execute("BEGIN IMMEDIATE")
            if kind == "note-update":
                db.execute("UPDATE note SET body=?,version=version+1 WHERE rowid=1", [body(shapes.profile.note_b, f"{n}:")])
            elif kind == "finance-update":
                db.execute("UPDATE record_transaction SET amountMinor=amountMinor+1,version=version+1 WHERE rowid=1")
            else:
                tables = {"ai-turn-insert": ["agent_run", "agent_message", "agent_message"],
                          "proposal-insert": ["change_proposal"], "operation-insert": ["commit_operation"]}[kind]
                for j, table in enumerate(tables):
                    entity = entities[table]
                    row = shape_record(shapes, entity, 1_000_000 + n * 3 + j)
                    fields = [field["columnName"] for field in entity["fields"]]
                    db.execute(f'INSERT INTO "{table}" VALUES ({",".join("?" for _ in fields)})', [row[key] for key in fields])
            db.execute("COMMIT")
            after = wal_path.stat().st_size
            changes.append(after - before)
        result[kind] = {"transactions": len(changes), "wal_growth_b": changes,
                        "min_b": min(changes), "median_b": statistics.median(changes), "max_b": max(changes)}
    return {"autocheckpoint_disabled_for_eight_shape_transactions": True,
            "held_export_reader": False, "not_a_daily_or_yearly_wal_forecast": True, "patterns": result}


def add_physical_estimates(models: list[dict], calibrations: list[dict]) -> None:
    for model in models:
        samples = [sample for sample in calibrations if sample["profile"] == model["profile"]]
        if not samples:
            continue
        low = high = max(sample["empty_schema_b"] for sample in samples)
        for table, value in model["tables"].items():
            if table in {"account", "category", "native_finance_draft", "record_data_state", "ai_control"}:
                continue  # Their few pages are already represented by the complete empty schema.
            rates = [sample["tables"][table]["incremental_b_per_row"] for sample in samples]
            lo_rate, hi_rate = min(rates) * .8, max(rates) * 1.5
            value["physical_compact_estimate_b_range"] = [math.floor(lo_rate * value["rows"]), math.ceil(hi_rate * value["rows"])]
            low += lo_rate * value["rows"]
            high += hi_rate * value["rows"]
        model["physical_compact_estimate_b_range"] = [math.floor(low), math.ceil(high)]
        model["physical_range_not_a_guaranteed_bound"] = True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--calibration-rows", default="256,1024", help="Comma-separated samples; 0 skips DB calibration")
    args = parser.parse_args()
    directory = ROOT / "build/lightweight-backup/capacity" / (
        datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8])
    directory.mkdir(parents=True, exist_ok=False)
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]
    assert schema["version"] == 1 and len(schema["entities"]) == 14
    fields = source_fields()
    shapes = {profile.name: Shapes(profile, fields) for profile in PROFILES}
    models = [scenario(shape, 365 * years) for shape in shapes.values() for years in (1, 3, 5)]
    sensitivities = [scenario(shape, 365 * 5, **options) for shape in shapes.values()
                     for options in ({"all_operations": True}, {"duplicate_answer": True})]
    thresholds = {}
    for name, shape in shapes.items():
        thresholds[name] = {
            "rows": first_exceed(shape, "total_export_rows", LIMITS["rows"]),
            "total_jsonl": first_exceed(shape, "total_jsonl_utf8_b", LIMITS["total_jsonl_b"]),
            "segments": {table: first_exceed(shape, "segment:" + table, LIMITS["single_segment_b"])
                         for table in fields if table not in {"account", "category", "native_finance_draft"}},
        }
        candidates = [("rows", thresholds[name]["rows"]), ("total_jsonl", thresholds[name]["total_jsonl"])]
        candidates.extend(("segment:" + table, value) for table, value in thresholds[name]["segments"].items())
        first_kind, first_value = min(candidates, key=lambda item: item[1]["first_exceed_day"])
        thresholds[name]["first_model_limit"] = dict(kind=first_kind, **first_value)
    calibrations = []
    sizes = [int(value) for value in args.calibration_rows.split(",") if int(value) > 0]
    for shape in shapes.values():
        for size in sizes:
            calibrations.append(calibrate(schema, shape, size, directory))
    add_physical_estimates(models + sensitivities, calibrations)
    input_paths = [SCHEMA, FORMAT] + [ENTITY_ROOT / name for name in (
        "RecordEntities.kt", "ProposalEntities.kt", "SessionEntities.kt", "AiControlEntity.kt")]
    report = {
        "warning": "Synthetic assumptions, no user statistics, legal-business or approved-limit claim",
        "schema_version": 1, "schema_identity": schema["identityHash"],
        "inputs": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in input_paths},
        "host_sqlite_version": sqlite3.sqlite_version, "profiles": [asdict(profile) for profile in PROFILES],
        "assumptions": {"days_per_year": 365, "visible_note_to_retained_factor": "1.1",
            "one_turn": "one run + one user message + one assistant message",
            "operations_baseline": "one per applied proposal; non-applied proposals assumed unconfirmed/discarded",
            "proposal_payload": "creation and current copies for every proposal; frozen copy for every operation",
            "business_rows_include_ai_created_items": True, "answer_result_json": None,
            "proposal_message_envelope_utf8_b": 128, "operation_result_envelope_utf8_b": 256,
            "session_title_utf8_b": 32, "receiver_identity_utf8_b": 64, "editing_drafts": 1,
            "json_representation": "actual field order, quoted embedded JSON as TEXT; ASCII payload body size, no ZIP entropy claim",
            "calibration": "full generated DDL/indexes; shape rows with FK OFF, no migrations or business validity claim",
            "physical_estimate": "0.8*minimum and 1.5*maximum observed incremental B/row plus fixed schema; not a hard bound"},
        "unchanged_limits": LIMITS, "baseline_models": models, "five_year_sensitivities": sensitivities,
        "first_exceed_thresholds": thresholds, "host_shape_calibrations": calibrations,
    }
    path = directory / "capacity.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"report": str(path), "sqlite_version": sqlite3.sqlite_version,
        "scenarios": [{"profile": model["profile"], "years": model["years_365"],
                       "rows": model["total_export_rows"], "jsonl_mib": model["total_jsonl_mib"],
                       "exceeded": model["exceeded_model_limits"]} for model in models],
        "first_limit": {key: value["first_model_limit"] for key, value in thresholds.items()}},
        ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
