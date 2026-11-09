import base64
import copy
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bootstrap
import deploy

IMAGE = 'example/bbang-ggut-be@sha256:' + 'a' * 64
PREFIX = '/sweetgirl/prod/v2/was'


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.asg, self.ec2, self.elb, self.sts = (Mock() for _ in range(4))
        self.sts.get_caller_identity.return_value = {'Account': deploy.ACCOUNT}
        self.group = {
            'LaunchTemplate': {'LaunchTemplateId': deploy.TEMPLATE, 'Version': '$Latest'},
            'VPCZoneIdentifier': deploy.SUBNET, 'TargetGroupARNs': [deploy.TARGET],
            'HealthCheckType': 'EC2,ELB', 'DesiredCapacity': 0, 'MinSize': 0, 'MaxSize': 2, 'Instances': [],
        }
        self.asg.describe_auto_scaling_groups.return_value = {'AutoScalingGroups': [self.group]}
        self.asg.describe_instance_refreshes.return_value = {'InstanceRefreshes': []}
        self.data = {
            'IamInstanceProfile': {'Name': 'v2-runtime'},
            'MetadataOptions': {'HttpTokens': 'required', 'HttpPutResponseHopLimit': 2},
            'SecurityGroupIds': [deploy.WAS_SG], 'InstanceType': 't4g.small', 'ImageId': 'ami-example',
        }
        self.ec2.describe_launch_template_versions.return_value = {
            'LaunchTemplateVersions': [{'VersionNumber': 3, 'LaunchTemplateData': self.data}]}
        self.ec2.create_launch_template_version.return_value = {'LaunchTemplateVersion': {'VersionNumber': 4}}
        self.elb.describe_target_groups.return_value = {'TargetGroups': [{
            'Port': 8080, 'Protocol': 'HTTP', 'HealthCheckPort': '8081',
            'HealthCheckPath': '/actuator/health/readiness'}]}
        self.asg.start_instance_refresh.return_value = {'InstanceRefreshId': 'refresh-id'}

    def invoke(self):
        return deploy.deploy(self.asg, self.ec2, self.elb, self.sts, IMAGE, PREFIX)

    def running(self):
        self.group['DesiredCapacity'] = 1
        self.group['Instances'] = [{'InstanceId': 'i-v2', 'LifecycleState': 'InService', 'HealthStatus': 'Healthy'}]
        self.elb.describe_target_health.return_value = {'TargetHealthDescriptions': [
            {'Target': {'Id': 'i-v2'}, 'TargetHealth': {'State': 'healthy'}}]}

    def test_zero_capacity_prepares_version_without_starting_servers(self):
        self.assertIsNone(self.invoke())
        self.asg.start_instance_refresh.assert_not_called()
        self.assertEqual(self.ec2.create_launch_template_version.call_args.kwargs['SourceVersion'], '3')
        for call in self.asg.update_auto_scaling_group.call_args_list:
            self.assertEqual(set(call.kwargs), {'AutoScalingGroupName', 'LaunchTemplate'})
        self.assertEqual(self.asg.update_auto_scaling_group.call_args.kwargs['LaunchTemplate']['Version'], '4')

    def test_running_refresh_keeps_old_config_for_rollback(self):
        self.running()
        self.assertEqual(self.invoke(), 'refresh-id')
        self.asg.update_auto_scaling_group.assert_called_once_with(
            AutoScalingGroupName=deploy.ASG, LaunchTemplate={'LaunchTemplateId': deploy.TEMPLATE, 'Version': '3'})
        request = self.asg.start_instance_refresh.call_args.kwargs
        self.assertEqual(request['DesiredConfiguration']['LaunchTemplate']['Version'], '4')
        self.assertTrue(request['Preferences']['AutoRollback'])
        self.assertEqual(request['Preferences']['MinHealthyPercentage'], 100)
        self.assertEqual(request['Preferences']['MaxHealthyPercentage'], 200)
        self.assertEqual(request['Preferences']['InstanceWarmup'], 90)

    def test_wrong_account_or_network_never_mutates(self):
        for key, bad in [('VPCZoneIdentifier', 'v1-subnet'), ('TargetGroupARNs', ['v1-target']),
                         ('HealthCheckType', 'EC2'), ('MaxSize', 3)]:
            with self.subTest(key=key):
                original = self.group[key]
                self.group[key] = bad
                with self.assertRaises(ValueError):
                    self.invoke()
                self.group[key] = original
        self.sts.get_caller_identity.return_value = {'Account': 'other-account'}
        with self.assertRaises(ValueError):
            self.invoke()
        self.ec2.create_launch_template_version.assert_not_called()
        self.asg.update_auto_scaling_group.assert_not_called()

    def test_missing_runtime_role_or_container_metadata_fails_before_changes(self):
        for key, bad in [('IamInstanceProfile', {}), ('MetadataOptions', {'HttpTokens': 'optional'}),
                         ('SecurityGroupIds', ['v1-sg']), ('InstanceType', 't3.small')]:
            with self.subTest(key=key):
                original = self.data[key]
                self.data[key] = bad
                with self.assertRaises(ValueError):
                    self.invoke()
                self.data[key] = original
        self.ec2.create_launch_template_version.assert_not_called()

    def test_unhealthy_existing_instance_blocks_refresh(self):
        self.running()
        self.elb.describe_target_health.return_value['TargetHealthDescriptions'][0]['TargetHealth']['State'] = 'unhealthy'
        with self.assertRaises(ValueError):
            self.invoke()
        self.ec2.create_launch_template_version.assert_not_called()

    def test_active_refresh_blocks_new_deployment(self):
        for status in ('Pending', 'InProgress', 'Baking', 'RollbackInProgress', 'RollbackFailed'):
            with self.subTest(status=status):
                self.asg.describe_instance_refreshes.return_value = {'InstanceRefreshes': [{'Status': status}]}
                with self.assertRaises(ValueError):
                    self.invoke()
        self.ec2.create_launch_template_version.assert_not_called()

    def test_numeric_source_is_not_changed_before_refresh(self):
        self.running()
        self.group['LaunchTemplate']['Version'] = '3'
        self.invoke()
        self.asg.update_auto_scaling_group.assert_not_called()

    def test_user_data_is_valid_bash_under_limit_and_contains_no_runtime_values(self):
        raw = base64.b64decode(deploy.user_data(IMAGE, PREFIX))
        self.assertLessEqual(len(raw), 16384)
        subprocess.run(['bash', '-n'], input=raw, check=True)
        self.assertNotIn(b'DB_PASSWORD=', raw)
        self.assertNotIn(b'private_key', raw)

    def test_user_data_installs_docker_and_boto3_only_when_the_ami_lacks_them(self):
        raw = base64.b64decode(deploy.user_data(IMAGE, PREFIX))
        # apt 설치는 조건 블록 안에 한 번만 있다. 선설치 AMI 에서는 건너뛰고, 일반 Ubuntu AMI 에서는 지금처럼 설치한다.
        self.assertEqual(raw.count(b'apt-get install'), 1)
        guard = re.search(rb"^if ! command -v docker >/dev/null \|\| ! python3 -c 'import boto3' 2>/dev/null; then\n"
                          rb"(?:  .+\n)+?fi\n", raw, re.M)
        self.assertIsNotNone(guard)
        self.assertIn(b'apt-get install -y docker.io python3-boto3', guard.group(0))
        self.assertLessEqual(guard.end(), raw.index(b'systemctl enable --now docker'))
        # 블록만 떼어 PATH 에 스텁만 둔 채 실행해 본다. docker 가 없거나 boto3 를 못 불러오면 설치하고, 둘 다 있으면 apt 를 부르지 않는다.
        for docker_installed, boto3_importable, expect_install in ((True, True, False), (False, True, True), (True, False, True)):
            with self.subTest(docker=docker_installed, boto3=boto3_importable), tempfile.TemporaryDirectory() as directory:
                stubs = Path(directory)
                if docker_installed:
                    (stubs / 'docker').write_text('#!/bin/sh\nexit 0\n')
                (stubs / 'python3').write_text(f'#!/bin/sh\nexit {0 if boto3_importable else 1}\n')
                (stubs / 'apt-get').write_text('#!/bin/sh\necho "$@" >> "$APT_LOG"\n')
                for stub in stubs.iterdir():
                    stub.chmod(0o755)
                log = stubs / 'apt.log'
                subprocess.run(['/bin/bash', '-c', guard.group(0)], check=True,
                               env={'PATH': str(stubs), 'APT_LOG': str(log)})
                self.assertEqual(log.exists(), expect_install)
                if expect_install:
                    self.assertEqual(log.read_text(), 'update -qq\ninstall -y docker.io python3-boto3\n')

    def test_mutable_image_or_injected_prefix_rejected(self):
        for image, secret in [(IMAGE.split('@')[0] + ':latest', PREFIX), (IMAGE, PREFIX + "'; echo bad")]:
            with self.subTest(image=image):
                with self.assertRaises(ValueError):
                    deploy.user_data(image, secret)

    def test_wrong_parameter_namespace_is_rejected_before_aws_changes(self):
        for prefix in ('/sweetgirl/prod/v1/was', PREFIX + '/', PREFIX + '/../v1', 'other'):
            with self.subTest(prefix=prefix), self.assertRaises(ValueError):
                deploy.deploy(self.asg, self.ec2, self.elb, self.sts, IMAGE, prefix)
        self.asg.describe_auto_scaling_groups.assert_not_called()

    def test_rollback_is_failure_not_success(self):
        self.asg.describe_instance_refreshes.return_value = {'InstanceRefreshes': [{'Status': 'RollbackSuccessful'}]}
        with self.assertRaisesRegex(RuntimeError, 'RollbackSuccessful'):
            deploy.wait_for_refresh(self.asg, 'refresh-id')

    @patch('deploy.time.sleep')
    def test_refresh_waits_for_success(self, sleep):
        self.asg.describe_instance_refreshes.side_effect = [
            {'InstanceRefreshes': [{'Status': 'InProgress'}]},
            {'InstanceRefreshes': [{'Status': 'Successful'}]}]
        deploy.wait_for_refresh(self.asg, 'refresh-id')
        sleep.assert_called_once_with(30)


