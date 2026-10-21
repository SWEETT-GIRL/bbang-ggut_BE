"""Freeze verifiable source-to-deployed-binary provenance for the current parser.

This is an operator-side build tool, never an LLM tool. A source digest beside an
unrelated JAR digest is insufficient: fixed offline javac output must equal every
parser-family class in the supplied service JAR before a manifest is issued.
"""
from __future__ import annotations

import json
from pathlib import Path
import shutil
import subprocess
import zipfile

from .validator import digest, _json

VERSION = "current-parser-deployment-v1"
PARSER_PATH = "src/main/java/sweetgirl/dataops/snapshot/parser/NaverPlaceParser.java"
PROFILE_PATH = "src/main/resources/response-agent/menu-structure-profile-v1.json"
EDITABLE_PATHS = (PARSER_PATH, PROFILE_PATH)
SOURCE_PATHS = tuple("src/main/java/sweetgirl/dataops/" + name for name in (
    "snapshot/parser/NaverPlaceParser.java", "snapshot/parser/MenuStructureProfile.java",
    "snapshot/parser/ParsedPlace.java", "snapshot/parser/ParsedMenu.java",
    "snapshot/parser/BusinessHoursParser.java", "snapshot/parser/BusinessHourRow.java",
    "snapshot/parser/MenuEvidenceParser.java", "snapshot/parser/StoreSignalParser.java",
    "snapshot/parser/ParsedStoreSignal.java", "snapshot/service/ResponseDiagnostics.java",
    "snapshot/service/ResponseDigests.java", "snapshot/dto/request/SnapshotSubmitRequest.java",
    "sync/enums/SyncSource.java",
))
TEST_PATHS = tuple("src/test/java/sweetgirl/dataops/" + name for name in (
    "snapshot/parser/NaverPlaceParserTest.java", "snapshot/parser/BusinessHoursParserTest.java",
    "snapshot/parser/MenuEvidenceParserTest.java", "snapshot/service/ResponseDiagnosticsTest.java",
))
RESOURCE_PATHS = (
    PROFILE_PATH,
    "src/test/resources/fixtures/apollo_state_place_menus_1439400670.json",
    "src/test/resources/fixtures/apollo_state_lemoment_465987.json",
)
BUILD_PATHS = ("build.gradle", "settings.gradle", "gradle/wrapper/gradle-wrapper.properties")
ALL_PATHS = SOURCE_PATHS + TEST_PATHS + RESOURCE_PATHS + BUILD_PATHS
TRUSTED = Path(__file__).parent / "trusted/current"
HARNESS = TRUSTED / "CurrentParserHarness.java"
VALIDATOR_FILES = ("current_manifest.py", "current_validator.py", "validator.py")


def validator_code_digests() -> dict[str, str]:
    return {name: digest((Path(__file__).parent / name).read_bytes()) for name in VALIDATOR_FILES}


def _git(root: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(root), *args], text=True, capture_output=True,
                          check=True, timeout=10).stdout.strip()


def manifest_digest(manifest: dict) -> str:
    return digest({key: value for key, value in manifest.items() if key != "manifest_digest"})


