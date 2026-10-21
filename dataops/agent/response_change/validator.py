"""Run candidate Java only in a restricted, offline Docker container.

This is a one-case evaluation validator, not a production deployment service.
Independent expected results are never mounted inside the untrusted container.
"""
from __future__ import annotations

from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import stat
import subprocess
import time
import uuid

REPO = Path(__file__).resolve().parents[2]
TRUSTED = Path(__file__).resolve().parent / "trusted"
def runtime_path(name):
    configured = os.environ.get('AGENT_RUNTIME_CACHE_DIRECTORY')
    if configured is None:
        return REPO / 'artifacts/response-agent' / name
    root = Path(configured)
    if not root.is_absolute() or root.resolve() != root or root.is_relative_to(REPO):
        raise ValueError('EXTERNAL_PRIVATE_RUNTIME_CACHE_REQUIRED')
    root.mkdir(mode=0o700,parents=True,exist_ok=True)
    if root.stat().st_uid != os.getuid() or stat.S_IMODE(root.stat().st_mode) != 0o700:
        raise ValueError('EXTERNAL_PRIVATE_RUNTIME_CACHE_REQUIRED')
    return root / name


RUNTIME = runtime_path('validator-runtime')
IMAGE_TAG = "eclipse-temurin:17-jdk"
DEPENDENCIES = (
    ("tools.jackson.core", "jackson-core", "3.1.5"),
    ("tools.jackson.core", "jackson-databind", "3.1.5"),
    ("com.fasterxml.jackson.core", "jackson-annotations", "2.21"),
    ("org.springframework", "spring-context", "7.0.7"),
    ("org.springframework", "spring-core", "7.0.7"),
    ("org.junit.jupiter", "junit-jupiter-api", "6.0.3"),
    ("org.junit.jupiter", "junit-jupiter-engine", "6.0.3"),
    ("org.junit.platform", "junit-platform-commons", "6.0.3"),
    ("org.junit.platform", "junit-platform-engine", "6.0.3"),
    ("org.junit.platform", "junit-platform-launcher", "6.0.3"),
    ("org.assertj", "assertj-core", "3.27.7"),
    ("org.opentest4j", "opentest4j", "1.3.0"),
    ("org.apiguardian", "apiguardian-api", "1.1.2"),
)
MAX_SOURCE_BYTES = 128_000
MAX_INPUT_BYTES = 2_000_000
MAX_OUTPUT_BYTES = 256_000
TIMEOUT_SECONDS = 60
SOURCE_POLICY_VERSION = "parser-capabilities-v1"
# This is a deliberately narrow maintenance surface, not a general Java sandbox.
ALLOWED_IMPORTS = frozenset({
    "java.util.ArrayList", "java.util.HashMap", "java.util.List", "java.util.Map",
    "java.util.Collection", "java.util.Collections", "java.util.Set", "java.util.HashSet",
    "java.util.LinkedHashMap", "java.util.LinkedHashSet", "java.util.TreeMap", "java.util.TreeSet",
    "java.util.Arrays", "java.util.Objects", "java.util.Optional", "java.util.Comparator",
    "java.util.Iterator", "java.util.Locale", "java.util.regex.Matcher", "java.util.regex.Pattern",
    "org.springframework.stereotype.Component", "org.springframework.beans.factory.annotation.Autowired",
    "tools.jackson.databind.JsonNode",
    "tools.jackson.databind.node.ArrayNode", "tools.jackson.databind.node.JsonNodeFactory",
    "tools.jackson.databind.node.MissingNode", "tools.jackson.databind.node.ObjectNode",
})
FORBIDDEN_IDENTIFIERS = frozenset({
    "System", "Runtime", "Process", "ProcessBuilder", "Thread", "ThreadGroup",
    "Class", "ClassLoader", "SecurityManager", "ServiceLoader", "Module", "ModuleLayer",
    "Unsafe", "MethodHandles", "MethodHandle", "VarHandle", "Proxy", "ObjectInputStream",
    "getClass", "forName", "getClassLoader", "getResource", "getResourceAsStream",
    "getDeclaredMethod", "getDeclaredMethods", "getDeclaredField", "getDeclaredFields",
    "getDeclaredConstructor", "getDeclaredConstructors", "getMethod", "getMethods",
    "getConstructor", "getConstructors", "setAccessible", "trySetAccessible", "invoke",
    "loadClass", "defineClass", "getProtectionDomain", "getModule",
})


