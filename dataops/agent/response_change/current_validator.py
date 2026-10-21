"""Current-source parser/profile validation with frozen tests and external oracles."""
from __future__ import annotations

import base64
import json
from pathlib import Path
import shutil
import subprocess
import uuid
import zipfile

from . import validator as isolated
from .current_manifest import (ALL_PATHS, EDITABLE_PATHS, HARNESS, PARSER_PATH, PROFILE_PATH,
                               SOURCE_PATHS, TEST_PATHS, verify_deployment)

RUNTIME = isolated.runtime_path('current-validator-runtime-jackson312')
CURRENT_DEPENDENCIES = tuple((group, artifact, "3.1.2" if group == "tools.jackson.core" else version)
                             for group, artifact, version in isolated.DEPENDENCIES)
EXTRA_DEPENDENCIES = (("org.projectlombok", "lombok", "1.18.46"),
                      ("jakarta.validation", "jakarta.validation-api", "3.1.1"),
                      ("org.springframework", "spring-beans", "7.0.7"))
VERSION = "current-isolated-java-v1"
ORACLE_KINDS = frozenset({"REVIEWED_CONTRACT", "APPROVED_BASELINE", "HUMAN_CONFIRMED"})
EXPECTED_FIELDS = frozenset({"name", "phone", "address", "roadAddress", "longitude", "latitude",
                            "businessStatusDescription", "businessHoursRaw", "instagramHandle", "menus", "baeminMenus",
                            "diagnostics", "evidence", "signals"})


def prepare_current_runtime() -> dict:
    base = isolated.prepare_runtime()
    with isolated.runtime_lock(RUNTIME):
        return _prepare_current_runtime_locked(base)


