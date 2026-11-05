import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cloud_runtime as runtime


class CloudRuntimeTest(unittest.TestCase):
    def test_environment_overrides_local_credentials_and_connection_is_read_only(self):
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / '.env'
            env_file.write_text('PROD_DB_URL=jdbc:postgresql://old/soboro\nPROD_DB_USERNAME=old\nPROD_DB_PASSWORD=old\n')
            with patch.dict(os.environ, {'DATAOPS_PROD_DB_URL': 'jdbc:postgresql://rds:5432/soboro?currentSchema=public&sslmode=require',
                                         'DATAOPS_PROD_DB_USERNAME': 'reader', 'DATAOPS_PROD_DB_PASSWORD': 'test'}, clear=True):
                params = runtime.serving_parameters(env_file)
            self.assertEqual((params['host'], params['user'], params['password']), ('rds', 'reader', 'test'))
            self.assertIn('default_transaction_read_only=on', params['options'])
            self.assertEqual(params['sslmode'], 'require')

    def test_no_was_credential_fallback_or_wrong_database(self):
        for url in ['', 'jdbc:postgresql://rds/dataops', 'jdbc:postgresql://user:pass@rds/soboro',
                    'jdbc:postgresql://rds/soboro?sslmode=disable']:
            with patch.dict(os.environ, {'DATAOPS_PROD_DB_URL': url, 'DB_USERNAME': 'writer', 'DB_PASSWORD': 'test'}, clear=True):
                with self.assertRaises(ValueError):
                    runtime.serving_parameters('/missing')

    def test_cycle_id_cannot_escape_storage(self):
        for bad in ['../seed', '/tmp/cycle', '', 'a/b', 'x' * 65]:
            with self.assertRaises(ValueError): runtime.cycle_id(bad)
        self.assertEqual(runtime.cycle_id('2026-11-smoke'), '2026-11-smoke')
