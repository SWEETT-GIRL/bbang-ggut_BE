"""배치 EC2 배포(GitHub Actions 에서 실행). main() 외에는 AWS 호출을 하지 않는다.

1. 예약 작업(Scheduler → Step Functions sweetgirl-batch-lifecycle)과 같은 DynamoDB 락을 잡는다 — 작업이 도는 중이면 끝날 때까지 기다린다.
2. 인스턴스가 꺼져 있으면 켜고, SSM Run Command 로 runner.py(bbang-batch)와 config.json(이미지 digest·파라미터 경로)을 설치한 뒤 `bbang-batch install` 로 이미지를 받는다.
3. 이 배포가 켠 인스턴스면 다시 끄고, 꺼진 걸 확인한 뒤 락을 놓는다.
"""
import base64
from datetime import datetime, timezone
import gzip
import json
import os
from pathlib import Path
import re
import time

REGION = 'ap-northeast-2'
INSTANCE = re.compile(r'i-[0-9a-f]{8,17}')
IMAGE = re.compile(r'(?:docker\.io/)?[a-z0-9][a-z0-9_-]*/[a-z0-9][a-z0-9._-]*@sha256:[0-9a-f]{64}')
PREFIX = re.compile(r'/sweetgirl/prod/v2/batch(?:/[A-Za-z0-9_-]+){0,8}')
LOCK_TABLE = 'sweetgirl-batch-execution-lock'
# 배포 락 주인 접두어 — lambda-sweetgirl-batch-stop 이 이 접두어를 보고 복구 대상에서 뺀다
DEPLOY_OWNER_PREFIX = 'github-deploy:'
TERMINAL = {'Success', 'Cancelled', 'TimedOut', 'Failed', 'Cancelling', 'Undeliverable', 'Terminated'}


def encode(value):
    return base64.b64encode(value).decode()


def validate(instance_id, image, prefix):
    if not INSTANCE.fullmatch(instance_id or ''):
        raise ValueError('Invalid instance id')
    if not IMAGE.fullmatch(image or ''):
        raise ValueError('Use a Docker Hub image pinned by SHA256 digest')
    if not PREFIX.fullmatch(prefix or ''):
        raise ValueError('Use a parameter prefix under /sweetgirl/prod/v2/batch without a trailing slash')


def install_script(image, prefix):
    """SSM AWS-RunShellScript 로 보낼 셸. 비밀값은 담지 않는다 — 이미지 주소·파라미터 경로·runner 코드뿐"""
    config = encode(json.dumps({'region': REGION, 'parameter_prefix': prefix, 'image': image}).encode())
    runner = encode(gzip.compress(Path(__file__).with_name('runner.py').read_bytes(), mtime=0))
    notifications = encode(gzip.compress(Path(__file__).with_name('batch_notifications.py').read_bytes(), mtime=0))
    return f'''#!/bin/bash
set -euo pipefail
umask 077
export DEBIAN_FRONTEND=noninteractive
if ! command -v docker >/dev/null || ! python3 -c 'import boto3' 2>/dev/null; then
  apt-get update -qq
  apt-get install -y docker.io python3-boto3
fi
systemctl enable --now docker
install -d -m 700 /opt/bbang-batch
# 다음 설정은 .next 로 둔다 — bbang-batch install 이 pull 에 성공해야 config.json 이 된다(실패해도 작업은 현재 이미지로)
printf '%s' '{config}' | base64 -d > /opt/bbang-batch/config.json.next
printf '%s' '{runner}' | base64 -d | gzip -d > /opt/bbang-batch/runner.py
printf '%s' '{notifications}' | base64 -d | gzip -d > /opt/bbang-batch/batch_notifications.py
cat > /usr/local/bin/bbang-batch <<'WRAPPER'
#!/bin/bash
exec /usr/bin/python3 /opt/bbang-batch/runner.py "$@"
WRAPPER
chmod 755 /usr/local/bin/bbang-batch
bbang-batch install
'''


def wait_command(ssm, command_id, instance_id, timeout_seconds):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        time.sleep(10)
        try:
            result = ssm.get_command_invocation(CommandId=command_id, InstanceId=instance_id)
        except ssm.exceptions.InvocationDoesNotExist:
            continue
        if result['Status'] in TERMINAL:
            return result
    raise TimeoutError('SSM command did not finish in time')


