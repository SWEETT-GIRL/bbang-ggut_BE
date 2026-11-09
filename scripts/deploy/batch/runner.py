"""배치 EC2 에서 실행(/usr/local/bin/bbang-batch). 비밀값을 stdout·이미지·디스크(임시 파일 외)에 남기지 않는다.

  bbang-batch install                    배포 — 옛 이미지를 정리하고 config.json.next 의 이미지(digest)를 받은 뒤 config.json 으로 승격
                                         (서버에는 현재·다음 이미지 2개만 남는다)
  bbang-batch job <이름>                 WAS 배치 작업 하나 (Scheduler → Step Functions → SSM 문서 SweetgirlBatchRun 이 부른다)
  bbang-batch dataops-nightly [시간] [최대가게수]     dataops 수집
  bbang-batch dataops-cycle [run|plan|seed-refresh] [옵션]  전체 최신화 독립 트랙
  bbang-batch dataops-migrate            별도 dataops DB의 Flyway (DDL 계정 전용)
  bbang-batch dataops <스크립트> [인자]  dataops 수동 작업(승인·반영)
  bbang-batch busy                       작업 컨테이너가 돌고 있으면 0 — 수동 점검용

실행 시점에 Parameter Store 를 읽는다 — 값을 바꾸면 다음 실행부터 바로 반영된다(재배포 불필요).
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from batch_notifications import BatchNotification, selected_command

BASE = Path('/opt/bbang-batch')
CONTAINER_UID = 10001
JOB_LABEL = 'bbang-batch.job'
MEMORY_LIMIT = '1600m'   # t4g.small 2GB — 호스트·Docker 몫을 남긴다

REQUIRED = {'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'ENCRYPT_SECRET_KEY', 'AWS_S3_BUCKET'}
OPTIONAL = {
    'REDIS_HOST', 'REDIS_PORT', 'REDIS_PASSWORD',
    # 캐시 Redis Sentinel — 있으면 HOST 대신 감시자에게 마스터를 묻는다(급부상 순위 계산이 쓴다)
    'SPRING_DATA_REDIS_SENTINEL_MASTER', 'SPRING_DATA_REDIS_SENTINEL_NODES',
    'TRENDING_ENABLED', 'SEARCH_MISS_TRIAGE_ENABLED', 'SEARCH_MISS_TRIAGE_AUTO_APPLY_VERDICTS', 'SEARCH_MISS_TRIAGE_MODEL',
    'CLAUDE_API_KEY', 'OPENAI_API_KEY', 'GEMINI_API_KEY', 'KAKAO_API_KEY', 'NAVER_CLIENT_ID', 'NAVER_CLIENT_SECRET',
    'SLACK_ERROR_WEBHOOK_URL', 'SLACK_REPORT_WEBHOOK_URL', 'SLACK_ABUSE_WEBHOOK_URL', 'SLACK_LETTER_WEBHOOK_URL',
    'SLACK_SEARCH_MISS_WEBHOOK_URL', 'SLACK_NOTIFICATION_ENABLED',
    # dataops — 판정 서버(dataops 스키마)·API 키·반영용 운영 쓰기 계정·사진 반영(본 앱 어드민 API)
    'DATAOPS_DB_URL', 'DATAOPS_DB_USERNAME', 'DATAOPS_DB_PASSWORD', 'DATAOPS_API_KEY',
    'DATAOPS_PUSHGATEWAY_URL', 'DATAOPS_SLACK_WEBHOOK_URL',
    'DATA_GO_KR_SERVICE_KEY', 'DATAOPS_FULL_CYCLE_ENABLED', 'DATAOPS_BASELINE_VERSION',
    'DATAOPS_PUBLIC_DATASETS', 'DATAOPS_PUBLIC_MAX_PAGES', 'DATAOPS_CYCLE_MAX_ITEMS', 'DATAOPS_SWEEP_MAX_REGIONS',
    'DATAOPS_MIGRATION_DB_USERNAME', 'DATAOPS_MIGRATION_DB_PASSWORD',
    'DATAOPS_PROD_DB_USERNAME', 'DATAOPS_PROD_DB_PASSWORD',
    'ADMIN_API_KEY', 'WAS_SERVER_URL',
}
# dataops 명령은 dataops 스키마 없이는 의미가 없다
DATAOPS_REQUIRED = {'DATAOPS_DB_URL', 'DATAOPS_DB_USERNAME', 'DATAOPS_DB_PASSWORD', 'DATAOPS_API_KEY'}
MIGRATION_SECRETS = {'DATAOPS_MIGRATION_DB_USERNAME', 'DATAOPS_MIGRATION_DB_PASSWORD'}
IMAGE = re.compile(r'(?:docker\.io/)?[a-z0-9][a-z0-9_-]*/[a-z0-9][a-z0-9._-]*@sha256:[0-9a-f]{64}')
PREFIX = re.compile(r'/sweetgirl/prod/v2/batch(?:/[A-Za-z0-9_-]+){0,8}')
JOB_NAME = re.compile(r'[a-z0-9][a-z0-9-]{0,62}')
SCRIPT_NAME = re.compile(r'[a-z0-9_]+\.py')


def validate_config(config):
    if not IMAGE.fullmatch(config.get('image', '')):
        raise ValueError('Use a Docker Hub image pinned by SHA256 digest')
    if not PREFIX.fullmatch(config.get('parameter_prefix', '')):
        raise ValueError('Use a parameter prefix under /sweetgirl/prod/v2/batch without a trailing slash')
    if config.get('region') != 'ap-northeast-2':
        raise ValueError('Unexpected region')
    return config


def parameter_names(prefix):
    suffixes = [f'env/{key}' for key in sorted(REQUIRED | OPTIONAL)]
    suffixes += ['firebase-service-account', 'dockerhub/username', 'dockerhub/token']
    return [f'{prefix}/{suffix}' for suffix in suffixes]


def load_parameters(ssm, prefix):
    # 경로 전체를 재귀 조회하지 않고 허용한 이름만 10개씩 읽는다(v2 bootstrap 과 같은 규칙)
    names = parameter_names(prefix)
    values = {}
    for offset in range(0, len(names), 10):
        batch = names[offset:offset + 10]
        response = ssm.get_parameters(Names=batch, WithDecryption=True)
        for param in response.get('Parameters', []):
            name, value = param['Name'], param['Value']
            if name not in batch or name in values:
                raise ValueError('Unexpected runtime parameter response')
            if param['Type'] != 'SecureString':
                raise ValueError(f'Runtime parameter must use SecureString: {name}')
            if len(value.encode('utf-8')) > 4096:
                raise ValueError(f'Runtime parameter exceeds Standard 4 KiB limit: {name}')
            values[name] = value
    required = {f'{prefix}/env/{key}' for key in REQUIRED}
    required |= {f'{prefix}/firebase-service-account', f'{prefix}/dockerhub/username', f'{prefix}/dockerhub/token'}
    missing = required - values.keys()
    if missing:
        raise ValueError('Missing runtime parameters: ' + ', '.join(sorted(missing)))
    env = {key: values[f'{prefix}/env/{key}'] for key in REQUIRED | OPTIONAL if f'{prefix}/env/{key}' in values}
    for key, value in env.items():
        if any(c in value for c in '\r\n\0'):
            raise ValueError(f'Invalid env-file value for {key}')
    try:
        firebase = json.loads(values[f'{prefix}/firebase-service-account'])
    except json.JSONDecodeError:
        raise ValueError('Firebase parameter must contain valid JSON') from None
    if not isinstance(firebase, dict) or not {'project_id', 'private_key', 'client_email'} <= firebase.keys():
        raise ValueError('Missing Firebase service account')
    hub = {key: values[f'{prefix}/dockerhub/{key}'] for key in ('username', 'token')}
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]*', hub['username']):
        raise ValueError('Invalid Docker Hub username')
    return env, firebase, hub


def container_args(command):
    """bbang-batch 인자 → (컨테이너 명령, 작업 라벨, dataops 볼륨 필요 여부). 셸을 거치지 않으므로 이름을 엄격히 검사한다"""
    if not command:
        raise ValueError('Command required')
    head, rest = command[0], command[1:]
    if head == 'dataops-migrate' and not rest:
        return ['dataops-migrate'], 'dataops-migrate', False
    if head == 'dataops-cycle':
        return ['dataops-cycle', *rest], 'dataops-cycle', True
    if head == 'job':
        if len(rest) != 1 or not JOB_NAME.fullmatch(rest[0]):
            raise ValueError('Usage: job <name>')
        return ['job', rest[0]], rest[0], False
    if head == 'dataops-nightly':
        if (len(rest) > 2 or (rest and not re.fullmatch(r'\d+(?:\.\d+)?', rest[0]))
                or (len(rest) == 2 and not re.fullmatch(r'[1-9][0-9]*', rest[1]))):
            raise ValueError('Usage: dataops-nightly [hours] [max-stores]')
        return ['dataops-nightly', *rest], 'dataops-nightly', True
    if head == 'dataops':
        if not rest or not SCRIPT_NAME.fullmatch(rest[0]):
            raise ValueError('Usage: dataops <script.py> [args...]')
        return ['dataops', *rest], 'dataops-' + rest[0].removesuffix('.py').replace('_', '-'), True
    raise ValueError(f'Unknown command: {head}')


def docker_run_args(image, env_path, firebase_path, logs_dir, command):
    args, label, needs_logs = container_args(command)
    run = ['docker', 'run', '--rm', '--label', f'{JOB_LABEL}={label}',
        '--memory', MEMORY_LIMIT, '--log-opt', 'max-size=10m', '--log-opt', 'max-file=3',
        '--env-file', str(env_path), '-e', 'AWS_REGION=ap-northeast-2', '-e', 'TZ=Asia/Seoul',
        '-e', 'DATAOPS_IMAGE_REF=' + image]
    if command[0] != 'dataops-migrate':
        run += ['--mount', f'type=bind,source={firebase_path},target=/run/secrets/firebase.json,readonly']
    if needs_logs:
        run += ['--mount', f'type=bind,source={logs_dir},target=/app/dataops/dataops-logs']
    return run + [image, *args], needs_logs


NEXT_CONFIG_NAME = 'config.json.next'   # 배포가 먼저 두는 다음 설정. pull 이 끝나야 config.json 이 된다


def load_config():
    return validate_config(json.loads((BASE / 'config.json').read_text()))


def load_next_config(base=BASE):
    """설치용 설정 — 배포 스크립트가 둔 config.json.next 를 우선 읽는다. 없으면(수동 재설치) 현재 config.json."""
    path = base / NEXT_CONFIG_NAME
    if not path.exists():
        path = base / 'config.json'
    return validate_config(json.loads(path.read_text()))


def current_image(base=BASE):
    """지금 작업이 쓰는 이미지(config.json 의 image). 첫 설치면 None."""
    path = base / 'config.json'
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text()).get('image')
    except ValueError:
        return None


def image_repository(image):
    return image.split('@', 1)[0]


def images_to_remove(listed, keep):
    """같은 저장소의 digest 참조 중 keep 에 없는 것. digest 가 없는 줄(<none>)은 건드리지 않는다."""
    return [ref for ref in listed if '@sha256:' in ref and ref not in keep]


def prune_images(repository, keep, run_command=subprocess.run):
    """현재·다음 이미지만 남기고 같은 저장소의 나머지를 지운다 — pull 전에 돌려 디스크 최대치를 이미지 2개로 묶는다.
    20GB 디스크에 이미지 하나가 2.5GB 라 "7일 보관"은 6개(15GB)가 쌓여 pull 도중 디스크가 찼다(2026-11-09)."""
    listed = run_command(['docker', 'images', '--digests', '--format', '{{.Repository}}@{{.Digest}}', repository],
                         check=False, capture_output=True, text=True).stdout.split()
    removed = []
    for ref in images_to_remove(listed, keep):
        run_command(['docker', 'rmi', ref], check=False)
        removed.append(ref)
    return removed


def install_image(config, hub, base=BASE, run_command=subprocess.run):
    """로그인 → 옛 이미지 정리 → pull → config.json 승격.
    pull 이 실패하면 config.json 은 그대로라 예약 작업은 계속 현재 이미지로 돈다(설정만 앞서가는 어긋남을 막는다)."""
    image = config['image']
    previous = current_image(base)
    with tempfile.TemporaryDirectory(prefix='bbang-batch-') as tmp:
        docker_env = {**os.environ, 'DOCKER_CONFIG': tmp}
        run_command(['docker', 'login', '--username', hub['username'], '--password-stdin'],
                    check=True, input=hub['token'], text=True, env=docker_env)
        removed = prune_images(image_repository(image), {previous, image} - {None}, run_command)
        run_command(['docker', 'pull', image], check=True, env=docker_env)
    next_path = base / NEXT_CONFIG_NAME
    if next_path.exists():
        os.replace(next_path, base / 'config.json')
    for ref in removed:
        print(f'removed old image: {ref}')
    print(f'batch image ready: {image}')


def install(config):
    import boto3  # EC2 의 python3-boto3; 단위 테스트에는 AWS SDK·인증이 필요 없다
    _, _, hub = load_parameters(boto3.client('ssm', region_name=config['region']), config['parameter_prefix'])
    install_image(config, hub)
    (BASE / 'dataops-logs').mkdir(mode=0o755, exist_ok=True)
    os.chown(BASE / 'dataops-logs', CONTAINER_UID, CONTAINER_UID)


def execute(config, command):
    import boto3
    env, firebase, _ = load_parameters(boto3.client('ssm', region_name=config['region']), config['parameter_prefix'])
    env = execution_environment(env, command)
    notification_command = selected_command(command)
    webhook = env.pop('DATAOPS_SLACK_WEBHOOK_URL', '')  # 호스트에서만 전송한다.
    notification = BatchNotification(BASE / 'dataops-logs', notification_command, webhook) if notification_command else None
    if notification:
        env['DATAOPS_INVOCATION_ID'] = notification.record['invocation_id']
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='bbang-batch-') as tmp:
        env_path = Path(tmp) / 'app.env'
        env_path.write_text(''.join(f'{key}={value}\n' for key, value in sorted(env.items())))
        firebase_path = Path(tmp) / 'firebase.json'
        firebase_path.write_text(json.dumps(firebase))
        # 컨테이너 uid 만 읽는다. 상위 임시 디렉터리는 root 전용(0700) — bind mount 는 root 인 Docker 데몬이 붙인다
        os.chown(firebase_path, CONTAINER_UID, CONTAINER_UID)
        firebase_path.chmod(0o400)
        args, _ = docker_run_args(config['image'], env_path, firebase_path, BASE / 'dataops-logs', command)
        if notification: notification.notify('start')
        code = 1
        try:
            code = subprocess.run(args, check=False).returncode
            return code
        except KeyboardInterrupt:
            code = 130
            raise
        finally:
            if notification: notification.finish(code)


def busy():
    output = subprocess.run(['docker', 'ps', '--filter', f'label={JOB_LABEL}', '--format', '{{.ID}}'],
        capture_output=True, text=True, check=True).stdout.strip()
    return 0 if output else 1


def execution_environment(env, command):
    """Never give DDL credentials to a collection/application container."""
    if command[0] == 'dataops-migrate':
        required = {'DATAOPS_DB_URL'} | MIGRATION_SECRETS
        selected = {key: value for key, value in env.items() if key in required}
    else:
        required = DATAOPS_REQUIRED if command[0] != 'job' else set()
        selected = {key: value for key, value in env.items() if key not in MIGRATION_SECRETS}
        full = env.get('DATAOPS_FULL_CYCLE_ENABLED', 'false')
        if full not in ('true', 'false'):
            raise ValueError('DATAOPS_FULL_CYCLE_ENABLED must be true or false')
        if command[0] == 'dataops-cycle' or (command[0] == 'dataops-nightly' and full == 'true'):
            required = required | {'DB_URL', 'DATAOPS_PROD_DB_USERNAME', 'DATAOPS_PROD_DB_PASSWORD',
                                   'DATA_GO_KR_SERVICE_KEY'}
            if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', env.get('DATAOPS_BASELINE_VERSION', 'cloud-001')):
                raise ValueError('DATAOPS_BASELINE_VERSION must be a safe version identifier')
    missing = {key for key in required if not selected.get(key, "").strip()}
    if missing:
        raise ValueError('Missing dataops parameters: ' + ', '.join(sorted(missing)))
    if command[0] != 'job':
        target = r'jdbc:postgresql://[A-Za-z0-9.-]+(?::[0-9]+)?/dataops'
        options = r'(?:\?(?:currentSchema=public|sslmode=require|sslmode=verify-full)(?:&(?:currentSchema=public|sslmode=require|sslmode=verify-full))*)?'
        if not re.fullmatch(target + options, selected['DATAOPS_DB_URL']):
            raise ValueError('DATAOPS_DB_URL must target separate database dataops with public schema')
    return selected


def main(argv):
    if not argv:
        sys.exit(__doc__)
    if argv[0] == 'busy':
        return busy()
    if argv[0] == 'install':
        install(load_next_config())
        return 0
    config = load_config()
    container_args(argv)   # 잘못된 인자는 Parameter Store 를 읽기 전에 거른다
    return execute(config, argv)


if __name__ == '__main__':
    try:
        sys.exit(main(sys.argv[1:]))
    except ValueError as error:
        sys.exit(f'bbang-batch: {error}')
