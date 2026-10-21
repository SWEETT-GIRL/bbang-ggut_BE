import base64
import copy
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bootstrap
import deploy

IMAGE = 'example/bbang-ggut-be@sha256:' + 'a' * 64
SECRET = f'arn:aws:secretsmanager:{deploy.REGION}:{deploy.ACCOUNT}:secret:v2/runtime-abcdef'


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
        return deploy.deploy(self.asg, self.ec2, self.elb, self.sts, IMAGE, SECRET)

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
        raw = base64.b64decode(deploy.user_data(IMAGE, SECRET))
        self.assertLessEqual(len(raw), 16384)
        subprocess.run(['bash', '-n'], input=raw, check=True)
        self.assertNotIn(b'DB_PASSWORD=', raw)
        self.assertNotIn(b'private_key', raw)

    def test_mutable_image_or_injected_arn_rejected(self):
        for image, secret in [(IMAGE.split('@')[0] + ':latest', SECRET), (IMAGE, SECRET + "'; echo bad")]:
            with self.subTest(image=image):
                with self.assertRaises(ValueError):
                    deploy.user_data(image, secret)

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


if __name__ == '__main__':
    unittest.main()
