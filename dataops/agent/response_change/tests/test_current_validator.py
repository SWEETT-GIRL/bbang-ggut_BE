import base64
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from agent.response_change import current_manifest as provenance
from agent.response_change import current_validator as current
from agent.response_change import validator as historical


def fixture(case_id="normal", typename="PlaceMenuItem", role="NORMAL_REGRESSION"):
    menu = {"id": "a", "__typename": typename, "name": "빵", "price": {"displayText": "5,300원"}, "images": []}
    return {"id": case_id, "role": role, "external_id": "123",
            "oracle": {"kind": "REVIEWED_CONTRACT", "reference": "display-text-won reviewed contract v1"},
            "raw": {"PlaceDetailBase:123": {"id": "123", "name": "빵집"}, typename + ":a": menu,
                    "ROOT_QUERY": {'placeDetail({"input":{"id":"123"}})': {
                        "placeMenus": {"items": [{"__ref": typename + ":a"}], "menuCount": 1}}}},
            "expected": {"menus": [{"name": "빵", "price": "5300", "description": None,
                                     "imageUrls": [], "groupName": None}],
                         "diagnostics": {"assessment": "READY", "menuAllowed": True, "issueCodes": []},
                         "evidence": {"menuComplete": True, "identityVerified": True, "menuNames": ["빵"]}}}


