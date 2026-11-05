import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cloud_seed as seed


class SeedTest(unittest.TestCase):
    def test_only_complete_unchanged_public_cycle_can_be_promoted(self):
        with tempfile.TemporaryDirectory() as directory:
            cycle, output = Path(directory)/'cycle', Path(directory)/'next'
            cycle.mkdir()
            for name in seed.allowed_files(['bakeries']):
                target = cycle/name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('fixture\n')
            (cycle/'scope.json').write_text(json.dumps({'track':'public','datasets':['bakeries']}))
            stages = {name: {'exit_code':0,'outputs':{}} for name in ('pull','new-delta','verify','bread','confirm','candidates','closures')}
            stages['pull']['outputs']['matching/localdata_raw/bakeries.jsonl'] = seed.file_hash(cycle/'matching/localdata_raw/bakeries.jsonl')
            stages['closures']['exit_code'] = 4
            (cycle/'checkpoint.json').write_text(json.dumps({'stages':stages}))
            with self.assertRaisesRegex(ValueError, 'incomplete'):
                seed.promote(cycle,output)
            stages['closures']['exit_code'] = 0
            (cycle/'checkpoint.json').write_text(json.dumps({'stages':stages}))
            self.assertEqual(seed.promote(cycle,output)['datasets'], ['bakeries'])
            (cycle/'matching/localdata_raw/bakeries.jsonl').write_text('tampered')
            with self.assertRaisesRegex(ValueError, 'artifact changed'):
                seed.promote(cycle,Path(directory)/'other')

    def test_only_allowlisted_evidence_is_copied_and_corruption_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / 'source', Path(directory) / 'bundle'
            for name in seed.allowed_files(['bakeries']):
                path = source / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('evidence\n')
            (source / '.env').write_text('PASSWORD=do-not-copy\n')
            manifest = seed.create_bundle(source, output, ['bakeries'])
            self.assertEqual(len(manifest['files']), 4)
            self.assertFalse((output / '.env').exists())
            self.assertEqual(seed.validate(output)['datasets'], ['bakeries'])
            with self.assertRaises(ValueError): seed.create_bundle(source, output, ['bakeries'])
            (output / 'matching/localdata_mapping.tsv').write_text('changed')
            with self.assertRaisesRegex(ValueError, 'checksum'): seed.validate(output)

    def test_manifest_cannot_add_a_secret_or_path_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'manifest.json').write_text(json.dumps({'schema_version': 1, 'datasets': ['bakeries'], 'files': {'../.env': {}}}))
            with self.assertRaisesRegex(ValueError, 'file set'): seed.validate(root)
