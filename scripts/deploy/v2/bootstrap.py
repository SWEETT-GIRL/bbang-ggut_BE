"""Ubuntu ARM EC2에서 실행. 비밀값을 stdout/user-data/이미지에 기록하지 않는다."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.request import urlopen

REQUIRED = {
    'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'KAKAO_CLIENT_ID', 'KAKAO_CLIENT_SECRET',
    'NAVER_LOGIN_CLIENT_ID', 'NAVER_LOGIN_CLIENT_SECRET', 'KAKAO_API_KEY',
    'JWT_SECRET', 'ENCRYPT_SECRET_KEY', 'AWS_S3_BUCKET',
    'LAMBDA_CALLBACK_SECRET_KEY', 'ADMIN_API_KEY',
}
OPTIONAL = {'NAVER_CLIENT_ID', 'NAVER_CLIENT_SECRET', 'GEMINI_API_KEY',
            'OPENAI_API_KEY', 'CLAUDE_API_KEY', 'SLACK_ERROR_WEBHOOK_URL', 'OPS_SERVER_ERROR_ALERT_ENABLED',
            # 메모 초안 Redis. MODE 가 없으면 초안 기능은 뜨지 않는다.
            'COURSE_DRAFT_REDIS_MODE', 'COURSE_DRAFT_REDIS_HOST', 'COURSE_DRAFT_REDIS_PORT',
            'COURSE_DRAFT_REDIS_DATABASE', 'COURSE_DRAFT_REDIS_PASSWORD',
            'COURSE_DRAFT_REDIS_SENTINEL_MASTER', 'COURSE_DRAFT_REDIS_SENTINEL_NODES',
            'COURSE_DRAFT_WRITE_ENABLED', 'COURSE_DRAFT_FLUSHER_ENABLED'}


def validate_secret(secret):
    env = secret.get('environment', {})
    if not isinstance(env, dict) or not REQUIRED <= env.keys():
        raise ValueError('Runtime secret is missing required environment keys')
    if env.keys() - REQUIRED - OPTIONAL:
        raise ValueError('Runtime secret contains unsupported environment keys')
    for key, value in env.items():
        if not isinstance(value, str) or any(c in value for c in '\r\n\0'):
            raise ValueError(f'Invalid env-file value for {key}')
        if key in REQUIRED and not value.strip():
            raise ValueError(f'Empty required value for {key}')
    alert_enabled = env.get('OPS_SERVER_ERROR_ALERT_ENABLED', 'false')
    if alert_enabled not in ('true', 'false'):
        raise ValueError('OPS_SERVER_ERROR_ALERT_ENABLED must be true or false')
    if alert_enabled == 'true' and not env.get('SLACK_ERROR_WEBHOOK_URL', '').strip():
        raise ValueError('Enabled server-error alerts require SLACK_ERROR_WEBHOOK_URL')
    firebase = secret.get('firebase_service_account', {})
    if not isinstance(firebase, dict) or not {'project_id', 'private_key', 'client_email'} <= firebase.keys():
        raise ValueError('Missing Firebase service account')
    hub = secret.get('dockerhub', {})
    if not all(isinstance(hub.get(key), str) and hub[key].strip() for key in ('username', 'token')):
        raise ValueError('Missing Docker Hub pull credentials')
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]*', hub['username']):
        raise ValueError('Invalid Docker Hub username')
    return env, firebase, hub


def load_parameters(ssm, prefix):
    # 경로 전체를 재귀 조회하지 않고 앱에 허용한 이름만 최대 10개씩 읽는다.
    suffixes = [f'env/{key}' for key in sorted(REQUIRED | OPTIONAL)]
    suffixes += ['firebase-service-account', 'dockerhub/username', 'dockerhub/token']
    names = [f'{prefix}/{suffix}' for suffix in suffixes]
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
    required.update(f'{prefix}/{suffix}' for suffix in suffixes[-3:])
    missing = required - values.keys()
    if missing:
        raise ValueError('Missing runtime parameters: ' + ', '.join(sorted(missing)))
    try:
        firebase = json.loads(values[f'{prefix}/firebase-service-account'])
    except json.JSONDecodeError:
        raise ValueError('Firebase parameter must contain valid JSON') from None
    return validate_secret({
        'environment': {key: values[f'{prefix}/env/{key}'] for key in REQUIRED | OPTIONAL
                        if f'{prefix}/env/{key}' in values},
        'firebase_service_account': firebase,
        'dockerhub': {key: values[f'{prefix}/dockerhub/{key}'] for key in ('username', 'token')},
    })


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def main():
    import boto3  # EC2의 python3-boto3; 단위 테스트에는 AWS SDK/인증이 필요 없다.

    os.umask(0o077)
    config = json.loads(Path('/opt/bbang-v2/config.json').read_text())
    env, firebase, hub = load_parameters(
        boto3.client('ssm', region_name=config['region']), config['parameter_prefix'])
    secret_dir = Path('/opt/bbang-v2/secrets')
    secret_dir.mkdir(mode=0o700, exist_ok=True)
    # 컨테이너 uid만 읽을 수 있는 파일. 부모 디렉터리는 root 전용이다.
    firebase_path = secret_dir / 'firebase.json'
    firebase_path.write_text(json.dumps(firebase))
    os.chown(firebase_path, 10001, 10001)
    firebase_path.chmod(0o400)
    with tempfile.TemporaryDirectory(prefix='bbang-v2-') as tmp:
        env_path = Path(tmp) / 'app.env'
        env_path.write_text(''.join(f'{key}={value}\n' for key, value in sorted(env.items())))
        docker_env = {**os.environ, 'DOCKER_CONFIG': tmp}
        run('docker', 'login', '--username', hub['username'], '--password-stdin',
            input=hub['token'], text=True, env=docker_env)
        run('docker', 'pull', config['image'], env=docker_env)
        # 재부팅 시에도 같은 digest를 사용한다. 이미지 pull 실패 시 기존 컨테이너를 먼저 지우지 않는다.
        existing = run('docker', 'ps', '-a', '--filter', 'name=^/bbang-ggut-v2$',
                       '--format', '{{.Names}}', capture_output=True, text=True).stdout.strip()
        if existing:
            run('docker', 'stop', '--time', '30', 'bbang-ggut-v2')
            run('docker', 'rm', 'bbang-ggut-v2')
        run('docker', 'run', '-d', '--name', 'bbang-ggut-v2', '--restart', 'unless-stopped',
            '--memory', '1536m', '--log-opt', 'max-size=10m', '--log-opt', 'max-file=3',
            '--stop-timeout', '30', '--env-file', str(env_path),
            '-e', 'SPRING_PROFILES_ACTIVE=v2', '-e', 'AWS_REGION=ap-northeast-2',
            '-e', 'TZ=Asia/Seoul',
            '-e', 'JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70.0 -Duser.timezone=Asia/Seoul',
            '-p', '8080:8080', '-p', '8081:8081',
            '--mount', f'type=bind,source={firebase_path},target=/run/secrets/firebase.json,readonly',
            config['image'])
    # ALB도 별도로 검사한다. 이 검사는 부팅 실패를 systemd journal에 드러낸다.
    for _ in range(60):
        try:
            with urlopen('http://127.0.0.1:8081/actuator/health/readiness', timeout=3) as resp:
                if resp.status == 200:
                    print('v2 container readiness passed')
                    return
        except OSError:
            pass
        time.sleep(5)
    raise RuntimeError('v2 readiness did not pass; inspect docker logs bbang-ggut-v2')


if __name__ == '__main__':
    main()