def candidate_policy_violations(source: str) -> list[str]:
    """Reject straightforward I/O/output-forgery/reflection additions before execution.

    The fixed harness and historical tests are not candidates. This conservative
    lexical check is defense in depth; Docker and the external oracle remain required.
    """
    violations = set()
    if re.search(r"\\u+[0-9a-fA-F]{4}", source):
        violations.add("UNICODE_ESCAPE_NOT_ALLOWED")
    # This parser has no text blocks. Reject them rather than pretending to implement
    # every Java text-block escape/preprocessing rule in a regex lexer.
    if '\"\"\"' in source:
        violations.add("TEXT_BLOCK_NOT_ALLOWED")
    lexical = re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', " ", source)
    for match in re.finditer(r"\bimport\s+(static\s+)?([^;]+);", lexical):
        imported = re.sub(r"\s+", "", match.group(2))
        if match.group(1) or imported not in ALLOWED_IMPORTS:
            violations.add("IMPORT_NOT_ALLOWED")
    identifiers = set(re.findall(r"[A-Za-z_$][A-Za-z0-9_$]*", lexical))
    if identifiers & FORBIDDEN_IDENTIFIERS:
        violations.add("FORBIDDEN_CAPABILITY")
    if re.search(r"\.\s*class\b", lexical):
        violations.add("CLASS_LITERAL_NOT_ALLOWED")
    for match in re.finditer(r"\b(?:java|javax|jdk|sun|com|org|tools)(?:\s*\.\s*[A-Za-z_$][A-Za-z0-9_$]*)+", lexical):
        qualified = re.sub(r"\s+", "", match.group())
        if not any(qualified == allowed or qualified.startswith(allowed + ".") for allowed in ALLOWED_IMPORTS):
            violations.add("QUALIFIED_CAPABILITY_NOT_ALLOWED")
    return sorted(violations)


def digest(value: bytes | str | object) -> str:
    if not isinstance(value, (bytes, str)):
        value = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    if isinstance(value, str):
        value = value.encode()
    return hashlib.sha256(value).hexdigest()


def _json(path: Path, value: object, mode: int = 0o600) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    path.chmod(mode)


