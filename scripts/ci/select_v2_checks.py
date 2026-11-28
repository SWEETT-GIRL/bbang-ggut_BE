"""Select V2 checks conservatively from the complete PR diff, including deletions."""
import os
from pathlib import Path
import subprocess


BATCH_PREFIXES = ('batch/', 'dataops/', 'scripts/deploy/batch/')
BATCH_FILES = frozenset({'Dockerfile.batch', 'Dockerfile.batch.dockerignore'})
BATCH_MONITORING_FILES = frozenset({
    'monitoring/grafana/dataops-spring-v0.json',
    'monitoring/grafana/dataops-rds-cloudwatch.json',
    'monitoring/grafana/alerting/dataops-rules.json',
    'monitoring/prometheus/dataops-scrape.example.yml',
    'monitoring/prometheus/dataops-alerts.example.yml',
    'monitoring/compose.pushgateway.example.yml',
})
SHARED_IMAGE_PREFIXES = ('core/', 'config/', 'gradle/')
SHARED_IMAGE_FILES = frozenset({
    'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat',
    '.github/workflows/ci-v2.yml', '.github/workflows/cd-batch.yml',
    'scripts/ci/select_v2_checks.py',
})


def select_checks(paths):
    paths = [path for path in paths if path and not (
        path.endswith('.md') or path.startswith(('docs/', '.claude/')))]
    redis_only = bool(paths) and all(
        path.startswith('infra/redis/') or path == '.github/workflows/cd-redis-prod.yml'
        for path in paths)
    backend = bool(paths) and not redis_only
    batch_only = backend and all(
        path.startswith(BATCH_PREFIXES) or path in BATCH_FILES or path in BATCH_MONITORING_FILES
        for path in paths)
    batch_image = backend and any(
        path.startswith(BATCH_PREFIXES + SHARED_IMAGE_PREFIXES)
        or path in BATCH_FILES or path in SHARED_IMAGE_FILES for path in paths)
    return {
        'backend': str(backend).lower(),
        'build_scope': 'batch' if batch_only else 'full',
        'batch': str(batch_image).lower(),
        'workflows': str(any(path.startswith('.github/workflows/') for path in paths)).lower(),
    }


def changed_paths(base, head, cwd=None):
    # --no-renames retains both sides of moves across module boundaries.
    result = subprocess.check_output([
        'git', 'diff', '--no-renames', '--name-only', '-z', f'{base}...{head}', '--'], cwd=cwd)
    return [path for path in result.decode().split('\0') if path]


def main():
    checks = select_checks(changed_paths(os.environ['BASE_SHA'], os.environ['HEAD_SHA']))
    with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as output:
        for name, value in checks.items():
            output.write(f'{name}={value}\n')
    print('V2 checks: ' + ', '.join(f'{name}={value}' for name, value in checks.items()))


if __name__ == '__main__':
    main()
