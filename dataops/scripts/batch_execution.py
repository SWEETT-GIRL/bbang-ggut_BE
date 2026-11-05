"""V0 workflow journal. JSON is evidence; Prometheus gauges are the latest observation.

Run IDs belong in files/logs, never in metric labels. RUNNING without a finish is
unresolved (possibly still active), not inferred success or failure.
"""
import argparse
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime, timezone
import fcntl
import json
import math
import os
from pathlib import Path
import signal
import tempfile
import time
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen
from uuid import uuid4

CURRENT_RUN = ContextVar("dataops_workflow_run", default=None)


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".write-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def outcome(code):
    if code == 0:
        return "COMPLETED"
    if code == 3:
        return "BLOCKED"
    if code == 4:
        return "INCOMPLETE"
    if code == 75:
        return "REJECTED_OVERLAP"
    if code < 0 or code in (130, 143):
        return "INTERRUPTED"
    return "FAILED"


class AlreadyRunning(RuntimeError):
    pass


class Interrupted(SystemExit):
    pass


@contextmanager
def termination_signals():
    def stop(signum, _frame):
        raise Interrupted(128 + signum)
    previous = {sig: signal.signal(sig, stop) for sig in (signal.SIGTERM, signal.SIGINT)}
    try:
        yield
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