def _jar_entries(artifact: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(artifact) as jar:
        names = jar.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate JAR entries are not allowed")
        if any(name.upper().startswith("META-INF/") and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC")) for name in names):
            raise ValueError("Signed JAR replacement is unsupported")
        return {name: jar.read(name) for name in names if not name.endswith("/")}


def check_class_correspondence(classes: dict[str, bytes], entries: dict[str, bytes]) -> None:
    """Require exact equality including nested classes and reject omitted old classes."""
    expected_roots = {path.removeprefix("src/main/java/").removesuffix(".java") for path in SOURCE_PATHS}
    if {path.split("$", 1)[0].removesuffix(".class") for path in classes} != expected_roots:
        raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: compiled parser source set differs")
    existing = {name.removeprefix("BOOT-INF/classes/"): value for name, value in entries.items()
                if name.startswith("BOOT-INF/classes/") and name.endswith(".class")
                and name.removeprefix("BOOT-INF/classes/").split("$", 1)[0].removesuffix(".class") in expected_roots}
    if classes != existing:
        changed = sorted(set(classes) ^ set(existing) | {key for key in set(classes) & set(existing) if classes[key] != existing[key]})
        raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: rebuilt class bytes differ: " + ", ".join(changed))


def freeze_deployment(source_root: Path, artifact_path: Path, output_dir: Path,
                      *, allow_working_tree: bool = False) -> dict:
    """Freeze current trusted inputs after isolated compile and JAR equality checks.

    Working-tree manifests are explicitly development-only; production callers must
    require ``provenance.mode == COMMITTED_SOURCE``. Never infers a deployed service
    from this file: the controller must additionally compare live readiness digests.
    """
    from .current_validator import compile_and_run, prepare_current_runtime, regression_passes
    root, artifact, output = Path(source_root).resolve(), Path(artifact_path).resolve(), Path(output_dir).resolve()
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    commit, tree = _git(root, "rev-parse", "HEAD"), _git(root, "rev-parse", "HEAD^{tree}")
    sources, changed = {}, []
    for path in ALL_PATHS:
        data = (root / path).read_bytes()
        result = subprocess.run(["git", "-C", str(root), "show", f"{commit}:{path}"], capture_output=True, timeout=10)
        if result.returncode != 0 or result.stdout != data:
            changed.append(path)
        destination = output / "snapshot" / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        sources[path] = digest(data)
    if changed and not allow_working_tree:
        raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: source/build inputs are not the committed tree")
    shutil.copyfile(artifact, output / "service.jar")
    runtime = prepare_current_runtime()
    execution = compile_and_run(output / "snapshot", {}, [], output / "baseline-build", runtime)
    if execution["status"] != "OK" or not regression_passes(execution.get("regression", {})):
        _json(output / "failed-build.json", execution)
        raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: trusted baseline build/regression did not pass")
    entries = _jar_entries(output / "service.jar")
    check_class_correspondence(execution.pop("class_bytes"), entries)
    if entries.get("BOOT-INF/classes/" + PROFILE_PATH.removeprefix("src/main/resources/")) != (root / PROFILE_PATH).read_bytes():
        raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: profile resource differs from JAR")
    deployed_dependencies = {}
    for name, expected in runtime["jars"].items():
        # Testing and annotation processing are trusted build inputs, not deployed libraries.
        if name.startswith(("junit-", "assertj-", "opentest4j-", "apiguardian-", "lombok-")):
            continue
        entry = "BOOT-INF/lib/" + name
        if entry not in entries or digest(entries[entry]) != expected:
            raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: dependency differs: " + name)
        deployed_dependencies[name] = expected
    manifest = {"version": VERSION,
                "provenance": {"mode": "ISOLATED_WORKING_TREE" if changed else "COMMITTED_SOURCE",
                               "git_commit": commit, "git_tree": tree, "uncommitted_inputs": changed,
                               "method": "fixed-offline-javac-equals-boot-jar-classes",
                               "scope": "PARSER_FAMILY_AND_VALIDATION_INPUTS",
                               "entire_jar_source_verified": False, "live_runtime_verified": False},
                "sources": sources, "editable_paths": list(EDITABLE_PATHS),
                "jar_sha256": digest((output / "service.jar").read_bytes()),
                "classes": execution["classes"], "parser_digest": execution["parser_digest"],
                "profile_digest": execution["profile_digest"], "profile_resource_sha256": sources[PROFILE_PATH],
                "trusted_harness_sha256": digest(HARNESS.read_bytes()),
                "validator_code_sha256": validator_code_digests(),
                "runtime": runtime, "deployed_dependencies": deployed_dependencies,
                "baseline_regression": execution["regression"],
                "build_options": ["-g", "-parameters", "-encoding", "UTF-8", "--release", "17"]}
    manifest["contract_digest"] = digest({path: sources[path] for path in SOURCE_PATHS
        if path.endswith(("ResponseDiagnostics.java", "MenuEvidenceParser.java", "BusinessHoursParser.java", "MenuStructureProfile.java"))})
    manifest["trusted_tests_digest"] = digest({path: sources[path] for path in TEST_PATHS + RESOURCE_PATHS if path != PROFILE_PATH})
    manifest["build_input_digest"] = digest({"sources": sources, "runtime": runtime, "build_options": manifest["build_options"]})
    manifest["manifest_digest"] = manifest_digest(manifest)
    _json(output / "manifest.json", manifest)
    _json(output / "baseline-build.json", execution)
    return manifest


def verify_deployment(manifest_path: Path, *, expected_manifest_digest: str,
                      require_committed: bool = True) -> dict:
    """Recheck frozen inputs, protected harness, runtime, artifact, and class binding.

    ``expected_manifest_digest`` must come from the trusted release/run registry,
    not from the manifest being verified or model output.
    """
    from .current_validator import prepare_current_runtime
    path = Path(manifest_path).resolve()
    manifest = json.loads(path.read_text())
    actual = manifest_digest(manifest)
    if manifest.get("version") != VERSION or actual != expected_manifest_digest or manifest.get("manifest_digest") != actual:
        raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: manifest digest/version differs")
    if require_committed and manifest["provenance"]["mode"] != "COMMITTED_SOURCE":
        raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: development-only manifest")
    if set(manifest["sources"]) != set(ALL_PATHS) or manifest["editable_paths"] != list(EDITABLE_PATHS):
        raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: protected source allowlist differs")
    for relative, expected in manifest["sources"].items():
        if digest((path.parent / "snapshot" / relative).read_bytes()) != expected:
            raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: frozen source changed: " + relative)
    if digest(HARNESS.read_bytes()) != manifest["trusted_harness_sha256"]:
        raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: trusted harness changed")
    if validator_code_digests() != manifest["validator_code_sha256"]:
        raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: trusted validator code changed")
    if digest((path.parent / "service.jar").read_bytes()) != manifest["jar_sha256"]:
        raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: service JAR changed")
    if prepare_current_runtime() != manifest["runtime"]:
        raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: trusted compiler/dependencies changed")
    entries = _jar_entries(path.parent / "service.jar")
    for class_path, expected in manifest["classes"].items():
        if digest(entries.get("BOOT-INF/classes/" + class_path, b"")) != expected:
            raise ValueError("DEPLOYMENT_SOURCE_UNVERIFIED: compiled class changed")
    return manifest
