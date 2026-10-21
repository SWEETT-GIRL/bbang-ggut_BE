"""Controller-owned Mac service adapter. No model imports this module.

Installation is explicit: an existing arbitrary PID is never adopted or killed.
The controller must first enroll a service started from its own launch plan.
Every transition holds both the PostgreSQL fence and a local flock. A lost or
ambiguous service-manager result leaves an IN_FLIGHT receipt for reconciliation.
"""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
import fcntl
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import secrets
import stat
import subprocess
import time
import urllib.error
import urllib.request

from .approval import ID, SHA, canonical, content_digest
from .release import ReleaseHold, _jar_digest, IDENTITY_FIELDS


RUNTIME_FIELDS = (*IDENTITY_FIELDS, "generation", "dataops_database_identity", "serving_database_identity")


def require(condition, code):
    if not condition:
        raise ReleaseHold(code)


def private_directory(path):
    path = Path(path)
    require(path.is_absolute(), "ABSOLUTE_PRIVATE_DIRECTORY_REQUIRED")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(path.resolve() == path and path.is_dir() and path.stat().st_uid == os.getuid()
            and stat.S_IMODE(path.stat().st_mode) == 0o700, "PRIVATE_DIRECTORY_REQUIRED")
    return path


def private_file(path):
    path = Path(path)
    require(path.is_absolute() and path.resolve() == path and path.is_file()
            and path.stat().st_uid == os.getuid() and stat.S_IMODE(path.stat().st_mode) == 0o600,
            "PRIVATE_CONFIGURATION_REQUIRED")
    return path


def atomic_write(path, data, mode=0o600):
    path = Path(path)
    require(not path.is_symlink() and path.parent.resolve() == path.parent, "OWNED_PATH_REQUIRED")
    temporary = path.parent / (".pending-" + secrets.token_hex(16))
    try:
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
        with os.fdopen(fd, "wb") as output:
            # launchd jobs run with umask 077; immutable JARs must still have the
            # exact verified 0444 mode, rather than the masked 0400 mode.
            os.fchmod(output.fileno(), mode)
            output.write(data if isinstance(data, bytes) else data.encode())
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if temporary.exists():
            temporary.unlink()


def write_json(path, value):
    atomic_write(path, canonical(value) + "\n")


@dataclass(frozen=True)
class ManagedServiceConfig:
    service_id: str
    root: Path
    java: Path
    properties: Path
    port: int
    readiness_seconds: int = 45

    def validate(self):
        require(isinstance(self.service_id, str) and ID.fullmatch(self.service_id), "SERVICE_ID_INVALID")
        require(type(self.port) is int and 1024 <= self.port <= 65535 and 1 <= self.readiness_seconds <= 60, "SERVICE_PORT_OR_TIMEOUT_INVALID")
        require(Path(self.java).is_absolute() and Path(self.java).is_file() and os.access(self.java, os.X_OK), "FIXED_JAVA_EXECUTABLE_REQUIRED")
        private_directory(self.root)
        private_file(self.properties)
        require(not any(mark in str(self.properties) for mark in (",", "\n", "\r")), "CONFIGURATION_PATH_INVALID")


