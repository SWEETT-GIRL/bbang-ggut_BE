from contextlib import contextmanager
from copy import deepcopy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import uuid

from agent.response_change import baseline_promotion as promotion
from agent.response_change.approval import (ApprovalHold, ApprovalVerifier, PublicationBinding, PullRequestSnapshot,
    content_digest, canonical)
from agent.response_change import full_registry
from agent.response_change import current_manifest, current_validator
from agent.response_change.tests import test_full_registry as fixtures


class PromotionFixture:
    def setup_fixture(self):
        self.fixture=fixtures.FullRegistryTest(methodName='runTest');self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        f=self.fixture;self.registry=f.registry
        self.candidate=f.validate()
        self.review=f.prepare().parser(None,validation_digest=self.candidate['validation_digest'],incident_id='incident',
            run_id='01000000-0000-0000-0000-000000000001',revision=1,expires_at='2099-01-01T00:00:00+00:00')
        self.manifest=self.review.manifest
        self.binding=PublicationBinding('example/dataops',19,self.review.head_sha,self.manifest.digest)
        self.snapshot=PullRequestSnapshot('example/dataops',19,self.review.head_sha,True,self.review.head_sha,
            7,datetime.now(timezone.utc).isoformat(),False,canonical(self.manifest.document['files']),
            self.candidate['candidate_tree_digest'],self.candidate['candidate_tree_digest'],'example/dataops',
            canonical(self.manifest.document['files']))
        self.verifier=ApprovalVerifier(allowed_repository='example/dataops',allowed_actor_ids=[7])
        self.evidence=self.verifier.evaluate(self.manifest,self.binding,self.snapshot)
        operation='release-'+uuid.uuid5(uuid.NAMESPACE_URL,'response-release:'+self.manifest.digest).hex
        self.record={'operation_id':operation,'service_id':'test-service','approval_digest':self.evidence.digest,
            'manifest_digest':self.manifest.digest,'approval_state':'CONSUMED','approval_scope':'DEPLOY_REPLAY_PROPOSE',
            'state':'ACTIVE','pending_action':None,
            'canary_receipt':{'passed':True,'result_digest':self.registry.canary_policy(self.candidate['canary_policy_digest'])['expected_result_digest']},
            'replay_receipt':{'phase':'REPLAY','outcome':'DONE','complete':True,'operating_writes':0,
                'manifest_digest':self.manifest.digest,'targets_digest':content_digest(self.candidate['targets'])}}
        self.live={**{key:self.candidate[key] for key in promotion.ACTIVE_FIELDS},'service_id':'test-service',
            'healthy':True,'writes_enabled':False,'policy_digest':f.base['policy_digest']}
        self.proof={'version':1,'repository':'example/dataops','repository_path':str(f.repo),'allowed_actor_ids':[7],
            'manifest':self.manifest.document,'publication':self.binding.__dict__,'snapshot':self.snapshot.__dict__,
            'approval':self.evidence.__dict__,'verified_at':datetime.now(timezone.utc).isoformat(),
            'release_operation':self.record,'runtime':self.live}