class CurrentValidatorContracts(unittest.TestCase):
    def test_requires_independent_oracle_and_current_case_roles(self):
        row = fixture()
        current.check_fixtures([row])
        for field in ("oracle", "role", "external_id"):
            broken = {key: value for key, value in row.items() if key != field}
            with self.subTest(field=field), self.assertRaises(ValueError):
                current.check_fixtures([broken])
        with self.assertRaises(ValueError):
            current.check_fixtures([{**row, "oracle": {"kind": "MODEL_GENERATED", "reference": "test"}}])
        current.check_fixtures([{key: value for key, value in row.items() if key != "oracle"}], independent=False)

    def test_oracle_type_and_exact_list_values_cannot_be_forged(self):
        row = fixture()
        actual = copy.deepcopy(row["expected"])
        actual["diagnostics"]["menuAllowed"] = 1
        result = current.compare_cases([row], [{"id": row["id"], "actual": actual}])
        self.assertFalse(result[0]["passed"])
        actual = copy.deepcopy(row["expected"])
        actual["menus"] = []
        self.assertFalse(current.compare_cases([row], [{"id": row["id"], "actual": actual}])[0]["passed"])

    def test_nested_oracle_retains_fixed_complete_guard(self):
        row = fixture()
        actual = copy.deepcopy(row["expected"])
        actual["evidence"]["representativeNames"] = []
        self.assertTrue(current.compare_cases([row], [{"id": row["id"], "actual": actual}])[0]["passed"])
        actual["evidence"]["menuComplete"] = False
        self.assertFalse(current.compare_cases([row], [{"id": row["id"], "actual": actual}])[0]["passed"])

    def test_regression_count_is_frozen_per_manifest_not_historical_eleven(self):
        report = {"found": 40, "succeeded": 40, "failed": 0, "skipped": 0, "aborted": 0}
        self.assertTrue(current.regression_passes(report, 40))
        self.assertFalse(current.regression_passes(report, 11))
        self.assertFalse(current.regression_passes({**report, "skipped": 1}, 40))
        self.assertFalse(current.regression_passes({**report, "aborted": 1}, 40))
        self.assertFalse(current.regression_passes({**report, "found": 0, "succeeded": 0}))

    def test_missing_baseline_role_and_protected_candidate_paths_rejected(self):
        candidates = {path: "placeholder" for path in provenance.EDITABLE_PATHS}
        with tempfile.TemporaryDirectory() as temporary:
            for rows in ([fixture()], [fixture(role="NEW_STRUCTURE")],
                         [fixture(role="NEW_STRUCTURE"), fixture("counter", role="CONTRACT_COUNTEREXAMPLE")]):
                with self.assertRaises(ValueError):
                    current.validate(Path("missing"), candidates, rows, Path(temporary) / "run", expected_manifest_digest="x")
            rows = [fixture(), fixture("changed", "ControlledMenuV9", "NEW_STRUCTURE")]
            candidates["src/main/java/sweetgirl/dataops/snapshot/service/ResponseDiagnostics.java"] = "weaken guards"
            with self.assertRaises(ValueError):
                current.validate(Path("missing"), candidates, rows, Path(temporary) / "run", expected_manifest_digest="x")

    def test_source_to_jar_correspondence_checks_exact_nested_classes(self):
        with patch.object(provenance, "SOURCE_PATHS", ("src/main/java/x/Parser.java",)):
            classes = {"x/Parser.class": b"outer", "x/Parser$Inner.class": b"inner"}
            entries = {"BOOT-INF/classes/" + key: value for key, value in classes.items()}
            provenance.check_class_correspondence(classes, entries)
            with self.assertRaisesRegex(ValueError, "class bytes differ"):
                provenance.check_class_correspondence(classes, {**entries, "BOOT-INF/classes/x/Parser$Stale.class": b"old"})
            with self.assertRaisesRegex(ValueError, "class bytes differ"):
                provenance.check_class_correspondence(classes, {**entries, "BOOT-INF/classes/x/Parser.class": b"different"})
            with self.assertRaisesRegex(ValueError, "source set differs"):
                provenance.check_class_correspondence({}, entries)

    def test_candidate_jar_preserves_unrelated_content_removes_old_nested_and_is_deterministic(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / "base.jar"
            parser = provenance.PARSER_PATH.removeprefix("src/main/java/").removesuffix(".java")
            with zipfile.ZipFile(base, "w") as jar:
                jar.writestr("BOOT-INF/classes/" + parser + ".class", b"old")
                jar.writestr("BOOT-INF/classes/" + parser + "$Old.class", b"stale")
                jar.writestr("BOOT-INF/lib/example.jar", b"must stay")
                jar.writestr("META-INF/MANIFEST.MF", b"manifest")
            classes = {parser + ".class": b"new", parser + "$New.class": b"nested"}
            current._candidate_jar(base, root / "one.jar", classes, b"{}")
            current._candidate_jar(base, root / "two.jar", classes, b"{}")
            self.assertEqual((root / "one.jar").read_bytes(), (root / "two.jar").read_bytes())
            with zipfile.ZipFile(root / "one.jar") as jar:
                self.assertNotIn("BOOT-INF/classes/" + parser + "$Old.class", jar.namelist())
                self.assertEqual(b"must stay", jar.read("BOOT-INF/lib/example.jar"))
                self.assertEqual(b"new", jar.read("BOOT-INF/classes/" + parser + ".class"))

    def test_signed_and_duplicate_jars_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with zipfile.ZipFile(root / "signed.jar", "w") as jar:
                jar.writestr("META-INF/SIGNER.SF", b"signature")
            with self.assertRaisesRegex(ValueError, "Signed"):
                provenance._jar_entries(root / "signed.jar")
            with zipfile.ZipFile(root / "duplicate.jar", "w") as jar:
                jar.writestr("same", b"first")
                with self.assertWarns(UserWarning):
                    jar.writestr("same", b"second")
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                provenance._jar_entries(root / "duplicate.jar")

    def test_digest_mismatch_rejected_before_runtime_or_files(self):
        manifest = {"version": provenance.VERSION, "provenance": {"mode": "COMMITTED_SOURCE"}}
        manifest["manifest_digest"] = provenance.manifest_digest(manifest)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "manifest.json"
            path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "digest/version"), patch.object(current, "prepare_current_runtime") as prepare:
                provenance.verify_deployment(path, expected_manifest_digest="wrong")
            prepare.assert_not_called()
            manifest["provenance"]["mode"] = "ISOLATED_WORKING_TREE"
            path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "digest/version"):
                provenance.verify_deployment(path, expected_manifest_digest=manifest["manifest_digest"])

    def test_development_manifest_cannot_be_used_for_production(self):
        manifest = {"version": provenance.VERSION, "provenance": {"mode": "ISOLATED_WORKING_TREE"}}
        manifest["manifest_digest"] = provenance.manifest_digest(manifest)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "manifest.json"
            path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "development-only"):
                provenance.verify_deployment(path, expected_manifest_digest=manifest["manifest_digest"])

    def test_container_is_offline_unprivileged_and_has_no_oracle(self):
        observed = {}
        classes = {path.removeprefix("src/main/java/").removesuffix(".java") + ".class": b"compiled" for path in provenance.SOURCE_PATHS}

        def fake_run(command, name):
            observed["command"] = command
            mount = next(value for value in command if ",dst=/input," in value)
            inputs = Path(mount.split("src=", 1)[1].split(",dst=", 1)[0])
            observed["input"] = json.loads((inputs / "fixtures.json").read_text())
            return {"exit_code": 0, "stopped": None, "elapsed_seconds": 0,
                    "output": json.dumps({"regression": {}, "cases": [], "parser_digest": "x", "profile_digest": "y",
                        "classes": {path: {"sha256": historical.digest(value), "bytes": base64.b64encode(value).decode()} for path, value in classes.items()}})}

        with tempfile.TemporaryDirectory() as temporary, patch.object(historical, "_bounded_run", side_effect=fake_run):
            result = current.compile_and_run(historical.REPO, {}, [fixture()], Path(temporary) / "run", {"image_id": "sha256:fixed"})
        self.assertEqual("OK", result["status"])
        self.assertEqual({"id", "raw", "external_id"}, set(observed["input"][0]))
        command = observed["command"]
        self.assertEqual("none", command[command.index("--network") + 1])
        self.assertEqual("10001:10001", command[command.index("--user") + 1])
        self.assertIn("--read-only", command)
        self.assertNotIn("ANTHROPIC_API_KEY", " ".join(command))


