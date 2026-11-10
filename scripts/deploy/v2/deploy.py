"""v2 전용 시작 템플릿/Instance Refresh. main() 외에는 AWS 호출을 실행하지 않는다."""
import base64
import gzip
import json
import os
from pathlib import Path
import re
import time

ACCOUNT = '210447604031'
REGION = 'ap-northeast-2'
ASG = 'sweetgirl-was-auto-scaling'
TEMPLATE = 'lt-005c5f2006720a57c'
SUBNET = 'subnet-0078f9aaf3051fcd3'
WAS_SG = 'sg-00c0052096ed30815'
TARGET = f'arn:aws:elasticloadbalancing:{REGION}:{ACCOUNT}:targetgroup/tg-sweetgirl-prod-was-v2/25e6b890b7ec7571'
TERMINAL = {'Successful', 'Failed', 'Cancelled', 'RollbackSuccessful', 'RollbackFailed'}


def encode(value):
    return base64.b64encode(value).decode()


def user_data(image, parameter_prefix):
    if not re.fullmatch(r'(?:docker\.io/)?[a-z0-9][a-z0-9_-]*/[a-z0-9][a-z0-9._-]*@sha256:[0-9a-f]{64}', image):
        raise ValueError('Use a Docker Hub image pinned by SHA256 digest')
    if not re.fullmatch(r'/sweetgirl/prod/v2/was(?:/[A-Za-z0-9_-]+){0,8}', parameter_prefix):
        raise ValueError('Use a parameter prefix under /sweetgirl/prod/v2/was without a trailing slash')
    config = encode(json.dumps({'region': REGION, 'parameter_prefix': parameter_prefix, 'image': image}).encode())
    bootstrap = encode(gzip.compress(Path(__file__).with_name('bootstrap.py').read_bytes(), mtime=0))
    # Docker·boto3 가 AMI 에 이미 있으면 apt 설치(부팅당 60~90초)를 건너뛴다. 일반 Ubuntu AMI 에서는 지금처럼 설치하므로
    # 선설치 AMI 로 바꾸기 전에도, 바꾼 뒤 이전 템플릿 버전으로 롤백해도 그대로 뜬다(docs/v2-docker-ami-runbook.md).
    script = f'''#!/bin/bash
set -euo pipefail
umask 077
export DEBIAN_FRONTEND=noninteractive
if ! command -v docker >/dev/null || ! python3 -c 'import boto3' 2>/dev/null; then
  apt-get update -qq
  apt-get install -y docker.io python3-boto3
fi
systemctl enable --now docker
install -d -m 700 /opt/bbang-v2
printf '%s' '{config}' | base64 -d > /opt/bbang-v2/config.json
printf '%s' '{bootstrap}' | base64 -d | gzip -d > /opt/bbang-v2/bootstrap.py
cat > /etc/systemd/system/bbang-v2.service <<'UNIT'
[Unit]
Description=bbang v2 container bootstrap
Requires=docker.service
After=docker.service network-online.target
Wants=network-online.target
StartLimitIntervalSec=3600
StartLimitBurst=3
[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/bin/python3 /opt/bbang-v2/bootstrap.py
TimeoutStartSec=900
Restart=on-failure
RestartSec=30
UMask=0077
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now bbang-v2
'''.encode()
    if len(script) > 16384:
        raise ValueError('EC2 user data exceeds 16 KiB')
    return encode(script)


def preflight(asg, ec2, elb, sts):
    if sts.get_caller_identity()['Account'] != ACCOUNT:
        raise ValueError('Unexpected AWS account')
    group = asg.describe_auto_scaling_groups(AutoScalingGroupNames=[ASG])['AutoScalingGroups'][0]
    if group.get('MixedInstancesPolicy') or group.get('LaunchTemplate', {}).get('LaunchTemplateId') != TEMPLATE:
        raise ValueError('Unexpected launch template or mixed instances policy')
    if group['VPCZoneIdentifier'] != SUBNET or group['TargetGroupARNs'] != [TARGET]:
        raise ValueError('Unexpected v2 subnet/target group; no change made')
    if 'ELB' not in group['HealthCheckType'].split(','):
        raise ValueError('Enable ASG ELB health checks before deployment')
    if group['DesiredCapacity'] > 2 or group['MaxSize'] != 2:
        raise ValueError('Expected v2 desired capacity 0..2 and maximum 2')
    refreshes = asg.describe_instance_refreshes(AutoScalingGroupName=ASG, MaxRecords=10)['InstanceRefreshes']
    if any(r['Status'] not in TERMINAL for r in refreshes):
        raise ValueError('Another instance refresh is active; do not overlap deployments')
    if refreshes and refreshes[0]['Status'] == 'RollbackFailed':
        raise ValueError('Investigate the previous rollback failure first')
    version = ec2.describe_launch_template_versions(
        LaunchTemplateId=TEMPLATE, Versions=[group['LaunchTemplate']['Version']])['LaunchTemplateVersions'][0]
    data = version['LaunchTemplateData']
    if not data.get('IamInstanceProfile'):
        raise ValueError('Attach the EC2 runtime IAM instance profile first')
    metadata = data.get('MetadataOptions', {})
    if metadata.get('HttpTokens') != 'required' or metadata.get('HttpPutResponseHopLimit', 0) < 2:
        raise ValueError('Require IMDSv2 and response hop limit 2 for the Docker AWS SDK')
    if data.get('SecurityGroupIds') != [WAS_SG] or data.get('NetworkInterfaces'):
        raise ValueError('Expected WAS v2 SG with no launch-template network-interface overrides')
    if data.get('InstanceType') != 't4g.small' or not data.get('ImageId', '').startswith('ami-'):
        raise ValueError('Expected t4g.small and a fixed AMI ID')
    target = elb.describe_target_groups(TargetGroupArns=[TARGET])['TargetGroups'][0]
    if (target['Port'], target['Protocol'], target['HealthCheckPort'], target['HealthCheckPath']) != (
            8080, 'HTTP', '8081', '/actuator/health/readiness'):
        raise ValueError('Expected ALB 8080 and readiness on 8081')
    if group['DesiredCapacity']:
        instances = group['Instances']
        healthy_ids = {t['Target']['Id'] for t in elb.describe_target_health(TargetGroupArn=TARGET)['TargetHealthDescriptions']
                       if t['TargetHealth']['State'] == 'healthy'}
        if (len(instances) != group['DesiredCapacity'] or
                any(i['LifecycleState'] != 'InService' or i['HealthStatus'] != 'Healthy'
                    or i['InstanceId'] not in healthy_ids for i in instances)):
            raise ValueError('Wait for all existing v2 instances to be healthy before refreshing')
    elif group['Instances'] or group['MinSize'] != 0:
        raise ValueError('For a stopped group, wait for zero instances and set minimum 0')
    return group, version