def _prepare_current_runtime_locked(base: dict) -> dict:
    lib = RUNTIME / "lib"
    path = RUNTIME / "manifest.json"
    if path.exists():
        manifest = json.loads(path.read_text())
        expected_names = {f"{artifact}-{version}.jar" for _, artifact, version in CURRENT_DEPENDENCIES + EXTRA_DEPENDENCIES}
        if (set(manifest.get('jars', {})) != expected_names
                or manifest != {**base, 'jars': manifest.get('jars')}
                or {file.name for file in lib.iterdir()} != expected_names):
            raise ValueError("Pinned current runtime changed; issue a new reviewed runtime")
        for name, expected in manifest['jars'].items():
            if isolated.digest((lib / name).read_bytes()) != expected:
                raise ValueError("Trusted dependency changed: " + name)
        return manifest
    lib.mkdir(exist_ok=True, mode=0o755)
    sources = {}
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    for group, artifact, version in CURRENT_DEPENDENCIES + EXTRA_DEPENDENCIES:
        candidates = list((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
        if len(candidates) != 1:
            raise ValueError("Expected one trusted cached dependency: " + artifact)
        sources[candidates[0].name] = candidates[0]
    jars = {}
    for name, source in sources.items():
        expected = isolated.digest(source.read_bytes())
        target = lib / name
        if target.exists() and isolated.digest(target.read_bytes()) != expected:
            raise ValueError("Trusted dependency changed: " + name)
        if not target.exists():
            shutil.copyfile(source, target)
            target.chmod(0o644)
        jars[name] = expected
    if {path.name for path in lib.iterdir()} != set(jars):
        raise ValueError("Unexpected dependency in offline compiler classpath")
    manifest = {**base, "jars": jars}
    isolated.runtime_manifest_once(path, manifest)
    return manifest


def regression_passes(result: dict, expected_count: int | None = None) -> bool:
    found = result.get("found", 0)
    return (type(found) is int and found > 0 and (expected_count is None or found == expected_count)
            and result.get("succeeded") == found and result.get("failed") == 0
            and result.get("skipped") == 0 and result.get("aborted") == 0)


def check_fixtures(fixtures: list[dict], *, independent: bool = True) -> None:
    if not isinstance(fixtures, list) or not 1 <= len(fixtures) <= 100:
        raise ValueError("Between 1 and 100 cases are required")
    seen = set()
    for row in fixtures:
        if not isinstance(row.get("id"), str) or not row["id"] or row["id"] in seen:
            raise ValueError("Unique nonempty fixture IDs required")
        seen.add(row["id"])
        if not isinstance(row.get("raw"), dict) or not isinstance(row.get("external_id"), str) or not row["external_id"]:
            raise ValueError("Raw object and scoped external_id required")
        expected = row.get("expected")
        if not isinstance(expected, dict) or not expected or not set(expected) <= EXPECTED_FIELDS:
            raise ValueError("Nonempty supported external expected fields required")
        if independent:
            oracle = row.get("oracle", {})
            if oracle.get("kind") not in ORACLE_KINDS or not isinstance(oracle.get("reference"), str) or not oracle["reference"].strip():
                raise ValueError("Independent expected values require reviewed provenance")
            if row.get("role") not in {"NORMAL_REGRESSION", "NEW_STRUCTURE", "CONTRACT_COUNTEREXAMPLE"}:
                raise ValueError("Trusted case role is required")
    if len(json.dumps(fixtures, ensure_ascii=False).encode()) > isolated.MAX_INPUT_BYTES:
        raise ValueError("Fixture input exceeds bounded size")


def compare_cases(fixtures: list[dict], outputs: list[dict]) -> list[dict]:
    """Nested object oracles compare their specified keys; arrays remain exact."""
    if not isinstance(outputs, list) or len(outputs) != len(fixtures):
        raise ValueError("Unexpected harness case count")
    results = []

    def mismatch(expected, actual, prefix=""):
        if isinstance(expected, dict) and isinstance(actual, dict):
            found = {}
            for key, value in expected.items():
                found.update(mismatch(value, actual.get(key), f"{prefix}.{key}" if prefix else key))
            return found
        return {} if type(expected) is type(actual) and expected == actual else {prefix: {"expected": expected, "actual": actual}}

    for fixture, output in zip(fixtures, outputs):
        if output.get("id") != fixture["id"]:
            raise ValueError("Unexpected harness fixture identity/order")
        differences = mismatch(fixture["expected"], output.get("actual", {}))
        results.append({"id": fixture["id"], "passed": not differences and "error" not in output,
                        "actual": output.get("actual", {}), "mismatches": differences, "error": output.get("error")})
    return results


def compile_and_run(snapshot: Path, candidates: dict[str, str], fixtures: list[dict], output_dir: Path, runtime: dict) -> dict:
    """Fixed command, no host compilation. Container receives raw cases, never oracles."""
    output_dir.mkdir(parents=True, exist_ok=False, mode=0o700)
    sources, inputs, resources = (output_dir / name for name in ("sources", "input", "resources"))
    for directory in (sources, inputs, resources):
        directory.mkdir(mode=0o755)
    for path in SOURCE_PATHS + TEST_PATHS:
        target = sources / Path(path).name
        target.write_bytes(candidates[path].encode() if path in candidates else (snapshot / path).read_bytes())
        target.chmod(0o644)
    shutil.copyfile(HARNESS, sources / HARNESS.name)
    (sources / HARNESS.name).chmod(0o644)
    for path in ALL_PATHS:
        if path.startswith(("src/main/resources/", "src/test/resources/")):
            relative = path.split("/resources/", 1)[1]
            target = resources / relative
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
            target.write_bytes(candidates[path].encode() if path in candidates else (snapshot / path).read_bytes())
            target.chmod(0o644)
    isolated._json(inputs / "fixtures.json", [{key: row[key] for key in ("id", "raw", "external_id")} for row in fixtures], 0o644)
    isolated._json(inputs / "test-classes.json", [path.removeprefix("src/test/java/").removesuffix(".java").replace("/", ".") for path in TEST_PATHS], 0o644)
    name = "current-parser-" + uuid.uuid4().hex[:16]
    command = ["docker", "run", "--rm", "--name", name, "--network", "none", "--read-only",
               "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--pids-limit", "128",
               "--memory", "512m", "--memory-swap", "512m", "--cpus", "1", "--user", "10001:10001",
               "--ulimit", "nofile=64:64", "--tmpfs", "/tmp:rw,noexec,nosuid,size=268435456,mode=1777",
               "--mount", f"type=bind,src={sources.resolve()},dst=/sources,readonly",
               "--mount", f"type=bind,src={inputs.resolve()},dst=/input,readonly",
               "--mount", f"type=bind,src={resources.resolve()},dst=/resources,readonly",
               "--mount", f"type=bind,src={RUNTIME / 'lib'},dst=/lib-jars,readonly",
               "--workdir", "/tmp", "--env", "HOME=/tmp", runtime["image_id"], "sh", "-c",
               "mkdir /tmp/classes && javac -J-Xmx256m -g -parameters -encoding UTF-8 --release 17 "
               "-cp '/lib-jars/*' -processorpath /lib-jars/lombok-1.18.46.jar -d /tmp/classes /sources/*.java && "
               "java -Xmx256m -XX:ActiveProcessorCount=1 -cp '/tmp/classes:/resources:/lib-jars/*' CurrentParserHarness"]
    execution = isolated._bounded_run(command, name)
    report = {"status": "INFRA_ERROR", "execution": {key: value for key, value in execution.items() if key != "output"},
              "sandbox": {"network": "none", "uid": 10001, "readonly_root": True,
                          "oracle_mounted": False, "host_secrets_mounted": False}}
    if execution["stopped"]:
        report.update(status="RESOURCE_LIMIT", error=execution["stopped"])
    elif execution["exit_code"]:
        report.update(status="COMPILE_OR_RUNTIME_ERROR", error=execution["output"][-12000:])
    else:
        try:
            raw = json.loads(execution["output"])
            classes = {path: base64.b64decode(row["bytes"], validate=True) for path, row in raw["classes"].items()}
            allowed_roots = {path.removeprefix("src/main/java/").removesuffix(".java") for path in SOURCE_PATHS}
            if {path.split("$", 1)[0].removesuffix(".class") for path in classes} != allowed_roots:
                raise ValueError("Unexpected compiled class outside protected parser family")
            if any(isolated.digest(data) != raw["classes"][path]["sha256"] for path, data in classes.items()):
                raise ValueError("Invalid compiled class digest")
            report.update(status="OK", cases=raw["cases"], regression=raw["regression"], class_bytes=classes,
                          classes={path: isolated.digest(data) for path, data in classes.items()},
                          parser_digest=raw["parser_digest"], profile_digest=raw["profile_digest"])
        except (ValueError, TypeError, KeyError) as error:
            report.update(status="INVALID_HARNESS_OUTPUT", error=str(error))
    return report


def _candidate_jar(base_jar: Path, output: Path, classes: dict[str, bytes], profile: bytes) -> None:
    from .current_manifest import _jar_entries
    _jar_entries(base_jar)  # Duplicate/signed inputs fail closed before mutation.
    roots = {path.removeprefix("src/main/java/").removesuffix(".java") for path in SOURCE_PATHS}
    resource = "BOOT-INF/classes/" + PROFILE_PATH.removeprefix("src/main/resources/")
    with zipfile.ZipFile(base_jar) as original, zipfile.ZipFile(output, "w") as candidate:
        for entry in original.infolist():
            relative = entry.filename.removeprefix("BOOT-INF/classes/")
            if entry.filename == resource or (entry.filename.startswith("BOOT-INF/classes/") and relative.endswith(".class")
                    and relative.split("$", 1)[0].removesuffix(".class") in roots):
                continue
            candidate.writestr(entry, original.read(entry))
        # All recompiled nested classes replace the old family; no stale inner classes survive.
        for relative, data in sorted(classes.items()):
            candidate.writestr(zipfile.ZipInfo("BOOT-INF/classes/" + relative), data, compress_type=zipfile.ZIP_DEFLATED)
        candidate.writestr(zipfile.ZipInfo(resource), profile, compress_type=zipfile.ZIP_DEFLATED)
    output.chmod(0o600)


def validate(manifest_path: Path, candidate_files: dict[str, str], fixtures: list[dict], output_dir: Path,
             *, expected_manifest_digest: str, proposed_fixtures: list[dict] | None = None,
             require_committed: bool = True) -> dict:
    """Prove current baseline failure, retained regression, and a bounded repaired JAR."""
    proposed_fixtures = proposed_fixtures or []
    check_fixtures(fixtures)
    if proposed_fixtures:
        check_fixtures(proposed_fixtures, independent=False)
    if not {"NORMAL_REGRESSION", "NEW_STRUCTURE"} <= {row["role"] for row in fixtures}:
        raise ValueError("Current baseline needs normal and failing new-structure oracles")
    if not isinstance(candidate_files, dict) or set(candidate_files) != set(EDITABLE_PATHS):
        raise ValueError("Exactly the parser/profile candidate map is required")
    if any(not isinstance(value, str) or len(value.encode()) > isolated.MAX_SOURCE_BYTES for value in candidate_files.values()):
        raise ValueError("Candidate source/profile exceeds bounded size")
    output = Path(output_dir).resolve()
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    report = {"validator_version": VERSION, "status": "INFRA_ERROR", "passed": False,
              "manifest_digest": expected_manifest_digest, "source_digest": isolated.digest(candidate_files),
              "candidate_file_digests": {path: isolated.digest(content) for path, content in candidate_files.items()},
              "fixtures_digest": isolated.digest(fixtures), "proposed_fixtures_digest": isolated.digest(proposed_fixtures),
              "cases": [], "proposed_cases": []}
    try:
        manifest = verify_deployment(manifest_path, expected_manifest_digest=expected_manifest_digest, require_committed=require_committed)
        report["contract_digest"] = manifest["contract_digest"]
        report["trusted_tests_digest"] = manifest["trusted_tests_digest"]
        report["build_input_digest"] = isolated.digest({"sources": {**manifest["sources"], **report["candidate_file_digests"]},
                                                       "runtime": manifest["runtime"], "build_options": manifest["build_options"]})
        policy = isolated.candidate_policy_violations(candidate_files[PARSER_PATH])
        if policy:
            report.update(status="SOURCE_POLICY_REJECTED", source_policy={"violations": policy})
        else:
            snapshot = Path(manifest_path).resolve().parent / "snapshot"
            baseline = compile_and_run(snapshot, {}, fixtures, output / "baseline", manifest["runtime"])
            baseline.pop("class_bytes", None)
            report["baseline"] = baseline
            if baseline["status"] != "OK":
                report["status"] = "BASELINE_BUILD_FAILED"
            else:
                baseline_cases = compare_cases(fixtures, baseline["cases"])
                report["baseline"]["cases"] = baseline_cases
                normal_ok = all(result["passed"] for row, result in zip(fixtures, baseline_cases) if row["role"] != "NEW_STRUCTURE")
                new_fails = all(not result["passed"] for row, result in zip(fixtures, baseline_cases) if row["role"] == "NEW_STRUCTURE")
                baseline_classes_match = baseline["classes"] == manifest["classes"]
                if not (normal_ok and new_fails and baseline_classes_match and regression_passes(baseline["regression"], manifest["baseline_regression"]["found"])):
                    report["status"] = "BASELINE_EVIDENCE_REJECTED"
                else:
                    fixed = [{**row, "id": f"fixed:{index}"} for index, row in enumerate(fixtures)]
                    proposed = [{**row, "id": f"proposed:{index}"} for index, row in enumerate(proposed_fixtures)]
                    candidate = compile_and_run(snapshot, candidate_files, fixed + proposed, output / "candidate", manifest["runtime"])
                    classes = candidate.pop("class_bytes", {})
                    report["candidate"] = candidate
                    report["status"] = candidate["status"]
                    if candidate["status"] == "OK":
                        parser_root = PARSER_PATH.removeprefix("src/main/java/").removesuffix(".java")
                        protected = lambda path: path.split("$", 1)[0].removesuffix(".class") != parser_root
                        if {path: value for path, value in candidate["classes"].items() if protected(path)} != {
                                path: value for path, value in manifest["classes"].items() if protected(path)}:
                            raise ValueError("Protected class output changed under parser candidate")
                        results = compare_cases(fixed + proposed, candidate["cases"])
                        report["cases"] = [{**row, "id": fixtures[i]["id"]} for i, row in enumerate(results[:len(fixtures)])]
                        report["proposed_cases"] = [{**row, "id": proposed_fixtures[i]["id"]} for i, row in enumerate(results[len(fixtures):])]
                        report["fixed_regression"] = candidate["regression"]
                        report["passed"] = regression_passes(candidate["regression"], manifest["baseline_regression"]["found"]) and all(row["passed"] for row in results)
                        report["status"] = "PASS" if report["passed"] else "ASSERTION_FAILED"
                        if report["passed"]:
                            jar = output / "candidate.jar"
                            _candidate_jar(Path(manifest_path).resolve().parent / "service.jar", jar, classes, candidate_files[PROFILE_PATH].encode())
                            report["artifact"] = {"path": str(jar), "sha256": isolated.digest(jar.read_bytes()),
                                                  "classes": candidate["classes"], "profile_digest": candidate["profile_digest"],
                                                  "parser_digest": candidate["parser_digest"], "base_jar_sha256": manifest["jar_sha256"],
                                                  "production_eligible": False,
                                                  "requires": ["trusted-live-base-jar-and-parser-binding", "digest-bound-human-approval", "controller-canary"]}
    except (OSError, ValueError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        report.update(status="PROVENANCE_OR_BUILD_ERROR", passed=False, error=str(error)[:2000])
    report["report_digest"] = isolated.digest(report)
    isolated._json(output / "validation.json", report)
    return report
