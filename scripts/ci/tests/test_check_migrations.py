import datetime
import importlib.util
import pathlib
import unittest

SPEC = importlib.util.spec_from_file_location(
    'check_migrations', pathlib.Path(__file__).resolve().parents[1] / 'check_migrations.py')
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)

NOW = datetime.datetime(2026, 11, 5, 14, 0, tzinfo=guard.KST)
EXISTING = ['V20260604.1__user_report_api.sql', 'V20261104110000__record_comment_parent.sql']
NEW = 'V20261105130000__add_something.sql'


def run(sql='SELECT 1;', status='A', name=NEW, allow=False):
    return guard.check([(status, f'{guard.MIGRATION_DIR}/{name}')], EXISTING, lambda _: sql, NOW, allow)


class BreakingChangesTest(unittest.TestCase):
    def test_blocks_changes_that_break_v1(self):
        cases = {
            'ALTER TABLE a DROP COLUMN b;': 'DROP COLUMN',
            'ALTER TABLE a DROP COLUMN IF EXISTS b;': 'DROP COLUMN',
            'ALTER TABLE a DROP b;': 'DROP COLUMN',
            'DROP TABLE a;': 'DROP TABLE',
            'DROP TABLE IF EXISTS a;': 'DROP TABLE',
            'TRUNCATE a;': 'TRUNCATE',
            'ALTER TABLE a RENAME TO b;': 'RENAME',
            'ALTER TABLE a RENAME COLUMN b TO c;': 'RENAME',
            'ALTER TABLE a ALTER COLUMN b SET NOT NULL;': 'SET NOT NULL',
            'ALTER TABLE a ALTER COLUMN b TYPE text;': 'ALTER COLUMN TYPE',
            'ALTER TABLE a ALTER b SET DATA TYPE text;': 'ALTER COLUMN TYPE',
            'ALTER TABLE a ADD COLUMN b bigint NOT NULL;': '기본값 없는 NOT NULL 컬럼',
            'ALTER TABLE a ADD b bigint NOT NULL;': '기본값 없는 NOT NULL 컬럼',
            'ALTER TABLE a ADD COLUMN IF NOT EXISTS b numeric(10,2) NOT NULL;': '기본값 없는 NOT NULL 컬럼',
        }
        for sql, reason in cases.items():
            with self.subTest(sql=sql):
                self.assertIn(reason, guard.breaking_changes(sql))

    def test_allows_additive_changes(self):
        cases = [
            'CREATE TABLE a (id bigint NOT NULL, name text NOT NULL);',
            'ALTER TABLE a ADD COLUMN b bigint;',
            'ALTER TABLE a ADD COLUMN b bigint NOT NULL DEFAULT 0;',
            'ALTER TABLE a ADD COLUMN IF NOT EXISTS b numeric(10,2) NOT NULL DEFAULT 0, ADD COLUMN c text;',
            'ALTER TABLE a ADD CONSTRAINT a_uq UNIQUE (b);',
            'ALTER TABLE a DROP CONSTRAINT IF EXISTS a_uq;',
            'ALTER TABLE a ALTER COLUMN b DROP DEFAULT;',
            'ALTER TABLE a ALTER COLUMN b DROP NOT NULL;',
            'CREATE INDEX i ON a (b);',
            'DROP INDEX IF EXISTS i;',
            "-- DROP TABLE a 는 하지 않는다\nCOMMENT ON TABLE a IS 'DROP COLUMN 금지 RENAME';",
            'CREATE TEMP TABLE t ON COMMIT DROP AS SELECT 1;',
        ]
        for sql in cases:
            with self.subTest(sql=sql):
                self.assertEqual(guard.breaking_changes(sql), [])


class CheckTest(unittest.TestCase):
    def test_additive_new_file_passes(self):
        self.assertEqual(run(), ([], []))

    def test_modified_or_deleted_file_fails_even_with_label(self):
        for status in 'MD':
            with self.subTest(status=status):
                errors, _ = run(status=status, name=EXISTING[1], allow=True)
                self.assertEqual(len(errors), 1)

    def test_version_older_than_latest_fails(self):
        errors, _ = run(name='V20261104100000__older.sql')
        self.assertIn('최신', errors[0])

    def test_equal_version_fails(self):
        self.assertTrue(run(name='V20261104110000__same.sql')[0])

    def test_far_future_version_fails(self):
        errors, _ = run(name='V20991231235959__future.sql')
        self.assertIn('미래', errors[0])

    def test_bad_filename_fails(self):
        for name in ('V20261105__short.sql', 'V20261105130000__Upper.sql', 'add_something.sql'):
            with self.subTest(name=name):
                self.assertIn('파일명', run(name=name)[0][0])

    def test_breaking_sql_fails_without_label(self):
        errors, allowed = run('ALTER TABLE a DROP COLUMN b;')
        self.assertEqual(len(errors), 1)
        self.assertEqual(allowed, [])

    def test_label_only_allows_breaking_sql(self):
        errors, allowed = run('ALTER TABLE a DROP COLUMN b;', allow=True)
        self.assertEqual(errors, [])
        self.assertEqual(len(allowed), 1)
        self.assertTrue(run('DROP TABLE a;', name='V20260101000000__old.sql', allow=True)[0])


if __name__ == '__main__':
    unittest.main()
