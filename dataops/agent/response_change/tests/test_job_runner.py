import ast
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

from agent.response_change import job_runner as jobs
from agent.response_change.approval import content_digest
from agent.response_change.release import ReleaseHold

ROOT=Path(__file__).resolve().parents[3]


class Fixture:
    def initialize(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();self.root.chmod(0o700)
        self.code=self.root/'code';self.code.mkdir(mode=0o700)
        self.source=self.code/'agent/response_change/job_runner.py';self.source.parent.mkdir(parents=True)
        self.source.write_text('# immutable test source\n')
        self.receipt=self.root/'installation.json'
        self.receipt_value={'kind':'FROZEN_CONTROLLER_SOURCE_V1','source_commit':'a'*40,'code_directory':str(self.code),
            'files':{'agent/response_change/job_runner.py':hashlib.sha256(self.source.read_bytes()).hexdigest()}}
        self.receipt_value['receipt_digest']=content_digest(self.receipt_value)
        self.write(self.receipt,self.receipt_value)

    def write(self,path,value):
        path.write_text(json.dumps(value));path.chmod(0o600);return path

    def config(self,kind='collection',job_id=None):
        job_id=job_id or kind
        secret=self.root/(job_id+'-secrets.json')
        self.write(secret,{name:'fixture-only' for name in jobs.SECRET_KEYS[kind]})
        settings={
            'collection':{'server_port':8090,'max_hours':2,'max_stores':50,'spool_directory':str(self.root/'spool'),'spool_max_bytes':1024**2},
            'analysis':{'baseline_pointer':str(self.root/'baseline.json'),'registry_directory':str(self.root/'registry'),
                'service_id':'service','review_config':str(self.root/'review.json'),'output_directory':str(self.root/'worker')},
            'orchestration':{'configuration':str(self.root/'orchestration.json')},
            'publisher':{'repository':'example/dataops','review_kind':'REPORT_ONLY'},'status':{'repository':'example/dataops'}}[kind]
        value={'job_id':job_id,'kind':kind,'installation_receipt':str(self.receipt),'repository_path':str(self.root/'repository'),
            'state_directory':str(self.root/'states'/job_id),'secrets_path':str(secret),'settings':settings}
        path=self.write(self.root/(job_id+'.json'),value)
        return value,path


class JobRunnerTest(Fixture,unittest.TestCase):
    def setUp(self):self.initialize()

    def test_source_receipt_binds_commit_all_files_and_executing_source(self):
        self.assertEqual(self.code,jobs.verify_installation(self.receipt,require_executing=False))
        with self.assertRaisesRegex(ReleaseHold,'EXECUTING_INSTALLATION_MISMATCH'):jobs.verify_installation(self.receipt)
        with patch.object(jobs,'__file__',str(self.source)):
            self.assertEqual(self.code,jobs.verify_installation(self.receipt))
        self.source.write_text('# modified')
        with self.assertRaisesRegex(ReleaseHold,'INSTALLED_SOURCE_CHANGED'):jobs.verify_installation(self.receipt,require_executing=False)

    def test_unlisted_file_symlink_and_invalid_commit_fail_closed(self):
        extra=self.code/'sitecustomize.py';extra.write_text('unexpected')
        with self.assertRaisesRegex(ReleaseHold,'INSTALLATION_FILE_INVENTORY_CHANGED'):jobs.verify_installation(self.receipt,require_executing=False)
        extra.unlink();extra.symlink_to(self.receipt)
        with self.assertRaises(ReleaseHold):jobs.verify_installation(self.receipt,require_executing=False)
        extra.unlink();self.receipt_value['source_commit']=None
        self.receipt_value['receipt_digest']=content_digest({k:v for k,v in self.receipt_value.items() if k!='receipt_digest'})
        self.write(self.receipt,self.receipt_value)
        with self.assertRaises(ReleaseHold):jobs.verify_installation(self.receipt,require_executing=False)

    def test_non_string_ids_and_settings_are_controlled_holds(self):
        value,path=self.config()
        for field,invalid in (('job_id',None),('job_id',[]),('kind',{}),('kind',9)):
            self.write(path,{**value,field:invalid})
            with self.assertRaises(ReleaseHold):jobs.configuration(path)
        value,_=self.config('analysis');value['settings']['service_id']=None
        with self.assertRaises(ReleaseHold):jobs.command(value,self.code)
        value,_=self.config('publisher');value['settings']['repository']=None
        with self.assertRaises(ReleaseHold):jobs.command(value,self.code)

    def test_scoped_environment_never_inherits_model_or_database_secrets(self):
        with patch.dict(os.environ,{'ANTHROPIC_API_KEY':'parent-secret','DB_PASSWORD':'parent-secret','AGENT_RELEASE_DSN':'parent-secret','PYTHONPATH':'/untrusted'}):
            for kind in jobs.KINDS:
                value,_=self.config(kind);env=jobs.environment(value)
                self.assertTrue(jobs.SECRET_KEYS[kind]<=set(env))
                self.assertNotIn('parent-secret',env.values());self.assertNotIn('DB_PASSWORD',env);self.assertNotIn('PYTHONPATH',env)
                self.assertEqual('Asia/Seoul',env['TZ'])
                if kind=='collection':
                    self.assertFalse(any('DSN' in name or 'KEY' in name or 'PASSWORD' in name for name in env))
                if kind in {'analysis','orchestration'}:
                    self.assertFalse(Path(env['AGENT_RUNTIME_CACHE_DIRECTORY']).is_relative_to(self.code))
        value,_=self.config('collection');self.write(Path(value['secrets_path']),{'ANTHROPIC_API_KEY':'forbidden'})
        with self.assertRaisesRegex(ReleaseHold,'SCOPED_JOB_SECRETS_REQUIRED'):jobs.environment(value)

    def test_collection_flags_match_actual_sync_worker_and_time_bound(self):
        value,_=self.config();argv=jobs.command(value,self.code)
        tree=ast.parse((ROOT/'scripts/sync_worker.py').read_text())
        accepted={arg.value for node in ast.walk(tree) if isinstance(node,ast.Call) and isinstance(node.func,ast.Attribute)
                  and node.func.attr=='add_argument' for arg in node.args if isinstance(arg,ast.Constant) and isinstance(arg.value,str) and arg.value.startswith('--')}
        self.assertTrue({arg for arg in argv if arg.startswith('--')}<=accepted)
        self.assertEqual('2',argv[argv.index('--max-hours')+1]);self.assertEqual('50',argv[argv.index('--max')+1])
        self.assertIn('http://127.0.0.1:8090',argv)
        value['settings']['max_hours']=2.001
        with self.assertRaisesRegex(ReleaseHold,'COLLECTION_TIME_BOUND'):jobs.command(value,self.code)

    def test_lock_prevents_same_job_concurrency(self):
        state=self.root/'state'
        def attempt():
            with jobs.job_lock(state):return True
        with jobs.job_lock(state),ThreadPoolExecutor(max_workers=1) as executor:
            with self.assertRaisesRegex(ReleaseHold,'JOB_ALREADY_RUNNING'):executor.submit(attempt).result()

    def test_log_symlink_hardlink_and_open_permissions_never_follow(self):
        target=self.root/'target';target.write_text('unchanged');target.chmod(0o600)
        log=self.root/'child.log';log.symlink_to(target)
        with self.assertRaises(OSError):jobs.private_log(log)
        self.assertEqual('unchanged',target.read_text());log.unlink()
        os.link(target,log)
        with self.assertRaisesRegex(ReleaseHold,'PRIVATE_JOB_LOG_REQUIRED'):jobs.private_log(log)
        log.unlink();log.write_text('');log.chmod(0o644)
        with self.assertRaises(ReleaseHold):jobs.private_log(log)
        log.unlink()
        with jobs.private_log(log) as output:output.write(b'safe')
        self.assertEqual(0o600,stat.S_IMODE(log.stat().st_mode))

    def test_real_owned_child_timeout_terminates_and_is_not_reported_success(self):
        started=time.monotonic()
        with jobs.private_log(self.root/'timeout.log') as output:
            code,unknown=jobs._run_child([sys.executable,'-c','import time; time.sleep(30)'],self.root,
                {'PATH':'/usr/bin:/bin'},output,.05)
        self.assertEqual((124,True),(code,unknown));self.assertLess(time.monotonic()-started,3)

    def test_run_records_only_process_result_and_collection_env_is_credential_free(self):
        value,_=self.config()
        def child(args,root,env,output,timeout):
            self.assertEqual(7190,timeout)
            self.assertEqual(value['settings']['spool_directory'],env['DATAOPS_CAPTURE_DIR'])
            self.assertNotIn('ANTHROPIC_API_KEY',env);self.assertFalse(any(name.endswith('DSN') for name in env))
            return 0,False
        with patch.object(jobs,'verify_installation',return_value=self.code),patch.object(jobs,'_run_child',side_effect=child):
            result=jobs.run(value)
        self.assertEqual('PROCESS_EXITED',result['state']);self.assertEqual('DURABLE_DOMAIN_JOURNALS',result['domain_result_authority'])
        state=Path(value['state_directory'])
        self.assertEqual(0o600,stat.S_IMODE((state/'child.log').stat().st_mode))
        self.assertEqual(result,json.loads((state/'last-run.json').read_text()))

    def test_frozen_configuration_digest_blocks_mutation_before_child(self):
        value,path=self.config();before=hashlib.sha256(path.read_bytes()).hexdigest()
        value['settings']['max_stores']=51;self.write(path,value)
        with patch.object(jobs,'run') as run:
            self.assertEqual(2,jobs.main(['--configuration',str(path),'--configuration-digest',before,'--live']))
            run.assert_not_called()

    def test_external_runtime_cache_rejects_source_or_shared_paths(self):
        from agent.response_change import validator
        cache=self.root/'runtime-cache'
        with patch.dict(os.environ,{'AGENT_RUNTIME_CACHE_DIRECTORY':str(cache)}):
            self.assertEqual(cache/'validator',validator.runtime_path('validator'))
            self.assertEqual(0o700,stat.S_IMODE(cache.stat().st_mode))
        with patch.dict(os.environ,{'AGENT_RUNTIME_CACHE_DIRECTORY':str(validator.REPO/'artifacts/cache')}):
            with self.assertRaisesRegex(ValueError,'EXTERNAL_PRIVATE_RUNTIME_CACHE_REQUIRED'):validator.runtime_path('validator')
        cache.chmod(0o755)
        with patch.dict(os.environ,{'AGENT_RUNTIME_CACHE_DIRECTORY':str(cache)}):
            with self.assertRaises(ValueError):validator.runtime_path('validator')


if __name__=='__main__':unittest.main()