class WorkflowRun:
    def __init__(self, directory, command):
        self.directory = Path(directory)
        self.started = time.perf_counter()
        self.record = {"schema_version": 1, "workflow_version": "v0", "run_id": str(uuid4()),
                       "command": command, "started_at": utc_now(), "finished_at": None,
                       "duration_seconds": None, "status": "RUNNING", "exit_code": None, "steps": []}
        self.path = self.directory / "runs" / (self.record["run_id"] + ".json")
        self.lock_fd = None

    def persist(self):
        atomic_json(self.path, self.record)

    @contextmanager
    def exclusive(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        with open(self.directory / ".workflow.lock", "a") as handle:
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                self.finish(75)
                raise AlreadyRunning("another weekly workflow is active") from error
            self.lock_fd = handle.fileno()
            try:
                yield
            finally:
                self.lock_fd = None
                # Close only. A child inherits this description so a killed parent
                # cannot release the lock while its child is still doing work.

    def __enter__(self):
        self.persist()
        self.token = CURRENT_RUN.set(self)
        self.publish()
        return self

    def __exit__(self, kind, error, traceback):
        try:
            if self.record["finished_at"] is None:
                code = error.code if isinstance(error, SystemExit) and isinstance(error.code, int) else 1
                self.record["error_type"] = kind.__name__ if kind else "MissingExitStatus"
                self.finish(code)
            self.publish()
        finally:
            CURRENT_RUN.reset(self.token)

    def finish(self, code):
        self.record.update(status=outcome(code), exit_code=code, finished_at=utc_now(),
                           duration_seconds=max(0.0, time.perf_counter() - self.started))
        self.persist()

    @contextmanager
    def step(self, name):
        step = {"name": name, "started_at": utc_now(), "status": "RUNNING", "exit_code": None}
        self.record["steps"].append(step)
        self.persist()
        started = time.perf_counter()
        try:
            yield step
        except BaseException as error:
            code = error.code if isinstance(error, SystemExit) and isinstance(error.code, int) else 1
            step.update(exit_code=code, error_type=type(error).__name__)
            raise
        finally:
            code = step["exit_code"] if step["exit_code"] is not None else 1
            step.update(status=outcome(code), finished_at=utc_now(),
                        duration_seconds=max(0.0, time.perf_counter() - started))
            self.persist()

    def publish(self):
        endpoint = os.environ.get("DATAOPS_PUSHGATEWAY_URL", "").strip()
        if not endpoint:
            return
        try:
            parsed = urlsplit(endpoint)
            if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.query or parsed.fragment:
                raise ValueError("invalid Pushgateway URL")
            body = metrics(self.directory / "runs", self.record["command"]).encode("utf-8")
            path = "/metrics/job/dataops-workflow/workflow/" + quote(self.record["command"], safe="")
            request = Request(endpoint.rstrip("/") + path, data=body, method="PUT",
                              headers={"Content-Type": "text/plain; version=0.0.4; charset=utf-8"})
            with urlopen(request, timeout=3) as response:
                if response.status != 200:
                    raise OSError("unexpected Pushgateway response")
            self.record["metrics_publish_status"] = "SUCCESS"
        except Exception as error:
            # Telemetry delivery must not retry an already successful business operation.
            self.record["metrics_publish_status"] = "FAILED"
            self.record["metrics_error_type"] = type(error).__name__
            print(f"[workflow] metrics publish failed: {type(error).__name__}", flush=True)
        self.persist()


def read_records(directory):
    records, invalid = [], 0
    for path in Path(directory).glob("*.json"):
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
            if value["schema_version"] != 1 or not isinstance(value["steps"], list):
                raise ValueError("unsupported record")
            datetime.fromisoformat(value["started_at"])
            if not isinstance(value["command"], str) or value["status"] not in {
                "RUNNING", "COMPLETED", "FAILED", "BLOCKED", "INCOMPLETE", "INTERRUPTED", "REJECTED_OVERLAP"
            }:
                raise ValueError("invalid record")
            if value["status"] != "RUNNING":
                datetime.fromisoformat(value["finished_at"])
                if not isinstance(value["exit_code"], int) or not isinstance(value["duration_seconds"], (float, int)) \
                        or not math.isfinite(value["duration_seconds"]) or value["duration_seconds"] < 0:
                    raise ValueError("invalid completion")
            records.append(value)
        except (OSError, ValueError, KeyError, TypeError):
            invalid += 1
    return sorted(records, key=lambda row: row["started_at"]), invalid


def summarize(directory):
    records, invalid = read_records(directory)
    workflows = {}
    for record in records:
        group = workflows.setdefault(record["command"], {"runs": 0, "outcomes": {}, "completed_durations_seconds": []})
        group["runs"] += 1
        status = record["status"]
        group["outcomes"][status] = group["outcomes"].get(status, 0) + 1
        if status == "COMPLETED":
            group["completed_durations_seconds"].append(record["duration_seconds"])
    for group in workflows.values():
        durations = sorted(group.pop("completed_durations_seconds"))
        group["completed_duration_p95_seconds"] = durations[math.ceil(len(durations) * .95) - 1] if durations else None
    return {"schema_version": 1, "invalid_records": invalid, "workflows": workflows,
            "note": "RUNNING is active or unresolved; process success does not prove serving completion."}


def metrics(directory, command):
    records, invalid = read_records(directory)
    selected = [row for row in records if row["command"] == command and row["status"] != "REJECTED_OVERLAP"]
    if not selected:
        return ""
    latest = selected[-1]
    finished = [row for row in selected if row["finished_at"]]
    successful = [row for row in finished if row["status"] == "COMPLETED"]
    last = finished[-1] if finished else None
    timestamp = lambda value: datetime.fromisoformat(value).timestamp() if value else 0
    values = {
        "running": int(latest["status"] == "RUNNING"),
        "last_start_timestamp_seconds": timestamp(latest["started_at"]),
        "last_finish_timestamp_seconds": timestamp(last["finished_at"]) if last else 0,
        "last_success_timestamp_seconds": timestamp(successful[-1]["finished_at"]) if successful else 0,
        "last_exit_code": last["exit_code"] if last else -1,
        "last_duration_seconds": last["duration_seconds"] if last else 0,
        "invalid_journal_records": invalid,
    }
    lines = []
    for suffix, value in values.items():
        name = "bbang_dataops_workflow_" + suffix
        lines.extend([f"# HELP {name} Latest observed workflow {suffix}; see monitoring README for scope.",
                      f"# TYPE {name} gauge", f"{name} {value}"])
    for status in ("COMPLETED", "FAILED", "BLOCKED", "INCOMPLETE", "INTERRUPTED", "REJECTED_OVERLAP", "RUNNING"):
        count = sum(row["command"] == command and row["status"] == status for row in records)
        name = "bbang_dataops_workflow_observed_runs"
        if status == "COMPLETED":
            lines.append(f"# HELP {name} Number of locally retained run records by outcome; not a counter.")
            lines.append(f"# TYPE {name} gauge")
        lines.append(f'{name}{{outcome="{status}"}} {count}')
    if last:
        item_names = {"attempted", "changed", "unchanged", "partial", "held", "failed", "pages_fetched",
                      "baseline_stores", "baseline_mapped", "baseline_unmapped", "baseline_ambiguous",
                      "gate_passed", "gate_held", "gate_blocked", "gate_need_menu", "gate_review_pending",
                      "public_raw", "public_new", "public_upd", "public_unobserved",
                      "candidates_input", "candidates_inserted", "candidates_existing", "candidates_registered", "candidates_held",
                      "closures_matched_changes", "closures_observed", "closures_attempted", "closures_proposed",
                      "closures_held", "closures_unknown", "closures_deferred"}
        name = "bbang_dataops_workflow_last_items"
        lines.extend([f"# HELP {name} Latest cycle artifact counts; gauge, not a distributed counter.", f"# TYPE {name} gauge"])
        for kind, value in sorted(last.get("items", {}).items()):
            if kind in item_names and type(value) in (int, float) and math.isfinite(value) and value >= 0:
                lines.append(f'{name}{{result="{kind}"}} {value}')
        for metric in ("duration_seconds", "exit_code"):
            name = "bbang_dataops_step_last_" + metric
            lines.append(f"# HELP {name} Latest completed workflow step {metric}.")
            lines.append(f"# TYPE {name} gauge")
            # Repeated names describe the last invocation, not duplicate samples.
            for step in {step["name"]: step for step in last["steps"]}.values():
                if metric in step and step[metric] is not None:
                    label = json.dumps(step["name"], ensure_ascii=False)
                    lines.append(f'{name}{{step={label}}} {step[metric]}')
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="weekly/runs directory")
    arguments = parser.parse_args()
    print(json.dumps(summarize(arguments.directory), ensure_ascii=False, indent=2))
