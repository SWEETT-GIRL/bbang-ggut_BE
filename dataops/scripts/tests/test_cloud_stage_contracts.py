import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


class StageContractsTest(unittest.TestCase):
    def test_no_changes_pipeline_produces_valid_empty_artifacts_without_network(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'localdata_mapping.tsv').write_text('mgmt_no\tjudgment\n')
            (root/'new_review_judgments.jsonl').write_text('')
            (root/'localdata_new_candidates.tsv').write_text('mgmt_no\tdataset\tcategory\tname\tlicensed_ymd\tclosed_ymd\tlat\tlon\n')
            (root/'localdata_raw/delta').mkdir(parents=True)
            (root/'localdata_raw/delta/bakeries_new.jsonl').write_text('')
            env = {**os.environ, 'DATAOPS_MATCHING_DIR': directory, 'DATAOPS_RAW_DIR': str(root/'localdata_raw')}
            for script in ('localdata_new_delta.py', 'localdata_new_verify.py', 'localdata_bread_check.py', 'localdata_new_confirmed.py'):
                result = subprocess.run([sys.executable, str(SCRIPTS/script)], env=env, text=True, capture_output=True, timeout=30)
                self.assertEqual(result.returncode, 0, script + '\n' + result.stderr)
            self.assertEqual((root/'new_verify_results.jsonl').read_text(), '')
            self.assertEqual((root/'bread_check_results.jsonl').read_text(), '')
            self.assertEqual(len((root/'localdata_new_confirmed.tsv').read_text().splitlines()), 1)
