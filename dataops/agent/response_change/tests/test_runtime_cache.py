"""Real-process cache races with synthetic dependencies and no Docker/network effects."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from agent.response_change import validator

ROOT = Path(__file__).resolve().parents[3]
CHILD = r'''
import json, sys, time
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from agent.response_change import validator, current_validator
home, mode = Path(sys.argv[1]), sys.argv[2]
def docker(*args, **kwargs):
    if args[-1] == validator.IMAGE_TAG:
        with (home / 'image-pins.log').open('a') as stream: stream.write('pin\n')
        time.sleep(.03)
    return SimpleNamespace(stdout=json.dumps([{'Id':'sha256:fixed-image','RepoDigests':['image@sha256:fixed']}]))
legacy = (('test.legacy','legacy','1'),)
current = (('test.current','current','2'),)
with patch.object(validator,'DEPENDENCIES',legacy), patch.object(current_validator,'CURRENT_DEPENDENCIES',current), \
        patch.object(current_validator,'EXTRA_DEPENDENCIES',()), patch.object(validator,'_docker',side_effect=docker), \
        patch.object(Path,'home',return_value=home if mode=='initial' else home/'no-gradle-cache'):
    print(json.dumps(current_validator.prepare_current_runtime(),sort_keys=True))
'''


class RuntimeCacheConcurrencyTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();self.home=self.root/'home';self.home.mkdir()
        self.cache=self.root/'external-runtime-cache'
        for group,artifact,version in (('test.legacy','legacy','1'),('test.current','current','2')):
            directory=self.home/'.gradle/caches/modules-2/files-2.1'/group/artifact/version/'known'
            directory.mkdir(parents=True)
            (directory/f'{artifact}-{version}.jar').write_bytes((artifact+'-fixed-bytes').encode())

    def child(self, mode='initial'):
        return subprocess.run([sys.executable,'-c',CHILD,str(self.home),mode],cwd=ROOT,
            env={**os.environ,'AGENT_RUNTIME_CACHE_DIRECTORY':str(self.cache),'PYTHONDONTWRITEBYTECODE':'1'},
            text=True,capture_output=True,timeout=20)

    def test_parallel_initialization_pins_once_and_warm_reads_never_rewrite(self):
        with ThreadPoolExecutor(max_workers=6) as pool:
            results=list(pool.map(lambda _:self.child(),range(6)))
        for result in results:self.assertEqual(0,result.returncode,result.stderr)
        outputs=[json.loads(result.stdout) for result in results]
        self.assertTrue(all(result==outputs[0] for result in outputs))
        self.assertEqual(['pin'],(self.home/'image-pins.log').read_text().splitlines())
        manifests=list(self.cache.glob('*/manifest.json'))
        self.assertEqual(2,len(manifests))
        original={str(path):(path.read_bytes(),path.stat().st_ino,path.stat().st_mtime_ns) for path in manifests}
        # A populated, verified runtime is independent of a user's Gradle eviction.
        with ThreadPoolExecutor(max_workers=6) as pool:
            warmed=list(pool.map(lambda _:self.child('warm'),range(6)))
        for result in warmed:
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertEqual(outputs[0],json.loads(result.stdout))
        self.assertEqual(original,{str(path):(path.read_bytes(),path.stat().st_ino,path.stat().st_mtime_ns) for path in manifests})
        self.assertFalse(any(self.cache.rglob('*.tmp')))
        self.assertEqual('sha256:fixed-image',outputs[0]['image_id'])

    def test_cached_dependency_or_metadata_drift_fails_closed(self):
        self.assertEqual(0,self.child().returncode)
        directory=self.cache/'current-validator-runtime-jackson312'
        dependency=directory/'lib/current-2.jar'
        saved=dependency.read_bytes();dependency.write_bytes(b'changed')
        result=self.child('warm')
        self.assertNotEqual(0,result.returncode)
        self.assertIn('Trusted dependency changed',result.stderr)
        dependency.write_bytes(saved)
        path=directory/'manifest.json';manifest=json.loads(path.read_text())
        manifest['image_id']='sha256:unreviewed';path.write_text(json.dumps(manifest))
        result=self.child('warm')
        self.assertNotEqual(0,result.returncode)
        self.assertIn('Pinned current runtime changed',result.stderr)

    def test_initial_manifest_is_once_only_and_lock_rejects_symlink(self):
        directory=self.root/'private';directory.mkdir(mode=0o700)
        path=directory/'manifest.json'
        with validator.runtime_lock(directory):
            validator.runtime_manifest_once(path,{'pinned':'first'})
            original=(path.stat().st_ino,path.stat().st_mtime_ns)
            validator.runtime_manifest_once(path,{'pinned':'first'})
            self.assertEqual(original,(path.stat().st_ino,path.stat().st_mtime_ns))
            with self.assertRaisesRegex(ValueError,'Pinned runtime manifest changed'):
                validator.runtime_manifest_once(path,{'pinned':'different'})
        self.assertEqual({'pinned':'first'},json.loads(path.read_text()))
        (directory/'.prepare.lock').unlink()
        (directory/'.prepare.lock').symlink_to(path)
        with self.assertRaises(OSError):
            with validator.runtime_lock(directory):pass


if __name__=='__main__':unittest.main()