class PgActiveRuntime:
    """Write observed state only while the caller owns the same service fence."""
    def enroll(self, live, fence):
        fence.assert_held()
        require(fence.service_id == live["service_id"], "SERVICE_FENCE_MISMATCH")
        with fence.connection, fence.connection.cursor() as cursor:
            cursor.execute("SELECT " + ",".join(RUNTIME_FIELDS) + ",state,fence_epoch FROM response_agent_active_runtime WHERE service_id=%s FOR UPDATE", (live["service_id"],))
            existing = cursor.fetchone()
            if existing is not None:
                require(existing[:-1] == (*[live[key] for key in RUNTIME_FIELDS], "ACTIVE") and existing[-1] <= fence.token,
                        "SERVICE_ALREADY_ENROLLED")
                return # recovered DB commit before current.json was persisted
            fields = ("service_id", *RUNTIME_FIELDS)
            cursor.execute("INSERT INTO response_agent_active_runtime(" + ",".join(fields) + ",state,fence_epoch) VALUES ("
                           + ",".join(["%s"] * len(fields)) + ",'ACTIVE',%s)", (*[live[key] for key in fields], fence.token))

    def transition(self, expected, live, state, fence):
        fence.assert_held()
        require(state in {"ACTIVE", "DRAINING", "STOPPED"} and expected["service_id"] == fence.service_id
                and live["service_id"] == fence.service_id, "RUNTIME_TRANSITION_INVALID")
        with fence.connection, fence.connection.cursor() as cursor:
            cursor.execute("UPDATE response_agent_active_runtime SET " + ",".join(field + "=%s" for field in RUNTIME_FIELDS)
                + ",state=%s,fence_epoch=%s,updated_at=CURRENT_TIMESTAMP WHERE service_id=%s AND generation=%s AND artifact_sha256=%s AND fence_epoch<=%s",
                (*[live[key] for key in RUNTIME_FIELDS], state, fence.token, fence.service_id, expected["generation"], expected["artifact_sha256"], fence.token))
            if cursor.rowcount != 1:
                # DB commit can precede a controller crash while writing its local receipt.
                cursor.execute("SELECT " + ",".join(RUNTIME_FIELDS) + ",state FROM response_agent_active_runtime WHERE service_id=%s AND fence_epoch<=%s",
                               (fence.service_id, fence.token))
                row = cursor.fetchone()
                require(row == (*[live[key] for key in RUNTIME_FIELDS], state), "ACTIVE_RUNTIME_CAS_FAILED")


class LoopbackRuntimeProbe:
    def __init__(self, port, token_path):
        self.port, self.token_path = port, token_path
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())

    def request(self, *, drain_generation=None):
        token = private_file(self.token_path).read_text().strip()
        require(re.fullmatch(r"[0-9a-f]{64}", token), "RUNTIME_TOKEN_INVALID")
        headers = {"X-Agent-Runtime-Token": token}
        if drain_generation is not None:
            headers["X-Agent-Expected-Generation"] = drain_generation
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}/internal/agent-runtime" + ("/drain" if drain_generation else ""),
            headers=headers, method="POST" if drain_generation else "GET", data=b"" if drain_generation else None)
        try:
            with self.opener.open(request, timeout=5) as response:
                require(response.status == 200, "RUNTIME_RESPONSE_UNAVAILABLE")
                body = response.read(16385)
                require(len(body) <= 16384, "RUNTIME_RESPONSE_TOO_LARGE")
                value = json.loads(body)
        except (OSError, ValueError):
            raise OSError("RUNTIME_PROBE_UNKNOWN") from None
        require(isinstance(value, dict), "RUNTIME_RESPONSE_INVALID")
        return value


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise OSError("RUNTIME_REDIRECT_FORBIDDEN")


