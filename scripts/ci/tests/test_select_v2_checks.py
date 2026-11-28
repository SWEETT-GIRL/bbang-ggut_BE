import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'select_v2_checks.py'
SPEC = importlib.util.spec_from_file_location('select_v2_checks', SCRIPT)
checks = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checks)


class SelectChecksTest(unittest.TestCase):
    def test_batch_changes_keep_the_whole_batch_build(self):
        for path in (
                'batch/src/main/java/Example.java',
                'batch/src/test/java/StoreObservationBarrierPostgresTest.java',
                'batch/src/main/resources/db/dataops-migration/V1__example.sql',
                'batch/build.gradle', 'dataops/scripts/sync_worker.py',
                'dataops/scripts/tests/test_hold_recovery.py',
                'scripts/deploy/batch/runner.py', 'Dockerfile.batch', 'Dockerfile.batch.dockerignore'):
            with self.subTest(path=path):
                self.assertEqual(checks.select_checks([path]), {
                    'backend': 'true', 'build_scope': 'batch', 'batch': 'true', 'workflows': 'false'})

    def test_dataops_dashboard_and_docs_do_not_expand_batch_scope(self):
        result = checks.select_checks(['batch/src/main/java/Example.java',
                                      'monitoring/grafana/dataops-spring-v0.json',
                                      'docs/runbook.md', '.claude/planning/example.md', 'README.md'])
        self.assertEqual(result['build_scope'], 'batch')
        self.assertEqual(result['batch'], 'true')

    def test_monitoring_alone_does_not_rebuild_the_image(self):
        result = checks.select_checks(['monitoring/grafana/dataops-spring-v0.json'])
        self.assertEqual(result['build_scope'], 'batch')
        self.assertEqual(result['batch'], 'false')

    def test_shared_inputs_always_require_full_validation_and_image_build(self):
        for path in ('core/src/main/java/Shared.java', 'core/src/testFixtures/java/Fixture.java',
                     'core/build.gradle', 'config/checkstyle/rules.xml', 'build.gradle',
                     'settings.gradle', 'gradle.properties', 'gradle/libs.versions.toml',
                     'gradlew', 'gradlew.bat', '.github/workflows/ci-v2.yml',
                     '.github/workflows/cd-batch.yml', 'scripts/ci/select_v2_checks.py'):
            with self.subTest(path=path):
                result = checks.select_checks(['batch/src/main/java/Example.java', path])
                self.assertEqual(result['build_scope'], 'full')
                self.assertEqual(result['backend'], 'true')
                self.assertEqual(result['batch'], 'true')
                self.assertEqual(checks.select_checks([path])['batch'], 'true')

    def test_api_mixed_and_unknown_changes_fall_back_to_full_validation(self):
        for path in ('api/src/main/java/Controller.java', '.github/workflows/cd-v2.yml',
                     'scripts/ci/tests/test_select_v2_checks.py', 'new-module/Example.java',
                     '.github/actions/shared/action.yml', 'monitoring/grafana/course-draft-v0.json'):
            with self.subTest(path=path):
                self.assertEqual(checks.select_checks([path])['build_scope'], 'full')
                self.assertEqual(checks.select_checks([path])['backend'], 'true')
                self.assertEqual(checks.select_checks(['batch/test.java', path])['build_scope'], 'full')
        self.assertEqual(checks.select_checks(['api/test.java'])['batch'], 'false')

    def test_redis_exception_cannot_hide_shared_or_ci_changes(self):
        redis = ['infra/redis/compose.yml', '.github/workflows/cd-redis-prod.yml']
        self.assertEqual(checks.select_checks(redis), {
            'backend': 'false', 'build_scope': 'full', 'batch': 'false', 'workflows': 'true'})
        for path in ('core/Example.java', '.github/workflows/ci-v2.yml',
                     '.github/workflows/cd-v2.yml', 'scripts/ci/select_v2_checks.py'):
            with self.subTest(path=path):
                result = checks.select_checks(redis + [path])
                self.assertEqual(result['backend'], 'true')
                self.assertEqual(result['build_scope'], 'full')

    def test_empty_or_documentation_only_diff_has_no_backend_work(self):
        for paths in ([], ['README.md', 'docs/example.yml', '.claude/settings.json']):
            self.assertEqual(checks.select_checks(paths), {
                'backend': 'false', 'build_scope': 'full', 'batch': 'false', 'workflows': 'false'})


class GitDiffTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'CI scope test')
        self.git('config', 'user.email', 'ci-scope@example.invalid')
        self.git('config', 'commit.gpgsign', 'false')
        self.git('config', 'core.hooksPath', '/dev/null')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, text=True).strip()

    def commit(self, message):
        self.git('add', '--all')
        self.git('commit', '-qm', message)
        return self.git('rev-parse', 'HEAD')

    def test_move_from_core_to_batch_still_selects_full_validation(self):
        old = self.repo / 'core/example.java'
        old.parent.mkdir()
        old.write_text('class Example {}')
        base = self.commit('base')
        new = self.repo / 'batch/example.java'
        new.parent.mkdir()
        old.rename(new)
        head = self.commit('move')
        paths = checks.changed_paths(base, head, cwd=self.repo)
        self.assertCountEqual(paths, ['core/example.java', 'batch/example.java'])
        self.assertEqual(checks.select_checks(paths)['build_scope'], 'full')

    def test_cli_checks_complete_pr_diff_and_handles_newlines_in_paths(self):
        shared = self.repo / 'core/example.java'
        shared.parent.mkdir()
        shared.write_text('class Example {}')
        base = self.commit('base')
        shared.write_text('class Changed {}')
        self.commit('shared change earlier in PR')
        batch = self.repo / 'batch/name with\na newline.java'
        batch.parent.mkdir()
        batch.write_text('class Batch {}')
        head = self.commit('batch change at PR head')
        self.assertCountEqual(checks.changed_paths(base, head, cwd=self.repo),
                              ['core/example.java', 'batch/name with\na newline.java'])
        output = self.repo / 'outputs'
        output.write_text('existing=value\n')
        subprocess.run([sys.executable, str(SCRIPT)], cwd=self.repo, check=True, capture_output=True,
                       env={**os.environ, 'BASE_SHA': base, 'HEAD_SHA': head, 'GITHUB_OUTPUT': str(output)})
        self.assertEqual(dict(line.split('=', 1) for line in output.read_text().splitlines()), {
            'existing': 'value', 'backend': 'true', 'build_scope': 'full',
            'batch': 'true', 'workflows': 'false'})


if __name__ == '__main__':
    unittest.main()