class TransitiveBuildTest(PromotionFixture,unittest.TestCase):
    def setUp(self):self.setup_fixture()

    def test_promotion_preserves_actual_candidate_bytes_and_proves_merged_whole_source(self):
        build=self.registry.register_approved_parser_build(self.proof)
        self.assertEqual('APPROVED_PARSER_SERVICE_BUILD',build['kind'])
        self.assertEqual(self.review.head_sha,build['source_commit'])
        self.assertEqual(self.candidate['artifact_sha256'],build['artifact_sha256'])
        self.assertFalse(build['rebuilt'])
        self.assertEqual(build,self.registry.build_receipt(build['receipt_digest']))
        with patch.object(full_registry,'probe_artifact',return_value=self.fixture.measure):
            release=self.registry.register_base(build_receipt_digest=build['receipt_digest'],release_id='next',
                observe_runtime=lambda:self.live,verify_recovery=lambda *_:self.fixture.recovery)
        self.assertEqual(self.review.head_sha,self.registry.committed_release('next')['source_commit'])
        self.assertEqual(build['artifact_sha256'],release['artifact_sha256'])

    def test_unmerged_unconsumed_incomplete_and_wrong_runtime_never_become_builds(self):
        mutators=[lambda p:p['snapshot'].update(merged=False),
            lambda p:p['release_operation'].update(approval_state='APPROVED'),
            lambda p:p['release_operation'].update(state='REPLAY_HELD'),
            lambda p:p['release_operation']['replay_receipt'].update(complete=False),
            lambda p:p['runtime'].update(generation='other')]
        for mutate in mutators:
            proof=deepcopy(self.proof);mutate(proof)
            with self.subTest(mutate=mutate),self.assertRaises(ApprovalHold):
                self.registry.register_approved_parser_build(proof)

    def test_added_unapproved_code_and_changed_candidate_bytes_hold(self):
        self.fixture.git('checkout','-q',self.review.head_sha)
        (self.fixture.repo/'app.txt').write_text('unapproved change')
        self.fixture.git('add','app.txt');self.fixture.git('commit','-qm','unapproved')
        merge=self.fixture.git('rev-parse','HEAD')
        proof=deepcopy(self.proof)
        proof['snapshot']['merge_commit_sha']=merge
        evidence=self.verifier.evaluate(self.manifest,self.binding,PullRequestSnapshot(**proof['snapshot']))
        proof['approval']=evidence.__dict__;proof['release_operation']['approval_digest']=evidence.digest
        # Even a provider snapshot erroneously retaining the approved tree digest
        # cannot bind a different local merged tree into the complete baseline.
        with self.assertRaisesRegex(ApprovalHold,'PROMOTION_MERGED_TREE_CHANGED'):
            self.registry.register_approved_parser_build(proof)
        Path(self.candidate['artifact_path']).write_bytes(b'changed')
        with self.assertRaises(ApprovalHold):self.registry.register_approved_parser_build(self.proof)

    def test_old_root_build_remains_required_on_every_read(self):
        build=self.registry.register_approved_parser_build(self.proof)
        self.fixture.basejar.write_bytes(b'lost original backup')
        with self.assertRaisesRegex(ApprovalHold,'FULL_BUILD_ARTIFACT_CHANGED'):
            self.registry.build_receipt(build['receipt_digest'])

    def test_second_approved_incident_extends_complete_lineage_without_rebuilding(self):
        first=self.registry.register_approved_parser_build(self.proof)
        with patch.object(full_registry,'probe_artifact',return_value=self.fixture.measure):
            self.registry.register_base(build_receipt_digest=first['receipt_digest'],release_id='next',
                observe_runtime=lambda:self.live,verify_recovery=lambda *_:self.fixture.recovery)
        f=self.fixture
        f.deployment['provenance']['git_commit']=self.review.head_sha
        f.deployment['jar_sha256']=first['artifact_sha256']
        f.jar(f.candidatejar,'third')
        f.candidates[fixtures.PROFILE_PATH]='third profile'
        f.report['artifact'].update(sha256=f.hash(f.candidatejar),base_jar_sha256=first['artifact_sha256'])
        f.report.pop('report_digest');f.report['report_digest']=content_digest(f.report)
        candidate=f.validate(base_release='next',review_id='repair-two',release_id='third',generation='third-generation')
        review=f.prepare().parser(None,validation_digest=candidate['validation_digest'],incident_id='incident-two',
            run_id='02000000-0000-0000-0000-000000000001',revision=1,expires_at='2099-01-01T00:00:00+00:00')
        binding=PublicationBinding('example/dataops',20,review.head_sha,review.manifest.digest)
        snapshot=PullRequestSnapshot('example/dataops',20,review.head_sha,True,review.head_sha,7,
            datetime.now(timezone.utc).isoformat(),False,canonical(review.manifest.document['files']),
            candidate['candidate_tree_digest'],candidate['candidate_tree_digest'],'example/dataops',canonical(review.manifest.document['files']))
        evidence=self.verifier.evaluate(review.manifest,binding,snapshot)
        record=deepcopy(self.record)
        record.update(operation_id='release-'+uuid.uuid5(uuid.NAMESPACE_URL,'response-release:'+review.manifest.digest).hex,
            approval_digest=evidence.digest,manifest_digest=review.manifest.digest)
        record['replay_receipt']['manifest_digest']=review.manifest.digest
        live={**self.live,**{key:candidate[key] for key in promotion.ACTIVE_FIELDS}}
        proof={**self.proof,'manifest':review.manifest.document,'publication':binding.__dict__,'snapshot':snapshot.__dict__,
            'approval':evidence.__dict__,'verified_at':datetime.now(timezone.utc).isoformat(),'release_operation':record,'runtime':live}
        second=self.registry.register_approved_parser_build(proof)
        self.assertEqual(first['receipt_digest'],second['parent_build_receipt_digest'])
        self.assertEqual(review.head_sha,self.registry.build_receipt(second['receipt_digest'])['source_commit'])
        self.assertNotEqual(first['artifact_sha256'],second['artifact_sha256'])


