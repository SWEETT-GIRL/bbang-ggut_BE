import base64
import copy
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from agent.response_change import job_installation as install
from agent.response_change.release import ReleaseHold
from agent.response_change.tests.test_job_runner import Fixture

ZONE={'timezone':'Asia/Seoul','zoneinfo_sha256':'a'*64}


class FakeLaunchctl:
    def __init__(self):self.states={};self.calls=[];self.crash=False;self.register_before_crash=False;self.timeout=False
    def observe(self,job,uid):return self.states.get(job['label'],'ABSENT')
    def bootstrap(self,job,uid):
        self.calls.append((job['label'],uid))
        if not self.crash or self.register_before_crash:self.states[job['label']]='LOADED'
        if self.crash:raise SystemExit('injected bootstrap process death')
        if self.timeout:raise subprocess.TimeoutExpired('launchctl',15)
        return Mock(returncode=0)


class JobInstallationTest(Fixture,unittest.TestCase):
    def setUp(self):
        self.initialize();self.driver=FakeLaunchctl();self.launch=self.root/'LaunchAgents'
        self.configs=[self.config(kind)[1] for kind in ('collection','analysis')]
        smoke=patch.object(install,'interpreter_smoke',return_value={'kind':'FIXTURE_ONLY'})
        self.smoke=smoke.start();self.addCleanup(smoke.stop)

    def prepare(self):
        self.prepared=install.prepare_plan(service_id='service',installation_receipt=self.receipt,configurations=self.configs,
            python=Path(sys.executable),state_directory=self.root/'installer',launch_agents_directory=self.launch,timezone_reader=lambda:ZONE)
        self.plan=json.loads(Path(self.prepared['plan_path']).read_text())
        return self.prepared

    def apply(self):return install.install_plan(self.prepared['plan_path'],self.prepared['plan_digest'],driver=self.driver,timezone_reader=lambda:ZONE)

    def test_prepare_is_reviewable_calendar_bound_and_does_not_install(self):
        self.prepare();self.assertFalse(self.launch.exists());self.assertEqual([],self.driver.calls)
        for job in self.plan['jobs']:
            value=plistlib.loads(base64.b64decode(job['plist_base64']))
            self.assertEqual('org.bbanggut.dataops.service.job.'+job['job_id'],value['Label'])
            self.assertEqual(str(Path(sys.executable)),value['ProgramArguments'][0])
            self.assertIn('--configuration-digest',value['ProgramArguments']);self.assertIn('--installation-receipt-digest',value['ProgramArguments'])
            self.assertFalse(any('KEY' in key or 'DSN' in key for key in value['EnvironmentVariables']))
            if job['kind']=='collection':
                self.assertFalse(value['RunAtLoad']);self.assertEqual({'Hour':9,'Minute':0},value['StartCalendarInterval'])
                self.assertNotIn('StartInterval',value)
            else:self.assertTrue(value['RunAtLoad']);self.assertEqual(60,value['StartInterval'])

    def test_install_and_repeat_require_exact_owned_bytes_and_never_bootout(self):
        self.prepare();self.launch.mkdir(mode=0o700)
        unrelated=self.launch/'com.other.job.plist';unrelated.write_text('leave me alone')
        result=self.apply();self.assertEqual('INSTALLED',result['state']);self.assertEqual(2,len(self.driver.calls))
        again=self.apply();self.assertEqual('INSTALLED',again['state']);self.assertEqual(2,len(self.driver.calls))
        self.assertEqual('leave me alone',unrelated.read_text())
        for job in self.plan['jobs']:
            self.assertEqual(base64.b64decode(job['plist_base64']),Path(job['plist_path']).read_bytes())
            self.assertTrue((Path(job['state_directory'])/'launchd.stdout.log').exists())

    def test_crash_after_registration_is_settled_from_authoritative_print_without_bootstrap(self):
        self.configs=self.configs[:1];self.prepare();self.driver.crash=True;self.driver.register_before_crash=True
        with self.assertRaises(SystemExit):self.apply()
        self.driver.crash=False
        self.assertEqual('INSTALLED',self.apply()['state']);self.assertEqual(1,len(self.driver.calls))

    def test_crash_before_proven_registration_stays_unknown_and_never_retries(self):
        self.configs=self.configs[:1];self.prepare();self.driver.crash=True
        with self.assertRaises(SystemExit):self.apply()
        self.driver.crash=False
        self.assertEqual('UNKNOWN',self.apply()['state']);self.assertEqual('UNKNOWN',self.apply()['state'])
        self.assertEqual(1,len(self.driver.calls))

    def test_lost_bootstrap_response_with_loaded_proof_is_successful_registration(self):
        self.prepare();self.driver.timeout=True
        self.assertEqual('INSTALLED',self.apply()['state']);self.assertEqual(2,len(self.driver.calls))

    def test_existing_unowned_label_or_plist_is_never_adopted_or_replaced(self):
        self.prepare();first=self.plan['jobs'][0]
        self.driver.states[first['label']]='LOADED'
        with self.assertRaisesRegex(ReleaseHold,'FOREIGN_LAUNCHD_JOB'):self.apply()
        self.assertEqual([],self.driver.calls)
        self.driver.states.clear();destination=Path(first['plist_path']);destination.write_bytes(base64.b64decode(first['plist_base64']));destination.chmod(0o600)
        with self.assertRaisesRegex(ReleaseHold,'FOREIGN_LAUNCHD_JOB'):self.apply()
        self.assertEqual([],self.driver.calls)

    def test_modified_owned_definition_is_held_before_launchctl_bootstrap(self):
        self.prepare();self.apply();first=self.plan['jobs'][0]
        Path(first['plist_path']).write_text('different owner bytes')
        with self.assertRaisesRegex(ReleaseHold,'FOREIGN_LAUNCHD_DEFINITION'):self.apply()
        self.assertEqual(2,len(self.driver.calls))

    def test_configuration_or_source_drift_invalidates_frozen_plan(self):
        self.prepare();value=json.loads(self.configs[0].read_text());value['settings']['max_stores']=99
        self.write(self.configs[0],value)
        with self.assertRaisesRegex(ReleaseHold,'FROZEN_JOB_CONFIGURATION_CHANGED'):self.apply()
        self.assertEqual([],self.driver.calls)

    def test_timezone_change_fails_before_installation(self):
        self.prepare()
        with self.assertRaisesRegex(ReleaseHold,'SYSTEM_TIMEZONE_CHANGED'):
            install.install_plan(self.prepared['plan_path'],self.prepared['plan_digest'],driver=self.driver,
                timezone_reader=lambda:{'timezone':'UTC','zoneinfo_sha256':'b'*64})
        self.assertFalse(self.launch.exists())

    def test_missing_dependencies_or_changed_import_proof_prevents_installation(self):
        self.smoke.side_effect=ReleaseHold('INSTALLED_PYTHON_DEPENDENCIES_UNAVAILABLE')
        with self.assertRaisesRegex(ReleaseHold,'DEPENDENCIES_UNAVAILABLE'):self.prepare()
        self.assertFalse(self.launch.exists())
        self.smoke.side_effect=None;self.prepare();self.smoke.return_value={'kind':'CHANGED'}
        with self.assertRaisesRegex(ReleaseHold,'IMPORT_PROOF_CHANGED'):self.apply()
        self.assertFalse(self.launch.exists());self.assertEqual([],self.driver.calls)

    def test_symlink_launchd_log_cannot_overwrite_another_file(self):
        self.configs=self.configs[:1];self.prepare();job=self.plan['jobs'][0]
        state=Path(job['state_directory']);state.mkdir(parents=True,mode=0o700)
        target=self.root/'unrelated';target.write_text('unchanged');target.chmod(0o600)
        (state/'launchd.stdout.log').symlink_to(target)
        with self.assertRaises(OSError):self.apply()
        self.assertEqual('unchanged',target.read_text());self.assertEqual([],self.driver.calls)

    def test_launchctl_observer_checks_registration_path_program_and_exact_arguments(self):
        self.prepare();job=self.plan['jobs'][0];value=plistlib.loads(base64.b64decode(job['plist_base64']))
        text='gui/501/'+job['label']+' = {\n path = '+job['plist_path']+'\n program = '+value['ProgramArguments'][0]+'\n arguments = {\n'
        text+='\n'.join('  '+arg for arg in value['ProgramArguments'])+'\n }\n}\n'
        driver=install.Launchctl();driver.run=Mock(return_value=Mock(returncode=0,stdout=text.encode(),stderr=b''))
        self.assertEqual('LOADED',driver.observe(job,501))
        driver.run.return_value.stdout=text.replace('--live','--arbitrary').encode()
        self.assertEqual('FOREIGN',driver.observe(job,501))
        driver.run.return_value=Mock(returncode=113,stdout=b'',stderr=b'Could not find service')
        self.assertEqual('ABSENT',driver.observe(job,501))
        driver.run.return_value=Mock(returncode=1,stdout=b'',stderr=b'permission denied')
        self.assertEqual('UNKNOWN',driver.observe(job,501))