class DeploymentOutputTest(unittest.TestCase):
    def test_only_verified_terminal_results_are_exposed_to_notifications(self):
        for refresh_id, failure, expected in ((None, False, 'prepared'), ('refresh-id', False, 'refreshed'),
                                              ('refresh-id', True, None)):
            with self.subTest(refresh_id=refresh_id, failure=failure), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'output'
                sdk = Mock()
                with patch.dict(sys.modules, {'boto3': sdk}), \
                        patch.dict('os.environ', {'V2_IMAGE': IMAGE, 'V2_RUNTIME_PARAMETER_PREFIX': PREFIX,
                                                 'GITHUB_OUTPUT': str(output)}), \
                        patch.object(deploy, 'deploy', return_value=refresh_id), \
                        patch.object(deploy, 'wait_for_refresh', side_effect=RuntimeError('rollback') if failure else None) as wait:
                    if failure:
                        with self.assertRaises(RuntimeError): deploy.main()
                        self.assertFalse(output.exists())
                    else:
                        deploy.main()
                        self.assertEqual(output.read_text(), 'deployment_state=' + expected + '\n')
                    if refresh_id: wait.assert_called_once()
                    else: wait.assert_not_called()


class SecretTest(unittest.TestCase):
    def setUp(self):
        self.secret = {
            'environment': {key: 'test-value' for key in bootstrap.REQUIRED},
            'firebase_service_account': {'project_id': 'test', 'private_key': 'multiline\nkey', 'client_email': 'test@example.com'},
            'dockerhub': {'username': 'test-user', 'token': 'test-token'},
        }

    def test_multiline_firebase_json_is_accepted_outside_env_file(self):
        env, firebase, _ = bootstrap.validate_secret(self.secret)
        self.assertEqual(set(env), bootstrap.REQUIRED)
        self.assertIn('\n', firebase['private_key'])

    def test_env_injection_or_config_overrides_are_rejected(self):
        for key, value in [('DB_PASSWORD', 'password\nSPRING_FLYWAY_ENABLED=true'),
                           ('SPRING_APPLICATION_JSON', '{}'), ('DB_PASSWORD', '')]:
            secret = copy.deepcopy(self.secret)
            secret['environment'][key] = value
            with self.assertRaises(ValueError):
                bootstrap.validate_secret(secret)

    def test_missing_admin_key_fails_closed(self):
        del self.secret['environment']['ADMIN_API_KEY']
        with self.assertRaises(ValueError):
            bootstrap.validate_secret(self.secret)


