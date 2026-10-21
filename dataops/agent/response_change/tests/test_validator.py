import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from agent.response_change import validator


class ValidatorContractTests(unittest.TestCase):
    def fixture(self):
        return {"id": "fixed", "raw": {"Menu:1": {"name": "bread"}}, "expected": {"menus": []}}

    def test_independent_expected_values_cannot_be_replaced_by_candidate_output(self):
        fixed = self.fixture()
        result = validator.compare_outputs([fixed], [{"id": "fixed", "actual": {"menus": [{"name": "wrong"}]}}])
        self.assertFalse(result[0]["passed"])
        self.assertEqual([], result[0]["mismatches"]["menus"]["expected"])

    def test_missing_duplicate_or_unexpected_case_is_rejected(self):
        with self.assertRaises(ValueError):
            validator.compare_outputs([self.fixture()], [])
        with self.assertRaises(ValueError):
            validator.compare_outputs([self.fixture()], [{"id": "invented", "actual": {}}])
        with self.assertRaises(ValueError):
            validator._fixture_check([self.fixture(), self.fixture()])

    def test_empty_oracle_and_unknown_contract_fields_are_rejected(self):
        with self.assertRaises(ValueError):
            validator._fixture_check([])
        with self.assertRaises(ValueError):
            validator._fixture_check([{**self.fixture(), "expected": {}}])
        with self.assertRaises(ValueError):
            validator._fixture_check([{**self.fixture(), "expected": {"pageMissing": False}}])

    def test_api_secret_not_in_cli_environment(self):
        with patch.dict(validator.os.environ, {"ANTHROPIC_API_KEY": "test-secret", "OTHER_SECRET": "test-secret"}):
            env = validator._docker_env()
        self.assertNotIn("ANTHROPIC_API_KEY", env)
        self.assertNotIn("OTHER_SECRET", env)

    def test_historical_and_current_sources_satisfy_candidate_policy(self):
        baseline = (validator.TRUSTED / "baseline/NaverPlaceParser.java").read_text()
        current = (validator.REPO / "src/main/java/sweetgirl/dataops/snapshot/parser/NaverPlaceParser.java").read_text()
        self.assertEqual([], validator.candidate_policy_violations(baseline))
        self.assertEqual([], validator.candidate_policy_violations(current))

    def test_output_forgery_runtime_file_io_and_reflection_are_rejected_before_docker(self):
        attempts = [
            'class Candidate { void parse() { System.out.print("forged validation"); } }',
            'class Candidate { void parse() { java.lang.Runtime.getRuntime().exec("cat /input/fixtures.json"); } }',
            'import java.nio.file.Files; class Candidate {}',
            'class Candidate { void parse() { java.io.File file = null; } }',
            'class Candidate { void parse() { java.net.Socket socket = null; } }',
            'class Candidate { void parse() { getClass().getDeclaredMethods(); } }',
            'class Candidate { void parse() { Class.forName("java.lang.System"); } }',
            'class Candidate { void parse() { Object.class.getMethods(); } }',
            'import static java.lang.System.out; class Candidate {}',
            r'class Candidate { void parse() { Syst\u0065m.out.print("forged"); } }',
            'import java.util.ServiceLoader; class Candidate {}',
            'import java.util.*; class Candidate {}',
            'class Candidate { void parse() { java . nio . file . Files.readAllBytes(null); } }',
        ]
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(validator, "prepare_runtime") as prepare:
                for index, source in enumerate(attempts):
                    with self.subTest(source=source):
                        result = validator.validate(source, [self.fixture()], Path(directory) / str(index))
                        self.assertFalse(result["passed"])
                        self.assertEqual("SOURCE_POLICY_REJECTED", result["status"])
                        self.assertTrue(result["source_policy"]["violations"])
                prepare.assert_not_called()

    def test_policy_ignores_nonexecuting_strings_and_comments(self):
        source = 'class Candidate { String name = "System.getClass java.nio.file.Files"; /* Runtime.exec */ }'
        self.assertEqual([], validator.candidate_policy_violations(source))

    def test_allowed_collection_import_and_qualified_regex_can_be_added(self):
        source = 'import java.util.LinkedHashMap; class Candidate { Object p = java.util.regex.Pattern.compile("a"); }'
        self.assertEqual([], validator.candidate_policy_violations(source))

    def test_container_is_restricted_and_oracle_is_not_mounted(self):
        observed = {}

        def fake_run(command, name):
            observed["command"] = command
            input_mount = next(arg for arg in command if ",dst=/input," in arg)
            mounted_path = Path(input_mount.split("src=", 1)[1].split(",dst=", 1)[0])
            observed["input"] = json.loads((mounted_path / "fixtures.json").read_text())
            return {"exit_code": 0, "stopped": None, "elapsed_seconds": 0,
                    "output": json.dumps({"regression": {"found": 11, "succeeded": 11, "failed": 0, "skipped": 0},
                                           "cases": [{"id": "fixed:0", "actual": {"menus": []}}]})}

        with tempfile.TemporaryDirectory() as directory:
            with patch.object(validator, "prepare_runtime", return_value={"image_id": "sha256:trusted", "jars": {}}), \
                    patch.object(validator, "_bounded_run", side_effect=fake_run):
                result = validator.validate("class Placeholder {}", [self.fixture()], Path(directory) / "validation")
            self.assertTrue(result["passed"])
            command = observed["command"]
            self.assertEqual("none", command[command.index("--network") + 1])
            self.assertEqual("10001:10001", command[command.index("--user") + 1])
            self.assertIn("--read-only", command)
            self.assertIn("no-new-privileges", command)
            self.assertEqual("ALL", command[command.index("--cap-drop") + 1])
            self.assertNotIn("docker.sock", " ".join(command))
            self.assertNotIn(".env", " ".join(command))
            self.assertEqual({"id", "raw"}, set(observed["input"][0]))

    def test_skipped_or_removed_fixed_tests_fail_even_when_generated_cases_pass(self):
        result_json = {"regression": {"found": 10, "succeeded": 10, "failed": 0, "skipped": 0},
                       "cases": [{"id": "fixed:0", "actual": {"menus": []}},
                                 {"id": "proposed:0", "actual": {"menus": []}}]}
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(validator, "prepare_runtime", return_value={"image_id": "sha256:trusted", "jars": {}}), \
                    patch.object(validator, "_bounded_run", return_value={"exit_code": 0, "stopped": None,
                                                                         "elapsed_seconds": 0, "output": json.dumps(result_json)}):
                result = validator.validate("class Placeholder {}", [self.fixture()], Path(directory) / "validation",
                                            proposed_fixtures=[self.fixture()])
            self.assertFalse(result["passed"])
            self.assertEqual("ASSERTION_FAILED", result["status"])

    def test_runtime_unavailability_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(validator, "prepare_runtime", side_effect=OSError("Docker unavailable")):
                result = validator.validate("class Placeholder {}", [self.fixture()], Path(directory) / "validation")
            self.assertFalse(result["passed"])
            self.assertEqual("INFRA_ERROR", result["status"])
            self.assertTrue((Path(directory) / "validation/validation.json").exists())


if __name__ == "__main__":
    unittest.main()
