"""Fixed Java entry point for approved full-capture canary/replay.

The process exit code is never a success oracle. The Java runner authenticates
immutable DB authority and returns a projection of durable item/completion rows.
A missing, partial or timed-out result stays UNKNOWN; STATUS cannot replay work.
"""
from dataclasses import dataclass
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

from psycopg2.extras import RealDictCursor

from .approval import canonical, content_digest
from .host_runtime import private_directory, private_file, write_json, atomic_write, require
from .release import ReleaseHold, _jar_digest, _identity


@dataclass(frozen=True)
class JavaBridgeConfig:
    java: Path
    properties: Path
    directory: Path
    timeout_seconds: int = 180

    def validate(self):
        require(Path(self.java).is_absolute() and Path(self.java).is_file() and os.access(self.java, os.X_OK), "FIXED_JAVA_EXECUTABLE_REQUIRED")
        private_file(self.properties)
        private_directory(self.directory)
        require(type(self.timeout_seconds) is int and 1 <= self.timeout_seconds <= 600, "BRIDGE_TIMEOUT_INVALID")
        require(not any(value in str(self.properties) for value in (",", "\n", "\r")), "CONFIGURATION_PATH_INVALID")


class JavaReplayAdapter:
    def __init__(self, config, registry):
        config.validate()
        self.config, self.registry = config, registry

    def _binding(self, command, fence):
        fence.assert_held()
        require(command["service_id"] == fence.service_id and command["writes_enabled"] is False, "REPLAY_SERVICE_CHANGED")
        candidate = command["candidate"]
        require(candidate == self.registry.validated_candidate(candidate["validation_digest"]), "REPLAY_REGISTRY_CHANGED")
        validation = self.registry.validation_document(candidate["validation_digest"])
        plan = self.registry.replay_plan(candidate["validation_digest"])
        policy = self.registry.canary_policy(candidate["canary_policy_digest"])
        replay_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "response-replay:" + command["operation_id"]))
        values = {"release_operation_id": command["operation_id"], "replay_operation_id": replay_id,
            "manifest_digest": command["manifest_digest"], "service_id": command["service_id"],
            "release_id": candidate["release_id"], "generation": candidate["generation"],
            "artifact_digest": candidate["artifact_sha256"],
            **{key: candidate[key] for key in ("parser_digest", "profile_digest", "contract_digest", "contract_version", "policy_digest",
                  "dataops_database_identity", "serving_database_identity", "database_identity")},
            "validation_json": canonical(validation), "validation_digest": content_digest(validation),
            "plan_json": canonical(plan), "plan_digest": content_digest(plan),
            "canary_policy_json": canonical(policy), "canary_policy_digest": content_digest(policy)}
        require(values["validation_digest"] == candidate["validation_digest"] and values["plan_digest"] == candidate["plan_digest"], "REPLAY_PLAN_CHANGED")
        with fence.connection, fence.connection.cursor(cursor_factory=RealDictCursor) as cursor:
            cursor.execute("""SELECT r.approval_digest FROM response_agent_release_operation r
                JOIN response_agent_review_approval a USING(approval_digest)
                WHERE r.operation_id=%s AND r.manifest_digest=%s AND r.service_id=%s
                AND r.state=%s AND r.pending_action=%s AND a.state='CONSUMED'
                AND a.operation_id=r.operation_id AND a.manifest_digest=r.manifest_digest
                AND a.scope='DEPLOY_REPLAY_PROPOSE' FOR SHARE OF r,a""",
                (command["operation_id"], command["manifest_digest"], command["service_id"], command["action"].upper() + "_IN_FLIGHT", command["action"]))
            row = cursor.fetchone()
            require(row is not None, "CONSUMED_REPLAY_AUTHORITY_REQUIRED")
            values["approval_digest"] = row["approval_digest"]
            cursor.execute("INSERT INTO response_agent_execution_binding(" + ",".join(values) + ") VALUES ("
                + ",".join(["%s"] * len(values)) + ") ON CONFLICT (release_operation_id) DO NOTHING", tuple(values.values()))
            cursor.execute("SELECT " + ",".join(values) + " FROM response_agent_execution_binding WHERE release_operation_id=%s", (command["operation_id"],))
            stored = cursor.fetchone()
            require(stored is not None and all(str(stored[key]) == str(value) for key, value in values.items()), "EXECUTION_BINDING_CHANGED")
        return values

    def _directory(self, operation, action):
        import re
        require(re.fullmatch(r"release-[0-9a-f]{32}", operation or "") and action in {"canary", "replay"}, "REPLAY_OPERATION_INVALID")
        return private_directory(self.config.directory / (operation + "-" + action))

    def _invoke(self, command, fence, directory, *, status):
        fence.assert_held()
        artifact = command["prepared"]["candidate_path"]
        require(_jar_digest(artifact) == command["candidate"]["artifact_sha256"], "EXECUTING_ARTIFACT_CHANGED")
        invocation = uuid.uuid4().hex
        receipt_path, log = directory / (invocation + ".json"), directory / (invocation + ".log")
        atomic_write(log, b"")
        argv = [str(self.config.java), "-Dloader.main=sweetgirl.dataops.replay.ReplayBridgeMain", "-cp", artifact,
            "org.springframework.boot.loader.launch.PropertiesLauncher",
            "--spring.config.additional-location=file:" + str(self.config.properties),
            "--spring.main.web-application-type=none", "--spring.flyway.enabled=false", "--spring.sql.init.mode=never",
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--dataops.agent.runtime.enabled=false", "--dataops.agent.execution.enabled=true",
            "--dataops.agent.replay.enabled=true", "--dataops.agent.apply.enabled=false",
            "--dataops.serving.write-enabled=false", "--dataops.reconcile.mode=PROPOSAL_ONLY",
            "--dataops.agent.execution.release-operation-id=" + command["operation_id"],
            "--dataops.agent.execution.manifest-digest=" + command["manifest_digest"],
            "--dataops.agent.execution.generation=" + command["candidate"]["generation"],
            "--dataops.agent.execution.fence-epoch=" + str(fence.token),
            "--dataops.agent.replay.action=" + ("STATUS" if status else command["action"].upper()),
            "--dataops.agent.replay.phase=" + command["action"].upper(),
            "--dataops.agent.replay.receipt-path=" + str(receipt_path)]
        write_json(directory / (invocation + "-invocation.json"), {"argv": argv, "status_only": status, "fence_epoch": fence.token})
        process = None
        try:
            with log.open("ab") as output:
                fence.assert_held()
                process = subprocess.Popen(argv, cwd=self.config.directory, stdout=output, stderr=subprocess.STDOUT,
                    env={"PATH": "/usr/bin:/bin", "HOME": str(Path.home()), "LANG": "C.UTF-8"}, start_new_session=True)
                deadline = time.monotonic() + self.config.timeout_seconds
                while process.poll() is None:
                    fence.assert_held()
                    if time.monotonic() >= deadline:
                        raise ReleaseHold("REPLAY_PROCESS_TIMEOUT_UNKNOWN")
                    time.sleep(0.1)
                fence.assert_held()
            require(receipt_path.exists(), "REPLAY_RECEIPT_MISSING")
            require(private_file(receipt_path).stat().st_size <= 8 * 1024 * 1024, "REPLAY_RECEIPT_TOO_LARGE")
            result = json.loads(receipt_path.read_text())
            self._check_result(result, command)
            return result
        finally:
            if process is not None and process.poll() is None:
                # Only the child created by this invocation, never a discovered PID.
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=5)

    def _check_result(self, result, command):
        require(isinstance(result, dict) and result.get("schema_version") == 1
            and result.get("operation_id") == command["operation_id"]
            and result.get("replay_operation_id") == str(uuid.uuid5(uuid.NAMESPACE_URL, "response-replay:" + command["operation_id"]))
            and result.get("manifest_digest") == command["manifest_digest"] and result.get("generation") == command["candidate"]["generation"]
            and result.get("phase") == command["action"].upper() and result.get("targets_digest") == command["targets_digest"]
            and result.get("runtime") == _identity(command["candidate"])
            and type(result.get("operating_writes")) is int and result["operating_writes"] == 0
            and type(result.get("passed")) is bool and type(result.get("complete")) is bool
            and result.get("outcome") in {"DONE", "UNKNOWN"}, "JAVA_RECEIPT_SCOPE_CHANGED")
        items = result.get("items")
        require(isinstance(items, list) and len(items) == len(command["targets"])
            and [row.get("capture_id") for row in items] == [row["capture_id"] for row in command["targets"]], "JAVA_RECEIPT_COVERAGE_CHANGED")
        statuses = {"PARSED", "PROPOSED", "NO_CHANGE", "STALE", "HELD", "FAILED", "UNKNOWN", "NOT_ATTEMPTED"}
        require(all(row.get("status") in statuses for row in items)
            and result["complete"] == (result["outcome"] == "DONE"), "JAVA_RECEIPT_COMPLETION_CHANGED")

    def _execute(self, command, fence):
        directory = self._directory(command["operation_id"], command["action"])
        intent = directory / "intent.json"
        if intent.exists():
            previous = json.loads(private_file(intent).read_text())
            require(previous["command_digest"] == content_digest(command), "REPLAY_OPERATION_REBOUND")
            raise ReleaseHold("REPLAY_RECONCILIATION_REQUIRED")
        self._binding(command, fence)
        # Written before Popen. A crash after this boundary is never retried as fresh execution.
        write_json(intent, {"command": command, "command_digest": content_digest(command)})
        result = self._invoke(command, fence, directory, status=False)
        write_json(directory / "latest.json", result)
        require(result["outcome"] == "DONE", "REPLAY_OUTCOME_UNKNOWN")
        return result

    def canary(self, command, fence):
        require(command["action"] == "canary", "REPLAY_ACTION_CHANGED")
        return self._execute(command, fence)

    def replay(self, command, fence):
        require(command["action"] == "replay", "REPLAY_ACTION_CHANGED")
        return self._execute(command, fence)

    def reconcile(self, operation_id, action, fence):
        directory = self._directory(operation_id, action)
        intent = directory / "intent.json"
        if not intent.exists():
            # A local file's absence does not prove another controller never launched an action.
            return {"outcome": "UNKNOWN"}
        frozen = json.loads(private_file(intent).read_text())
        require(content_digest(frozen["command"]) == frozen["command_digest"], "REPLAY_INTENT_CHANGED")
        result = self._invoke(frozen["command"], fence, directory, status=True)
        write_json(directory / "latest.json", result)
        return {"outcome": result["outcome"], **({"result": result} if result["outcome"] == "DONE" else {})}