class Cursor:
    def __init__(self,db):self.db=db
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def execute(self,sql,args=None):
        if 'response_agent_active_runtime' not in sql:raise AssertionError(sql)
    def fetchone(self):return {'state':'ACTIVE',**self.db.live}


class DB:
    def __init__(self,live):self.live=live
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def cursor(self,**kwargs):return Cursor(self)
    def close(self):pass
    def set_session(self,**kwargs):pass


class PromotionServiceTest(PromotionFixture,unittest.TestCase):
    def setUp(self):
        self.setup_fixture();f=self.fixture
        self.db=DB(f.base)
        self.fence=SimpleNamespace(service_id='test-service',connection=self.db,assert_held=Mock())
        self.pointers=promotion.BaselinePointerStore(registry=self.registry,connect=lambda:self.db,service_id='test-service')
        self.manifest_path=f.root/'parser-manifest.json'
        self.deployment={'manifest_digest':'e'*64,'provenance':{'git_commit':f.commit},'jar_sha256':f.base['artifact_sha256'],
            'parser_digest':f.base['parser_digest'],'profile_digest':f.base['profile_digest']}
        self.manifest_path.write_text(json.dumps(self.deployment))
        self.verified=patch.object(promotion.current_manifest,'verify_deployment',side_effect=lambda *a,**k:deepcopy(self.deployment))
        self.verified.start();self.addCleanup(self.verified.stop)
        self.old_pointer=self.pointers.initialize(base_release='base',deployment_manifest=self.manifest_path,
            deployment_manifest_digest='e'*64,fence=self.fence)
        self.db.live=self.live
        @contextmanager
        def lock(_):yield self.fence
        self.journal=SimpleNamespace(service_lock=lock,load=Mock(return_value=self.record))
        self.host=SimpleNamespace(observe=Mock(return_value=self.live))
        self.provider=SimpleNamespace(workspace=f.repo,repository='example/dataops',read_snapshot=Mock(return_value=self.snapshot))
        self.service=promotion.BaselinePromotionService(connect=lambda:self.db,registry=self.registry,repository=f.repo,
            pointers=self.pointers,host=self.host,journal=self.journal,provider=self.provider,
            verifier=self.verifier,verify_recovery=lambda *_:f.recovery)
        self.service._review=Mock(return_value=(self.manifest,self.binding))
        def freeze(release,commit,build):
            self.deployment.update(provenance={'git_commit':commit},jar_sha256=build['artifact_sha256'])
            self.manifest_path.write_text(json.dumps(self.deployment))
            return self.manifest_path,'e'*64
        self.service._freeze=Mock(side_effect=freeze)
        self.probe=patch.object(full_registry,'probe_artifact',return_value=f.measure)
        self.probe.start();self.addCleanup(self.probe.stop)

    def test_active_candidate_switches_one_baseline_pointer_and_reuses_on_retry(self):
        with self.assertRaisesRegex(ApprovalHold,'BASELINE_PROMOTION_REQUIRED'):self.pointers.read()
        first=self.service.promote(self.manifest.digest)
        self.assertEqual('PROMOTED',first['outcome'],first)
        pointer=self.pointers.read()
        self.assertEqual('next',pointer['base_release'])
        self.assertEqual(self.review.head_sha,pointer['source_commit'])
        self.assertEqual(first,self.service.promote(self.manifest.digest))
        self.assertEqual(first,self.service.status(self.manifest.digest))

    def test_changed_runtime_and_unfinished_release_leave_old_pointer(self):
        self.host.observe.return_value={**self.live,'generation':'wrong'}
        self.assertEqual('HELD',self.service.promote(self.manifest.digest)['outcome'])
        self.assertEqual(self.old_pointer,self.pointers._stored())
        self.host.observe.return_value=self.live;self.record['state']='REPLAY'
        self.assertEqual('HELD',self.service.promote(self.manifest.digest)['outcome'])
        self.service._freeze.assert_not_called()

    def test_status_recovers_pointer_rename_without_new_compile_or_github_call(self):
        first=self.service.promote(self.manifest.digest)
        self.assertEqual('PROMOTED',first['outcome'],first)
        (self.registry.root/'baseline-promotion-results'/(self.manifest.digest+'.json')).unlink()
        count=self.provider.read_snapshot.call_count
        self.assertEqual(first,self.service.status(self.manifest.digest))
        self.assertEqual(count,self.provider.read_snapshot.call_count)
        self.service._freeze.assert_called_once()

    def test_cas_pointer_cannot_adopt_another_service_or_stale_generation(self):
        wrong=deepcopy(self.old_pointer);wrong['generation']='wrong'
        with self.assertRaises(ApprovalHold):
            self.pointers.activate(base_release='base',deployment_manifest=self.manifest_path,
                deployment_manifest_digest='e'*64,expected_previous=wrong,fence=self.fence)
        self.assertEqual(self.old_pointer,self.pointers._stored())