class InterpreterSmokeTest(unittest.TestCase):
    def test_real_lexical_venv_imports_service_modules_with_no_secrets_or_source_writes(self):
        root=Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory() as temporary:
            state=Path(temporary).resolve();state.chmod(0o700)
            with patch.dict(os.environ,{'ANTHROPIC_API_KEY':'must-not-inherit','AGENT_ORCHESTRATION_DSN':'must-not-inherit'}):
                proof=install.interpreter_smoke(Path(sys.executable),root,state)
            self.assertEqual(str(Path(sys.executable)),proof['python'])
            self.assertEqual('FROZEN_IMPORT_SMOKE_V1',proof['kind'])
            self.assertEqual(list(install.SMOKE_MODULES),proof['modules'])

    def test_probe_subprocess_is_bounded_uses_lexical_python_and_drops_parent_credentials(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary).resolve();root.chmod(0o700);python=root/'venv/bin/python'
            with patch.object(install.subprocess,'run',return_value=Mock(returncode=1,stdout=b'',stderr=b'missing dependency')) as run:
                with self.assertRaisesRegex(ReleaseHold,'DEPENDENCIES_UNAVAILABLE'):install.interpreter_smoke(python,root,root)
            self.assertEqual(str(python),run.call_args.args[0][0]);self.assertEqual(45,run.call_args.kwargs['timeout'])
            self.assertFalse(any('KEY' in key or 'DSN' in key or key=='PYTHONPATH' for key in run.call_args.kwargs['env']))


if __name__=='__main__':unittest.main()