def send(ssm, instance_id, script, timeout_seconds):
    command = ssm.send_command(InstanceIds=[instance_id], DocumentName='AWS-RunShellScript',
                               Parameters={'commands': [script]}, TimeoutSeconds=timeout_seconds)
    return wait_command(ssm, command['Command']['CommandId'], instance_id, timeout_seconds + 120)


def instance_state(ec2, instance_id):
    reservations = ec2.describe_instances(InstanceIds=[instance_id])['Reservations']
    return reservations[0]['Instances'][0]['State']['Name']


def wait_ssm_online(ssm, instance_id, timeout_seconds=600):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        info = ssm.describe_instance_information(Filters=[{'Key': 'InstanceIds', 'Values': [instance_id]}])
        if any(item.get('PingStatus') == 'Online' for item in info.get('InstanceInformationList', [])):
            return
        time.sleep(10)
    raise TimeoutError('SSM agent did not come online')


def lease_owner(environ):
    return f"{DEPLOY_OWNER_PREFIX}{environ.get('GITHUB_RUN_ID', 'local')}-{environ.get('GITHUB_RUN_ATTEMPT', '1')}"


def acquire_lease(dynamodb, instance_id, owner, wait_seconds=3600, poll_seconds=15):
    deadline = time.time() + wait_seconds
    while True:
        try:
            dynamodb.put_item(
                TableName=LOCK_TABLE,
                Item={'lock_id': {'S': instance_id}, 'owner': {'S': owner}, 'job_id': {'S': 'deploy'},
                      'started_at': {'S': datetime.now(timezone.utc).isoformat()}},
                ConditionExpression='attribute_not_exists(lock_id)')
            return
        except dynamodb.exceptions.ConditionalCheckFailedException:
            if time.time() >= deadline:
                raise TimeoutError('A batch job kept the execution lock; deployment skipped')
            print('a batch job owns the execution lock — waiting')
            time.sleep(poll_seconds)


def release_lease(dynamodb, instance_id, owner):
    try:
        dynamodb.delete_item(TableName=LOCK_TABLE, Key={'lock_id': {'S': instance_id}},
                             ConditionExpression='#owner = :owner',
                             ExpressionAttributeNames={'#owner': 'owner'},
                             ExpressionAttributeValues={':owner': {'S': owner}})
    except dynamodb.exceptions.ConditionalCheckFailedException:
        print('execution lock changed owner — leaving it')


def main():
    import boto3

    instance_id = os.environ['BATCH_INSTANCE_ID']
    image = os.environ['BATCH_IMAGE']
    prefix = os.environ['BATCH_RUNTIME_PARAMETER_PREFIX']
    validate(instance_id, image, prefix)
    ec2 = boto3.client('ec2', region_name=REGION)
    ssm = boto3.client('ssm', region_name=REGION)
    dynamodb = boto3.client('dynamodb', region_name=REGION)
    owner = lease_owner(os.environ)

    acquire_lease(dynamodb, instance_id, owner)
    try:
        install(ec2, ssm, instance_id, image, prefix)
    finally:
        release_lease(dynamodb, instance_id, owner)


def install(ec2, ssm, instance_id, image, prefix):
    state = instance_state(ec2, instance_id)
    started_here = False
    if state == 'stopping':
        ec2.get_waiter('instance_stopped').wait(InstanceIds=[instance_id])
        state = 'stopped'
    if state == 'stopped':
        print('batch EC2 is stopped — starting it for the deployment')
        ec2.start_instances(InstanceIds=[instance_id])
        started_here = True
    elif state not in ('pending', 'running'):
        raise RuntimeError(f'Unexpected instance state: {state}')
    try:
        ec2.get_waiter('instance_running').wait(InstanceIds=[instance_id])
        wait_ssm_online(ssm, instance_id)
        result = send(ssm, instance_id, install_script(image, prefix), timeout_seconds=1800)
        print(result.get('StandardOutputContent', '')[-4000:])
        if result['Status'] != 'Success':
            print(result.get('StandardErrorContent', '')[-4000:])
            raise RuntimeError(f'Batch install failed: {result["Status"]}')
    finally:
        if started_here:
            # 락을 쥐고 있으니 예약 작업이 끼어들 수 없다 — 꺼진 걸 확인한 뒤에 락을 놓는다
            print('stopping the batch EC2 started for this deployment')
            ec2.stop_instances(InstanceIds=[instance_id])
            ec2.get_waiter('instance_stopped').wait(InstanceIds=[instance_id])

if __name__ == '__main__':
    main()