class ParameterStoreTest(unittest.TestCase):
    def setUp(self):
        SecretTest.setUp(self)
        self.values = {f'{PREFIX}/env/{key}': value
                       for key, value in self.secret['environment'].items()}
        self.values[f'{PREFIX}/firebase-service-account'] = json.dumps(self.secret['firebase_service_account'])
        self.values.update({f'{PREFIX}/dockerhub/{key}': value
                            for key, value in self.secret['dockerhub'].items()})
        self.ssm = Mock()
        self.ssm.get_parameters.side_effect = self.respond

    def respond(self, Names, WithDecryption):
        self.assertTrue(WithDecryption)
        self.assertLessEqual(len(Names), 10)
        return {'Parameters': [{'Name': name, 'Type': 'SecureString', 'Value': self.values[name]}
                               for name in reversed(Names) if name in self.values],
                'InvalidParameters': [name for name in Names if name not in self.values]}

    def test_decrypted_batches_are_mapped_by_name_with_optional_parameters_absent(self):
        self.assertEqual(bootstrap.load_parameters(self.ssm, PREFIX),
                         bootstrap.validate_secret(self.secret))
        # 환경 변수 이름 + firebase + dockerhub 2개를 10개씩 묶어 부른다
        names = len(bootstrap.REQUIRED | bootstrap.OPTIONAL) + 3
        self.assertEqual(self.ssm.get_parameters.call_count, -(-names // 10))
        self.ssm.get_parameters_by_path.assert_not_called()

    def test_draft_redis_parameters_reach_the_app_when_present(self):
        self.values[f'{PREFIX}/env/COURSE_DRAFT_REDIS_MODE'] = 'standalone'
        self.values[f'{PREFIX}/env/COURSE_DRAFT_REDIS_PORT'] = '6380'
        env, _, _ = bootstrap.load_parameters(self.ssm, PREFIX)
        self.assertEqual(env['COURSE_DRAFT_REDIS_MODE'], 'standalone')
        self.assertEqual(env['COURSE_DRAFT_REDIS_PORT'], '6380')

    def test_cache_redis_sentinel_parameters_reach_the_app_when_present(self):
        nodes = '10.0.0.1:26380,10.0.0.2:26380,10.0.0.3:26380'
        self.values[f'{PREFIX}/env/SPRING_DATA_REDIS_SENTINEL_MASTER'] = 'cache'
        self.values[f'{PREFIX}/env/SPRING_DATA_REDIS_SENTINEL_NODES'] = nodes
        env, _, _ = bootstrap.load_parameters(self.ssm, PREFIX)
        self.assertEqual(env['SPRING_DATA_REDIS_SENTINEL_MASTER'], 'cache')
        self.assertEqual(env['SPRING_DATA_REDIS_SENTINEL_NODES'], nodes)

    def test_cache_redis_stays_off_when_parameters_are_absent(self):
        env, _, _ = bootstrap.load_parameters(self.ssm, PREFIX)
        self.assertNotIn('SPRING_DATA_REDIS_SENTINEL_MASTER', env)
        self.assertNotIn('REDIS_HOST', env)

    def test_google_places_key_is_not_needed_to_load_runtime_parameters(self):
        google_parameter = f'{PREFIX}/env/GOOGLE_PLACES_API_KEY'
        self.values.pop(google_parameter, None)
        env, firebase, dockerhub = bootstrap.load_parameters(self.ssm, PREFIX)
        self.assertEqual(env['DB_PASSWORD'], 'test-value')
        self.assertEqual(firebase['project_id'], 'test')
        self.assertEqual(dockerhub['username'], 'test-user')
        self.assertNotIn('GOOGLE_PLACES_API_KEY', env)
        for call in self.ssm.get_parameters.call_args_list:
            self.assertNotIn(google_parameter, call.kwargs['Names'])

    def test_optional_parameter_is_loaded_when_present(self):
        self.values[f'{PREFIX}/env/OPENAI_API_KEY'] = 'optional-test'
        env, _, _ = bootstrap.load_parameters(self.ssm, PREFIX)
        self.assertEqual(env['OPENAI_API_KEY'], 'optional-test')

    def test_server_error_alert_credentials_are_loaded_only_from_explicit_parameters(self):
        self.values[f'{PREFIX}/env/SLACK_ERROR_WEBHOOK_URL'] = 'https://hooks.slack.com/services/test-only'
        self.values[f'{PREFIX}/env/OPS_SERVER_ERROR_ALERT_ENABLED'] = 'true'
        env, _, _ = bootstrap.load_parameters(self.ssm, PREFIX)
        self.assertEqual(env['OPS_SERVER_ERROR_ALERT_ENABLED'], 'true')
        self.assertEqual(env['SLACK_ERROR_WEBHOOK_URL'], 'https://hooks.slack.com/services/test-only')
        self.ssm.get_parameters_by_path.assert_not_called()

    def test_enabling_error_alerts_without_a_receiver_is_rejected(self):
        self.values[f'{PREFIX}/env/OPS_SERVER_ERROR_ALERT_ENABLED'] = 'true'
        with self.assertRaisesRegex(ValueError, 'require SLACK_ERROR_WEBHOOK_URL'):
            bootstrap.load_parameters(self.ssm, PREFIX)

    def test_alert_switch_typo_is_rejected(self):
        self.values[f'{PREFIX}/env/OPS_SERVER_ERROR_ALERT_ENABLED'] = 'treu'
        with self.assertRaisesRegex(ValueError, 'must be true or false'):
            bootstrap.load_parameters(self.ssm, PREFIX)

    def test_missing_required_value_fails_without_disclosing_other_values(self):
        del self.values[f'{PREFIX}/env/DB_PASSWORD']
        with self.assertRaisesRegex(ValueError, 'Missing runtime parameters') as error:
            bootstrap.load_parameters(self.ssm, PREFIX)
        self.assertNotIn('test-token', str(error.exception))

    def test_plaintext_parameter_is_rejected(self):
        self.ssm.get_parameters.side_effect = None
        self.ssm.get_parameters.return_value = {'Parameters': [
            {'Name': f'{PREFIX}/env/ADMIN_API_KEY', 'Type': 'String', 'Value': 'secret-value'}]}
        with self.assertRaisesRegex(ValueError, 'SecureString') as error:
            bootstrap.load_parameters(self.ssm, PREFIX)
        self.assertNotIn('secret-value', str(error.exception))

    def test_firebase_json_and_standard_utf8_size_limits(self):
        for value in ('not-json-secret', json.dumps([]), '가' * 1366):
            with self.subTest(length=len(value)), self.assertRaises(ValueError) as error:
                self.values[f'{PREFIX}/firebase-service-account'] = value
                bootstrap.load_parameters(self.ssm, PREFIX)
            self.assertNotIn(value, str(error.exception))

    def test_failed_parameter_access_stops_before_docker_commands(self):
        sdk = Mock()
        sdk.client.return_value.get_parameters.side_effect = RuntimeError('AccessDenied')
        config = json.dumps({'region': deploy.REGION, 'parameter_prefix': PREFIX, 'image': IMAGE})
        with patch.dict(sys.modules, {'boto3': sdk}), patch('bootstrap.Path.read_text', return_value=config), \
                patch('bootstrap.os.umask'), patch('bootstrap.run') as run:
            with self.assertRaisesRegex(RuntimeError, 'AccessDenied'):
                bootstrap.main()
            sdk.client.assert_called_once_with('ssm', region_name=deploy.REGION)
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