@contextmanager
def runtime_lock(directory: Path):
    """Serialize preparation across launchd jobs; never lock a replaceable inode."""
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    if directory.resolve() != directory or directory.stat().st_uid != os.getuid():
        raise ValueError('OWNED_RUNTIME_DIRECTORY_REQUIRED')
    descriptor = os.open(directory / '.prepare.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(descriptor)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1):
            raise ValueError('OWNED_RUNTIME_LOCK_REQUIRED')
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def runtime_manifest_once(path: Path, value: dict):
    """Publish a fully fsynced initial manifest without truncating existing pins."""
    if path.exists():
        if json.loads(path.read_text()) != value:
            raise ValueError('Pinned runtime manifest changed')
        return
    temporary = path.with_name('.' + path.name + '.' + uuid.uuid4().hex + '.tmp')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write((json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode())
            output.flush()
            os.fsync(output.fileno())
        try:
            # link is an atomic create-if-absent publication, including if a
            # noncooperating writer appeared after the initial exists check.
            os.link(temporary, path)
        except FileExistsError:
            if json.loads(path.read_text()) != value:
                raise ValueError('Pinned runtime manifest changed') from None
        parent = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(parent)
        finally:
            os.close(parent)
    finally:
        temporary.unlink(missing_ok=True)


def _docker_env() -> dict[str, str]:
    # Docker CLI needs its local configuration, but API credentials are not inherited.
    allowed = ("PATH", "HOME", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG")
    return {key: os.environ[key] for key in allowed if key in os.environ}


def _docker(*args: str, timeout: int = 20) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", *args], text=True, capture_output=True,
                          env=_docker_env(), timeout=timeout, check=True)


def prepare_runtime() -> dict:
    """Pin an already-pulled trusted JDK image and copy exact cached dependency jars."""
    with runtime_lock(RUNTIME):
        return _prepare_runtime_locked()


def _prepare_runtime_locked() -> dict:
    manifest_path = RUNTIME / "manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        expected_names = {f"{artifact}-{version}.jar" for _, artifact, version in DEPENDENCIES}
        if set(manifest["jars"]) != expected_names:
            raise ValueError("Pinned runtime dependency set differs from validator; prepare a new runtime")
        _docker("image", "inspect", manifest["image_id"])
        for name, expected in manifest["jars"].items():
            if digest((RUNTIME / "lib" / name).read_bytes()) != expected:
                raise ValueError("Trusted dependency digest changed: " + name)
        return manifest
    image = json.loads(_docker("image", "inspect", IMAGE_TAG).stdout)[0]
    lib = RUNTIME / "lib"
    lib.mkdir(exist_ok=True, mode=0o755)
    jars = {}
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    for group, artifact, version in DEPENDENCIES:
        candidates = list((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
        if len(candidates) != 1:
            raise ValueError(f"Expected one cached dependency {artifact}:{version}, got {len(candidates)}")
        target = lib / candidates[0].name
        shutil.copyfile(candidates[0], target)
        target.chmod(0o644)
        jars[target.name] = digest(target.read_bytes())
    manifest = {"image_requested": IMAGE_TAG, "image_id": image["Id"],
                "image_repo_digests": image.get("RepoDigests", []), "jars": jars}
    runtime_manifest_once(manifest_path, manifest)
    return manifest


def _verify_fixed_sources() -> None:
    manifest = json.loads((TRUSTED / "source-manifest.json").read_text())
    for filename in ("ParsedPlace.java", "ParsedMenu.java", "baseline/NaverPlaceParserTest.java"):
        if digest((TRUSTED / filename).read_bytes()) != manifest["sources"][filename]["sha256"]:
            raise ValueError("Protected historical source changed: " + filename)


def _fixture_check(fixtures: list[dict]) -> None:
    if not fixtures or len(fixtures) > 100:
        raise ValueError("Between 1 and 100 independent fixtures are required")
    seen = set()
    for fixture in fixtures:
        if not isinstance(fixture.get("id"), str) or fixture["id"] in seen:
            raise ValueError("Fixture IDs must be unique strings")
        seen.add(fixture["id"])
        if not isinstance(fixture.get("raw"), dict):
            raise ValueError("Fixture raw must be an Apollo entity object")
        expected = fixture.get("expected")
        if not isinstance(expected, dict) or not expected:
            raise ValueError("An independent expected field map is required")
        allowed = {"name", "phone", "address", "roadAddress", "longitude", "latitude",
                   "businessStatusDescription", "businessHoursRaw", "instagramHandle", "menus", "baeminMenus"}
        if not set(expected) <= allowed:
            raise ValueError("Unknown expected ParsedPlace field")
    if len(json.dumps(fixtures, ensure_ascii=False).encode()) > MAX_INPUT_BYTES:
        raise ValueError("Fixture input exceeds bounded size")


def compare_outputs(fixtures: list[dict], outputs: list[dict]) -> list[dict]:
    if not isinstance(outputs, list) or len(outputs) != len(fixtures):
        raise ValueError("Harness returned an unexpected number of cases")
    results = []
    for fixture, output in zip(fixtures, outputs):
        if not isinstance(output, dict) or output.get("id") != fixture["id"]:
            raise ValueError("Harness returned unexpected fixture IDs/order")
        actual = output.get("actual", {})
        diffs = {field: {"expected": expected, "actual": actual.get(field)}
                 for field, expected in fixture["expected"].items()
                 if actual.get(field) != expected}
        results.append({"id": fixture["id"], "passed": not diffs and "error" not in output,
                        "actual": actual, "mismatches": diffs, "error": output.get("error")})
    return results


def _bounded_run(command: list[str], name: str) -> dict:
    started = time.monotonic()
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               env=_docker_env())
    chunks = bytearray()
    stopped = None
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        while selector.get_map():
            if time.monotonic() - started > TIMEOUT_SECONDS:
                stopped = "TIMEOUT"
                break
            for key, _ in selector.select(timeout=0.1):
                chunk = os.read(key.fileobj.fileno(), 8192)
                if not chunk:
                    selector.unregister(key.fileobj)
                    break
                chunks.extend(chunk)
                if len(chunks) > MAX_OUTPUT_BYTES:
                    stopped = "OUTPUT_LIMIT"
                    break
            if stopped:
                break
    finally:
        selector.close()
        if stopped:
            try:
                _docker("rm", "-f", name)
            except (subprocess.SubprocessError, OSError):
                pass
            process.kill()
        try:
            returncode = process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try:
                _docker("rm", "-f", name)
            except (subprocess.SubprocessError, OSError):
                pass
            process.kill()
            returncode = process.wait(timeout=5)
            stopped = stopped or "TIMEOUT"
        process.stdout.close()
    return {"exit_code": returncode, "stopped": stopped,
            "output": bytes(chunks[:MAX_OUTPUT_BYTES]).decode(errors="replace"),
            "elapsed_seconds": round(time.monotonic() - started, 3)}


def validate(candidate_source: str, fixtures: list[dict], output_dir: Path,
             *, proposed_fixtures: list[dict] | None = None) -> dict:
    """Validate candidate with immutable external oracle; no host Java execution."""
    if not isinstance(candidate_source, str) or len(candidate_source.encode()) > MAX_SOURCE_BYTES:
        raise ValueError("Candidate Java source is missing or too large")
    _fixture_check(fixtures)
    proposed_fixtures = proposed_fixtures or []
    if proposed_fixtures:
        _fixture_check(proposed_fixtures)
    output_dir = Path(output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=False, mode=0o700)
    report = {"validator_version": "isolated-java-v2", "passed": False,
              "status": "INFRA_ERROR", "source_digest": digest(candidate_source),
              "fixtures_digest": digest(fixtures), "proposed_fixtures_digest": digest(proposed_fixtures),
              "cases": [], "proposed_cases": []}
    policy_violations = candidate_policy_violations(candidate_source)
    report["source_policy"] = {"version": SOURCE_POLICY_VERSION, "violations": policy_violations}
    if policy_violations:
        report["status"] = "SOURCE_POLICY_REJECTED"
        report["report_digest"] = digest(report)
        _json(output_dir / "validation.json", report)
        return report
    try:
        _verify_fixed_sources()
        runtime = prepare_runtime()
        sources = output_dir / "sources"
        inputs = output_dir / "input"
        sources.mkdir(mode=0o755)
        inputs.mkdir(mode=0o755)
        (sources / "NaverPlaceParser.java").write_text(candidate_source)
        (sources / "NaverPlaceParser.java").chmod(0o644)
        trusted_hashes = {}
        for filename in ("ParsedPlace.java", "ParsedMenu.java", "ParserHarness.java"):
            content = (TRUSTED / filename).read_bytes()
            trusted_hashes[filename] = digest(content)
            (sources / filename).write_bytes(content)
            (sources / filename).chmod(0o644)
        fixed_test = (TRUSTED / "baseline/NaverPlaceParserTest.java").read_bytes()
        trusted_hashes["NaverPlaceParserTest.java"] = digest(fixed_test)
        (sources / "NaverPlaceParserTest.java").write_bytes(fixed_test)
        (sources / "NaverPlaceParserTest.java").chmod(0o644)
        # Prefix all IDs to prevent generated tests colliding with fixed cases.
        all_fixtures = [{**row, "id": f"fixed:{index}"} for index, row in enumerate(fixtures)]
        all_fixtures += [{**row, "id": f"proposed:{index}"} for index, row in enumerate(proposed_fixtures)]
        _json(inputs / "fixtures.json", [{"id": f["id"], "raw": f["raw"]} for f in all_fixtures], 0o644)
        name = "response-validator-" + uuid.uuid4().hex[:16]
        command = ["docker", "run", "--rm", "--name", name, "--network", "none", "--read-only",
                   "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--pids-limit", "128",
                   "--memory", "512m", "--memory-swap", "512m", "--cpus", "1", "--user", "10001:10001",
                   "--ulimit", "nofile=64:64", "--tmpfs", "/tmp:rw,noexec,nosuid,size=268435456,mode=1777",
                   "--mount", f"type=bind,src={sources},dst=/sources,readonly",
                   "--mount", f"type=bind,src={inputs},dst=/input,readonly",
                   "--mount", f"type=bind,src={RUNTIME / 'lib'},dst=/lib-jars,readonly",
                   "--workdir", "/tmp", "--env", "HOME=/tmp", runtime["image_id"], "sh", "-c",
                   "mkdir /tmp/classes && javac -J-Xmx256m -encoding UTF-8 -cp '/lib-jars/*' -d /tmp/classes /sources/*.java && "
                   "java -Xmx256m -XX:ActiveProcessorCount=1 -cp '/tmp/classes:/lib-jars/*' ParserHarness"]
        report.update({"runtime": runtime, "trusted_sources": trusted_hashes, "command": command,
                       "sandbox": {"network": "none", "readonly_root": True, "uid": 10001,
                                   "oracle_mounted": False, "host_secrets_mounted": False}})
        execution = _bounded_run(command, name)
        report["execution"] = {key: value for key, value in execution.items() if key != "output"}
        if execution["stopped"]:
            report.update(status="RESOURCE_LIMIT", error=execution["stopped"])
        elif execution["exit_code"] in (125, 126, 127):
            report.update(status="INFRA_ERROR", error=execution["output"][-2000:])
        elif execution["exit_code"] == 137:
            report.update(status="RESOURCE_LIMIT", error="Container process was killed (possible memory limit)")
        elif execution["exit_code"] != 0:
            report.update(status="COMPILE_OR_RUNTIME_ERROR", error=execution["output"][-12000:])
        else:
            try:
                outputs = json.loads(execution["output"])
                regression = outputs["regression"]
                report["fixed_regression"] = regression
                regression_passed = (regression.get("found") == 11 and regression.get("succeeded") == 11
                                     and regression.get("failed") == 0 and regression.get("skipped") == 0)
                cases = compare_outputs(all_fixtures, outputs["cases"])
                report["cases"] = [{**case, "id": fixtures[i]["id"]} for i, case in enumerate(cases[:len(fixtures)])]
                report["proposed_cases"] = [{**case, "id": proposed_fixtures[i]["id"]} for i, case in enumerate(cases[len(fixtures):])]
                report["passed"] = regression_passed and all(case["passed"] for case in cases)
                report["status"] = "PASS" if report["passed"] else "ASSERTION_FAILED"
            except (ValueError, TypeError, AttributeError, KeyError) as error:
                report.update(status="INVALID_HARNESS_OUTPUT", error=str(error))
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        report["error"] = str(error)[:2000]
    report["report_digest"] = digest(report)
    _json(output_dir / "validation.json", report)
    return report