def deploy(asg, ec2, elb, sts, image, parameter_prefix):
    payload = user_data(image, parameter_prefix)
    group, version = preflight(asg, ec2, elb, sts)
    old_version = str(version['VersionNumber'])
    # $Latest/$Default는 자동 롤백 대상이 될 수 없다. 같은 현재 버전의 숫자로 먼저 고정한다.
    if group['LaunchTemplate']['Version'] != old_version:
        asg.update_auto_scaling_group(AutoScalingGroupName=ASG,
                                     LaunchTemplate={'LaunchTemplateId': TEMPLATE, 'Version': old_version})
    new_version = ec2.create_launch_template_version(
        LaunchTemplateId=TEMPLATE, SourceVersion=old_version,
        VersionDescription='v2 Docker Hub digest deployment',
        LaunchTemplateData={'UserData': payload})['LaunchTemplateVersion']['VersionNumber']
    desired = {'LaunchTemplate': {'LaunchTemplateId': TEMPLATE, 'Version': str(new_version)}}
    print(f'Launch template: {old_version} -> {new_version}')
    if not group['DesiredCapacity']:
        # 최초 준비: 서버를 켜거나 min/desired/max를 바꾸지 않는다.
        asg.update_auto_scaling_group(AutoScalingGroupName=ASG, **desired)
        print('Prepared only: desired capacity remains 0; no live application was verified.')
        return None
    # 워밍업 45초. 실측(2026-11-09·11-10, 배포 세 번)에서 새 서버가 healthy 가 된 뒤 구 서버가 빠지기까지 37~95초가 걸렸고
    # 그 대기는 warmup 값(90·45)과 무관했다 — refresh 가 상태를 보는 주기로 보인다. 300초일 때만 그 값이 하한이 됐다(교체 305초).
    # 그래서 45초는 줄여 주는 게 없지만 해롭지도 않아 둔다. 교체 시간에서 우리가 손댈 수 있는 건 wait_for_refresh 의 조기 반환이다.
    result = asg.start_instance_refresh(
        AutoScalingGroupName=ASG, Strategy='Rolling', DesiredConfiguration=desired,
        Preferences={'MinHealthyPercentage': 100, 'MaxHealthyPercentage': 200,
                     'InstanceWarmup': 45, 'AutoRollback': True, 'SkipMatching': False,
                     'ScaleInProtectedInstances': 'Wait', 'StandbyInstances': 'Wait'})
    print(f"Instance refresh: {result['InstanceRefreshId']}")
    return result['InstanceRefreshId']


def wait_for_refresh(asg, refresh_id, timeout=7200):
    deadline = time.monotonic() + timeout
    previous = None
    while time.monotonic() < deadline:
        state = asg.describe_instance_refreshes(
            AutoScalingGroupName=ASG, InstanceRefreshIds=[refresh_id])['InstanceRefreshes'][0]
        if state['Status'] != previous:
            print(f"Refresh status: {state['Status']}", flush=True)
            previous = state['Status']
        if state['Status'] == 'Successful':
            return
        if state['Status'] in TERMINAL:
            raise RuntimeError(f"Refresh ended with {state['Status']}: {state.get('StatusReason', '')}")
        # 10초: 교체가 끝난 뒤 Successful 을 알아채기까지의 지연(평균 폴링 간격의 절반)을 줄인다. 호출은 무료다.
        time.sleep(10)
    # 파이프라인 중단만으로 AWS 작업은 취소되지 않는다. 상태를 확인하고 수동 롤백한다.
    raise TimeoutError('AWS refresh is still active; inspect ASG and roll back if needed')


def main():
    import boto3

    session = boto3.Session(region_name=REGION)
    asg = session.client('autoscaling')
    refresh_id = deploy(asg, session.client('ec2'), session.client('elbv2'), session.client('sts'),
                        os.environ['V2_IMAGE'], os.environ['V2_RUNTIME_PARAMETER_PREFIX'])
    if refresh_id:
        wait_for_refresh(asg, refresh_id)
    # 정상 반환 뒤에만 알린다. 용량 0의 준비 완료를 실제 서비스 교체와 구분한다.
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write('deployment_state=' + ('refreshed' if refresh_id else 'prepared') + '\n')


if __name__ == '__main__':
    main()