@unittest.skipUnless(os.environ.get("CURRENT_VALIDATOR_JAR"), "set CURRENT_VALIDATOR_JAR for real offline Java/JAR provenance test")
class CurrentValidatorDockerIntegration(unittest.TestCase):
    def test_current_baseline_new_structure_and_profile_repair_with_independent_guards(self):
        output = Path(os.environ.get("CURRENT_VALIDATOR_OUTPUT", tempfile.mkdtemp()))
        output.mkdir(parents=True, exist_ok=True)
        manifest = provenance.freeze_deployment(historical.REPO, Path(os.environ["CURRENT_VALIDATOR_JAR"]), output / "deployment", allow_working_tree=True)
        candidates = {path: (historical.REPO / path).read_text() for path in provenance.EDITABLE_PATHS}
        profile = json.loads(candidates[provenance.PROFILE_PATH])
        profile["entities"].append({"typename": "ControlledMenuV9", "priceShape": "DISPLAY_TEXT_WON", "imageShapes": ["STRING", "URL_OBJECT"]})
        candidates[provenance.PROFILE_PATH] = json.dumps(profile, ensure_ascii=False)
        rows = [fixture(), fixture("controlled-new-type", "ControlledMenuV9", "NEW_STRUCTURE")]
        partial = fixture("missing-total", role="CONTRACT_COUNTEREXAMPLE")
        del partial["raw"]["ROOT_QUERY"]['placeDetail({"input":{"id":"123"}})']["placeMenus"]["menuCount"]
        partial["expected"]["diagnostics"] = {"assessment": "PARTIAL", "menuAllowed": False, "issueCodes": ["MENU_INCOMPLETE"]}
        partial["expected"]["evidence"]["menuComplete"] = False
        rows.append(partial)
        result = current.validate(output / "deployment/manifest.json", candidates, rows, output / "candidate",
                                  expected_manifest_digest=manifest["manifest_digest"], require_committed=False)
        self.assertTrue(result["passed"], json.dumps(result, ensure_ascii=False)[-10000:])
        self.assertFalse(result["baseline"]["cases"][1]["passed"])
        self.assertTrue(result["baseline"]["cases"][0]["passed"])
        self.assertTrue(all(row["passed"] for row in result["cases"]))
        self.assertTrue(Path(result["artifact"]["path"]).is_file())
        # Correct parser output alone is insufficient when the profile is unchanged.
        baseline_candidates = {path: (historical.REPO / path).read_text() for path in provenance.EDITABLE_PATHS}
        unchanged = current.validate(output / "deployment/manifest.json", baseline_candidates, rows, output / "unchanged",
                                     expected_manifest_digest=manifest["manifest_digest"], require_committed=False)
        self.assertFalse(unchanged["passed"])
        self.assertNotIn("artifact", unchanged)
        # The captured current source must remain exactly bound to its compiled JAR.
        snapshot = output / "deployment/snapshot" / provenance.PARSER_PATH
        snapshot.write_text(snapshot.read_text() + "\n// changed after freeze\n")
        with self.assertRaisesRegex(ValueError, "frozen source changed"):
            provenance.verify_deployment(output / "deployment/manifest.json", expected_manifest_digest=manifest["manifest_digest"], require_committed=False)


if __name__ == "__main__":
    unittest.main()