@unittest.skipUnless(os.getenv('RUN_BASELINE_PROMOTION_SANDBOX_TESTS')=='true','offline Docker committed source opt-in')
class PromotionSourceSandboxTest(unittest.TestCase):
    def test_exact_candidate_jar_is_frozen_from_merged_commit_without_rebuilding_service(self):
        root=Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory() as temporary:
            work=Path(temporary).resolve();repo=work/'source';repo.mkdir()
            def git(*args):return subprocess.run(['git','-C',str(repo),*args],capture_output=True,text=True,check=True).stdout.strip()
            git('init','-q','-b','main');git('config','user.name','Test');git('config','user.email','test@example.invalid')
            for path in current_manifest.ALL_PATHS:
                destination=repo/path;destination.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(root/path,destination)
            git('add','.');git('commit','-qm','baseline')
            runtime=json.loads((current_validator.RUNTIME/'manifest.json').read_text())
            profile=json.loads((repo/current_manifest.PROFILE_PATH).read_text())
            profile['entities'].append({'typename':'ControlledNextMenu','priceShape':'DISPLAY_TEXT_WON','imageShapes':['STRING','URL_OBJECT']})
            profile_text=json.dumps(profile,ensure_ascii=False,indent=2)+'\n'
            candidates={current_manifest.PARSER_PATH:(repo/current_manifest.PARSER_PATH).read_text(),current_manifest.PROFILE_PATH:profile_text}
            compiled=current_validator.compile_and_run(repo,candidates,[],work/'compile',runtime)
            self.assertEqual('OK',compiled['status'])
            artifact=work/'candidate.jar'
            current_validator._candidate_jar(Path(os.environ['AGENT_PROBE_TEST_JAR']),artifact,compiled['class_bytes'],profile_text.encode())
            (repo/current_manifest.PROFILE_PATH).write_text(profile_text)
            git('add',current_manifest.PROFILE_PATH);git('commit','-qm','approved merged alias')
            merge=git('rev-parse','HEAD')
            service=promotion.BaselinePromotionService.__new__(promotion.BaselinePromotionService)
            service.repository=repo;service.registry=full_registry.FullReleaseRegistry(work/'registry')
            path,digest=service._freeze('approved-next',merge,{'artifact_path':str(artifact),'artifact_sha256':full_registry.file_digest(artifact)})
            manifest=current_manifest.verify_deployment(path,expected_manifest_digest=digest,require_committed=True)
            self.assertEqual(merge,manifest['provenance']['git_commit'])
            self.assertEqual(full_registry.file_digest(artifact),manifest['jar_sha256'])
            self.assertEqual(compiled['parser_digest'],manifest['parser_digest'])
            self.assertEqual(merge,git('rev-parse','HEAD'))
            self.assertEqual(1,git('worktree','list','--porcelain').count('worktree '))


if __name__=='__main__':unittest.main()
