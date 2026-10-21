"""Local evaluation inbox. Production response_incident dispatch is a separate adapter.

Claims are never automatically retried: a crashed RUNNING entry requires human
reconciliation, preventing a lost paid response from resetting the budget.
"""
import hashlib
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path


def digest(value):
    data = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(data.encode()).hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat()


def atomic_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    temporary.replace(path)


def menu_shape(raw):
    """IDs/values/order/counts cannot create new structure incidents."""
    def shape(value):
        if isinstance(value, dict):
            return {key.split("(", 1)[0]: shape(child) for key, child in sorted(value.items())
                    if key not in {"id", "__ref", "observedAt", "timestamp"}}
        if isinstance(value, list):
            return sorted({json.dumps(shape(child), sort_keys=True) for child in value})
        return "null" if value is None else type(value).__name__
    entities = []
    for entity in raw.values():
        if isinstance(entity, dict) and entity.get("__typename") in {"Menu", "PlaceMenuItem"}:
            entities.append({"typename": entity["__typename"], "shape": shape(entity)})
    return sorted({json.dumps(entity, sort_keys=True) for entity in entities})


def detect(before, after, *, transport_status="OK"):
    if transport_status != "OK":
        return {"eligible": False, "classification": "TRANSPORT_FAILURE"}
    old, new = menu_shape(before), menu_shape(after)
    if not old or not new:
        return {"eligible": False, "classification": "INSUFFICIENT_EVIDENCE"}
    if old == new:
        return {"eligible": False, "classification": "VALUE_OR_NO_CHANGE"}
    return {"eligible": True, "classification": "MENU_STRUCTURE_CHANGE",
            "before_shape": old, "after_shape": new}


class Inbox:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS incident (
                fingerprint TEXT PRIMARY KEY, state TEXT NOT NULL,
                manifest TEXT NOT NULL, run_dir TEXT, updated_at TEXT NOT NULL)""")

    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        return db

    def enqueue(self, manifest):
        if not manifest["diagnostic"]["eligible"]:
            return None
        fingerprint = digest({key: manifest[key] for key in
                              ("diagnostic", "base_digest", "contract_version")})
        with self.connect() as db:
            db.execute("INSERT OR IGNORE INTO incident VALUES (?, 'QUEUED', ?, NULL, ?)",
                       (fingerprint, json.dumps(manifest, ensure_ascii=False), now()))
        return fingerprint

    def get(self, fingerprint):
        with self.connect() as db:
            row = db.execute("SELECT * FROM incident WHERE fingerprint=?", (fingerprint,)).fetchone()
        return dict(row) if row else None

    def claim(self, fingerprint, run_dir):
        with self.connect() as db:
            cursor = db.execute("UPDATE incident SET state='RUNNING', run_dir=?, updated_at=? "
                                "WHERE fingerprint=? AND state='QUEUED'",
                                (str(run_dir), now(), fingerprint))
        return cursor.rowcount == 1

    def finish(self, fingerprint, state):
        if state not in {"AWAITING_REVIEW", "HELD", "ERROR"}:
            raise ValueError("invalid terminal state")
        with self.connect() as db:
            cursor = db.execute("UPDATE incident SET state=?, updated_at=? "
                                "WHERE fingerprint=? AND state='RUNNING'", (state, now(), fingerprint))
            if cursor.rowcount != 1:
                raise ValueError("incident is not claimed")

    def hold_after_review(self, fingerprint, run_dir):
        """A review rejection cannot reopen the inbox or authorize another paid run."""
        with self.connect() as db:
            cursor = db.execute("UPDATE incident SET state='HELD', updated_at=? "
                                "WHERE fingerprint=? AND state='AWAITING_REVIEW' AND run_dir=?",
                                (now(), fingerprint, str(run_dir)))
            if cursor.rowcount != 1:
                raise ValueError("review requires this exact awaiting-review run")