class LaunchdDriver:
    """Fixed per-user launchd label, no shell, no PID-based termination."""
    def __init__(self, config, *, launch_agents_directory=None):
        self.config = config
        self.label = "com.bbanggut.dataops.agent." + config.service_id
        self.domain = "gui/" + str(os.getuid())
        self.launch_agents = Path(launch_agents_directory) if launch_agents_directory is not None else Path.home() / "Library/LaunchAgents"

    def _installation_path(self):
        self.launch_agents.mkdir(mode=0o700, parents=True, exist_ok=True)
        require(self.launch_agents.is_absolute() and self.launch_agents.resolve() == self.launch_agents
                and self.launch_agents.stat().st_uid == os.getuid() and self.launch_agents.stat().st_mode & 0o022 == 0,
                "OWNED_LAUNCH_AGENTS_DIRECTORY_REQUIRED")
        return self.launch_agents / (self.label + ".plist")

    def absent(self):
        result = self._run(["print", self.domain + "/" + self.label])
        if result.returncode == 0:
            return False
        require(result.returncode == 113 and b"Could not find service" in result.stderr,
                "LAUNCHD_JOB_STATE_UNKNOWN")
        return True

    def _run(self, args):
        return subprocess.run(["/bin/launchctl", *args], capture_output=True, timeout=15, check=False,
                              env={"PATH": "/usr/bin:/bin", "HOME": str(Path.home()), "LANG": "C.UTF-8"})

    def _loaded_pid(self):
        result = self._run(["print", self.domain + "/" + self.label])
        require(result.returncode == 0, "OWNED_LAUNCHD_JOB_UNAVAILABLE")
        match = re.search(rb"^\s*pid = ([0-9]+)\s*$", result.stdout, re.MULTILINE)
        require(match is not None, "OWNED_LAUNCHD_PID_UNAVAILABLE")
        return int(match.group(1))

    def require_owner(self, plan, live):
        require(plan["driver"] == "launchd" and live["pid"] == self._loaded_pid()
                and live["launch_nonce"] == plan["launch_nonce"], "LAUNCHD_OWNERSHIP_CHANGED")
        path = self.config.root / (plan["launch_nonce"] + ".plist")
        require(private_file(path).read_bytes() == self._plist(plan), "LAUNCHD_PLAN_CHANGED")
        require(private_file(self._installation_path()).read_bytes() == self._plist(plan), "LAUNCHD_INSTALLATION_CHANGED")

    def _plist(self, plan):
        require(hashlib.sha256(private_file(Path(plan["base_properties_path"])).read_bytes()).hexdigest()
                == plan["base_properties_sha256"], "FROZEN_CONFIGURATION_CHANGED")
        return plistlib.dumps({"Label": self.label, "ProgramArguments": plan["argv"],
            "RunAtLoad": True, "KeepAlive": True, "ThrottleInterval": 10, "ExitTimeOut": 30,
            "Umask": 63, "WorkingDirectory": str(self.config.root),
            "StandardOutPath": plan["log_path"], "StandardErrorPath": plan["log_path"],
            "EnvironmentVariables": {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"}}, sort_keys=True)

    def stop(self, plan, live):
        self.require_owner(plan, live)
        require(self._run(["bootout", self.domain + "/" + self.label]).returncode == 0, "LAUNCHD_STOP_UNKNOWN")

    def start(self, plan):
        # The controller records intent before this call. Never unload an unknown job to make room.
        path = self.config.root / (plan["launch_nonce"] + ".plist")
        require(self.absent(), "LAUNCHD_JOB_ALREADY_LOADED")
        destination = self._installation_path()
        ledger = self.config.root / "launchd-installation.json"
        data = self._plist(plan); digest = hashlib.sha256(data).hexdigest()
        if destination.exists():
            require(ledger.exists(), "FOREIGN_LAUNCHD_INSTALLATION")
            old = json.loads(private_file(ledger).read_text())
            actual = hashlib.sha256(private_file(destination).read_bytes()).hexdigest()
            require(old.get("label") == self.label and old.get("path") == str(destination)
                    and actual in {old.get("desired_digest"), old.get("previous_digest")}, "FOREIGN_LAUNCHD_INSTALLATION")
        else:
            actual = None
        # Recoverable ownership intent precedes the login-persistent plist replacement.
        write_json(ledger, {"label": self.label, "path": str(destination), "previous_digest": actual,
            "desired_digest": digest, "launch_nonce": plan["launch_nonce"]})
        atomic_write(path, data)
        atomic_write(destination, data)
        require(self._run(["bootstrap", self.domain, str(destination)]).returncode == 0, "LAUNCHD_START_UNKNOWN")


class ManagedHostAdapter:
    def __init__(self, config, *, active_runtime=None, driver=None, probe=None):
        config.validate()
        self.config = config
        self.active_runtime = active_runtime or PgActiveRuntime()
        self.driver = driver or LaunchdDriver(config)
        self.token_path = config.root / "control.token"
        self.probe = probe or LoopbackRuntimeProbe(config.port, self.token_path)
        self.receipts = private_directory(config.root / "receipts")
        self.releases = private_directory(config.root / "releases")

    @contextmanager
    def _lock(self, fence):
        fence.assert_held()
        require(fence.service_id == self.config.service_id, "SERVICE_FENCE_MISMATCH")
        path = self.config.root / "host.lock"
        fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            fence.assert_held()
            epoch_path = self.config.root / "epoch.json"
            previous = json.loads(private_file(epoch_path).read_text())["epoch"] if epoch_path.exists() else 0
            require(type(fence.token) is int and fence.token >= previous, "HOST_FENCE_STALE")
            write_json(epoch_path, {"epoch": fence.token})
            yield
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)

    def _current(self):
        return json.loads(private_file(self.config.root / "current.json").read_text())

    def _probe(self, plan):
        live = self.probe.request()
        require(live.get("service_id") == self.config.service_id and live.get("launch_nonce") == plan["launch_nonce"]
                and live.get("writes_enabled") is False and all(live.get(key) == plan["runtime"][key] for key in RUNTIME_FIELDS),
                "OBSERVED_RUNTIME_CHANGED")
        require(type(live.get("pid")) is int and live["pid"] > 0, "RUNTIME_PID_INVALID")
        self.driver.require_owner(plan, live)
        return live

    def observe(self, service_id, fence):
        require(service_id == self.config.service_id, "UNKNOWN_SERVICE")
        with self._lock(fence):
            return self._probe(self._current())

    def prepare_initial_plan(self, runtime):
        """Prepare a reviewable launch plan only. Does not install, start, enroll or adopt a process."""
        require(not (self.config.root / "current.json").exists(), "SERVICE_ALREADY_ENROLLED")
        if not self.token_path.exists():
            atomic_write(self.token_path, secrets.token_hex(32) + "\n")
        private_file(self.token_path)
        artifact = self._copy(runtime)
        return self._plan(runtime, artifact)

    def prepare_installation(self, runtime, build_receipt_digest):
        """Durable, idempotent initial plan; filesystem preparation only, no launchd/DB effect."""
        require(isinstance(build_receipt_digest, str) and SHA.fullmatch(build_receipt_digest), "FULL_BUILD_RECEIPT_REQUIRED")
        require(runtime.get("service_id") == self.config.service_id
                and all(isinstance(runtime.get(k), str) and ID.fullmatch(runtime[k]) for k in ("release_id", "generation"))
                and all(isinstance(runtime.get(k), str) and SHA.fullmatch(runtime[k]) for k in RUNTIME_FIELDS if k not in {"release_id", "generation"}),
                "INITIAL_RUNTIME_IDENTITY_INVALID")
        require(runtime["database_identity"] == content_digest({"dataops":runtime["dataops_database_identity"],
                "serving":runtime["serving_database_identity"]}), "DATABASE_COMPOSITE_MISMATCH")
        configuration = {"service_id":self.config.service_id,"root":str(self.config.root),"java":str(self.config.java),
            "properties":str(self.config.properties),"port":self.config.port,
            "properties_digest":hashlib.sha256(private_file(self.config.properties).read_bytes()).hexdigest()}
        request = {"runtime":runtime,"build_receipt_digest":build_receipt_digest,"configuration":configuration}
        fd = os.open(self.config.root / "host.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            path = self.config.root / "initial.json"
            if path.exists():
                receipt = self._initial()
                require(receipt["request_digest"] == content_digest(request), "INITIAL_INSTALLATION_REBOUND")
                return receipt
            plan = self.prepare_initial_plan(runtime)
            receipt = {"schema_version":1,"state":"PREPARED","request":request,"request_digest":content_digest(request),
                "plan":plan,"plan_digest":content_digest(plan)}
            write_json(path, receipt)
            return receipt
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN);os.close(fd)

    def _initial(self):
        receipt = json.loads(private_file(self.config.root / "initial.json").read_text())
        require(receipt.get("schema_version") == 1 and receipt.get("state") in {"PREPARED","IN_FLIGHT","ENROLLED"}
                and content_digest(receipt["request"]) == receipt["request_digest"]
                and content_digest(receipt["plan"]) == receipt["plan_digest"], "INITIAL_INSTALLATION_CHANGED")
        plan = receipt["plan"]
        configuration = receipt["request"]["configuration"]
        require(all(configuration.get(key) == value for key, value in {
            "service_id":self.config.service_id,"root":str(self.config.root),"java":str(self.config.java),
            "properties":str(self.config.properties),"port":self.config.port}.items())
            and receipt["request"]["runtime"]["service_id"] == self.config.service_id
            and plan["runtime"] == {key:receipt["request"]["runtime"][key] for key in RUNTIME_FIELDS},
            "INITIAL_SERVICE_CONFIGURATION_CHANGED")
        require(_jar_digest(plan["artifact_path"]) == plan["runtime"]["artifact_sha256"]
                and hashlib.sha256(private_file(Path(plan["base_properties_path"])).read_bytes()).hexdigest()
                    == plan["base_properties_sha256"], "INITIAL_ARTIFACT_OR_CONFIGURATION_CHANGED")
        return receipt

    def _enroll_initial(self, receipt, live, fence):
        require(live.get("healthy") is True, "INITIAL_RUNTIME_NOT_READY")
        plan = receipt["plan"]
        current = self.config.root / "current.json"
        if current.exists():
            require(self._current() == plan, "SERVICE_ALREADY_ENROLLED")
        self.active_runtime.enroll(live, fence)
        write_json(current, plan)
        self._admission(plan, True)
        write_json(self.config.root / "initial.json", {**receipt,"state":"ENROLLED"})
        return self._probe(plan)

    def start_initial(self, fence, *, resume_absent=False):
        """Only an explicit resume may retry an ambiguous intent, after launchd proves the job absent."""
        with self._lock(fence):
            receipt = self._initial()
            if receipt["state"] == "ENROLLED":
                return self._probe(receipt["plan"])
            if receipt["state"] == "IN_FLIGHT":
                require(resume_absent and self.driver.absent(), "INITIAL_RECONCILIATION_REQUIRED")
            receipt = {**receipt,"state":"IN_FLIGHT","fence_epoch":fence.token}
            write_json(self.config.root / "initial.json", receipt)
            fence.assert_held();self.driver.start(receipt["plan"])
            live = self._wait(receipt["plan"], fence)
            return self._enroll_initial(receipt, live, fence)

    def reconcile_initial(self, fence):
        """Reconcile launch/enrollment evidence; this never starts or retries a process."""
        with self._lock(fence):
            receipt = self._initial()
            if receipt["state"] == "PREPARED":
                return {"outcome":"NOT_STARTED","state":"PREPARED"}
            try:
                live = self._probe(receipt["plan"])
                live = self._enroll_initial(receipt, live, fence)
                return {"outcome":"DONE","state":"ENROLLED","runtime":live}
            except (OSError,ReleaseHold):
                return {"outcome":"UNKNOWN","state":receipt["state"]}

    def enroll_started(self, plan, fence):
        """Explicit one-time enrollment after operator/installer has started this exact plan."""
        with self._lock(fence):
            require(not (self.config.root / "current.json").exists(), "SERVICE_ALREADY_ENROLLED")
            live = self._probe(plan)
            require(live.get("healthy") is True, "INITIAL_RUNTIME_NOT_READY")
            self.active_runtime.enroll(live, fence)
            write_json(self.config.root / "current.json", plan)
            self._admission(plan, True)
            return self._probe(plan)

    def _admission(self, plan, opened):
        path = Path(plan["admission_path"])
        require(path == self.config.root / (plan["launch_nonce"] + ".admission"), "ADMISSION_PLAN_CHANGED")
        private_file(path)
        atomic_write(path, "OPEN\n" if opened else "CLOSED\n")

    def _plan(self, runtime, artifact):
        nonce = secrets.token_hex(16)
        props = self.config.root / (nonce + ".properties")
        admission = self.config.root / (nonce + ".admission")
        atomic_write(admission, "CLOSED\n")
        # Freeze private configuration for reboot recovery; later edits require a new launch plan.
        frozen_properties = self.config.root / (nonce + "-base.properties")
        atomic_write(frozen_properties, private_file(self.config.properties).read_bytes())
        # Properties are controller-generated; all secrets stay in the private pre-existing configuration file.
        values = {"server.address": "127.0.0.1", "server.port": self.config.port,
            "dataops.agent.runtime.enabled": "true", "dataops.agent.runtime.service-id": self.config.service_id,
            "dataops.agent.runtime.release-id": runtime["release_id"], "dataops.agent.runtime.generation": runtime["generation"],
            "dataops.agent.runtime.launch-nonce": nonce, "dataops.agent.runtime.token-file": str(self.token_path),
            "dataops.agent.runtime.admission-file": str(admission)}
        require(all("\n" not in str(value) and "\r" not in str(value) and "\\" not in str(value) for value in values.values()), "LAUNCH_PROPERTY_INVALID")
        atomic_write(props, "".join(key + "=" + str(value) + "\n" for key, value in values.items()))
        argv = [str(self.config.java), "-jar", str(artifact),
            "--spring.config.additional-location=file:" + str(frozen_properties) + ",file:" + str(props),
            "--spring.flyway.enabled=false", "--spring.sql.init.mode=never", "--spring.jpa.hibernate.ddl-auto=validate",
            "--dataops.serving.write-enabled=false", "--dataops.reconcile.mode=PROPOSAL_ONLY",
            "--dataops.agent.execution.enabled=false", "--dataops.agent.replay.enabled=false", "--dataops.agent.apply.enabled=false",
            "--spring.main.web-application-type=servlet"]
        # Force runtime isolation on argv as well: base configuration cannot override these settings.
        argv += ["--" + key + "=" + str(value) for key, value in values.items()]
        atomic_write(self.config.root / (nonce + ".log"), b"")
        return {"schema_version": 1, "driver": "launchd", "launch_nonce": nonce,
                "runtime": {key: runtime[key] for key in RUNTIME_FIELDS}, "argv": argv,
                "log_path": str(self.config.root / (nonce + ".log")), "artifact_path": str(artifact),
                "admission_path": str(admission), "base_properties_path": str(frozen_properties),
                "base_properties_sha256": hashlib.sha256(frozen_properties.read_bytes()).hexdigest()}

    def _copy(self, runtime):
        digest = runtime["artifact_sha256"]
        require(SHA.fullmatch(digest) and _jar_digest(runtime["artifact_path"]) == digest, "ARTIFACT_DIGEST_CHANGED")
        destination = self.releases / (digest + ".jar")
        if not destination.exists():
            # Atomic copy and a second digest check before the path becomes executable.
            with Path(runtime["artifact_path"]).open("rb") as source:
                atomic_write(destination, source.read(), mode=0o444)
        require(_jar_digest(destination) == digest and stat.S_IMODE(destination.stat().st_mode) == 0o444, "IMMUTABLE_ARTIFACT_CHANGED")
        return destination

    def _receipt_path(self, operation, action):
        require(isinstance(operation, str) and re.fullmatch(r"release-[0-9a-f]{32}", operation)
                and action in {"prepare", "drain", "start", "rollback"}, "HOST_OPERATION_INVALID")
        return self.receipts / (operation + "-" + action + ".json")

    def _effect(self, command, fence, effect):
        with self._lock(fence):
            require(command["service_id"] == self.config.service_id and command["writes_enabled"] is False, "HOST_SCOPE_CHANGED")
            path = self._receipt_path(command["operation_id"], command["action"])
            if path.exists():
                old = json.loads(private_file(path).read_text())
                require(old["command_digest"] == content_digest(command), "HOST_OPERATION_REBOUND")
                require(old["state"] == "DONE", "HOST_RECONCILIATION_REQUIRED")
                return old["result"]
            receipt = {"state": "IN_FLIGHT", "command": command, "command_digest": content_digest(command), "fence_epoch": fence.token}
            write_json(path, receipt)
            result = effect(command, fence, receipt, path)
            fence.assert_held()
            write_json(path, {**receipt, "state": "DONE", "result": result})
            return result

    def prepare(self, command, fence):
        return self._effect(command, fence, lambda c, f, r, p: {"operation_id": c["operation_id"],
            "base_path": str(self._copy(c["base"])), "candidate_path": str(self._copy(c["candidate"]))})

    def drain(self, command, fence):
        def effect(c, f, receipt, path):
            plan = self._current(); live = self._probe(plan)
            require(live["generation"] == c["expected_generation"], "HOST_GENERATION_CHANGED")
            self._admission(plan, False)
            self.active_runtime.transition(live, live, "DRAINING", f)
            f.assert_held()
            self.probe.request(drain_generation=live["generation"])
            self._wait(plan, f, drained=True)
            return {"operation_id": c["operation_id"]}
        return self._effect(command, fence, effect)

    def _wait(self, plan, fence, *, drained=False):
        deadline = time.monotonic() + self.config.readiness_seconds
        last = None
        while True:
            fence.assert_held()
            try:
                last = self._probe(plan)
                if last.get("drained" if drained else "healthy") is True:
                    return last
            except OSError:
                last = None
            if time.monotonic() >= deadline:
                if last is not None and not drained:
                    return last # explicit unhealthy identity permits a guarded rollback
                raise ReleaseHold("DRAIN_OR_READINESS_UNKNOWN")
            time.sleep(0.2)

    def _switch(self, command, fence, receipt, path):
        old_plan = self._current(); old = self._probe(old_plan)
        require(old["generation"] == command["expected_generation"], "HOST_GENERATION_CHANGED")
        if command["action"] == "rollback":
            self._admission(old_plan, False)
            self.active_runtime.transition(old, old, "DRAINING", fence)
            self.probe.request(drain_generation=old["generation"])
            old = self._wait(old_plan, fence, drained=True)
        require(old.get("drained") is True, "OLD_RUNTIME_NOT_DRAINED")
        desired = dict(command["candidate"] if command["action"] == "start" else command["base"])
        if command["action"] == "rollback":
            desired["generation"] = command["rollback_generation"]
            desired["release_id"] = command["rollback_release_id"]
        artifact = command["prepared"]["candidate_path" if command["action"] == "start" else "base_path"]
        require(_jar_digest(artifact) == desired["artifact_sha256"], "PREPARED_ARTIFACT_CHANGED")
        plan = self._plan(desired, artifact)
        receipt.update({"previous_plan": old_plan, "desired_plan": plan})
        write_json(path, receipt) # switch intent is durable before stop/start
        fence.assert_held(); self.driver.stop(old_plan, old)
        self.active_runtime.transition(old, old, "STOPPED", fence)
        fence.assert_held(); self.driver.start(plan)
        live = self._wait(plan, fence)
        self.active_runtime.transition(old, live, "ACTIVE", fence)
        write_json(self.config.root / "current.json", plan)
        if live.get("healthy") is True:
            self._admission(plan, True)
        return {"operation_id": command["operation_id"]}

    def start(self, command, fence):
        return self._effect(command, fence, self._switch)

    def rollback(self, command, fence):
        return self._effect(command, fence, self._switch)

    def reconcile(self, operation_id, action, fence):
        with self._lock(fence):
            path = self._receipt_path(operation_id, action)
            if not path.exists():
                return {"outcome": "NOT_STARTED"} # no effect can precede its intent file
            receipt = json.loads(private_file(path).read_text())
            require(content_digest(receipt["command"]) == receipt["command_digest"], "HOST_RECEIPT_CHANGED")
            if receipt["state"] == "DONE":
                return {"outcome": "DONE", "result": receipt["result"]}
            command = receipt["command"]
            try:
                if action == "prepare":
                    base_path = self.releases / (command["base"]["artifact_sha256"] + ".jar")
                    candidate_path = self.releases / (command["candidate"]["artifact_sha256"] + ".jar")
                    require(_jar_digest(base_path) == command["base"]["artifact_sha256"] and _jar_digest(candidate_path) == command["candidate"]["artifact_sha256"], "INCOMPLETE_PREPARE")
                    result = {"operation_id": operation_id, "base_path": str(base_path), "candidate_path": str(candidate_path)}
                elif action == "drain":
                    live = self._probe(self._current())
                    require(live["generation"] == command["expected_generation"] and live.get("drained") is True, "DRAIN_UNKNOWN")
                    result = {"operation_id": operation_id}
                else:
                    plan = receipt["desired_plan"]; live = self._probe(plan)
                    # A failed/unready but identified start is still an executed start; controller handles rollback.
                    old = {"service_id": self.config.service_id, **receipt["previous_plan"]["runtime"]}
                    current_path = self.config.root / "current.json"
                    if self._current()["launch_nonce"] != plan["launch_nonce"]:
                        self.active_runtime.transition(old, live, "ACTIVE", fence)
                        write_json(current_path, plan)
                    if live.get("healthy") is True:
                        self._admission(plan, True)
                    result = {"operation_id": operation_id}
                write_json(path, {**receipt, "state": "DONE", "result": result})
                return {"outcome": "DONE", "result": result}
            except (OSError, KeyError, ReleaseHold):
                return {"outcome": "UNKNOWN"}
