"""배치 컨테이너 안에서 실행. Java 용 JDBC URL 을 Python 스크립트가 읽는 접속 변수로 풀어 셸 export 문으로 출력한다.

Parameter Store 에는 JDBC URL 하나만 둔다 — 같은 값을 Python 용으로 두 번 등록하다 어긋나지 않게.
  DATAOPS_DB_URL → DATAOPS_DB_HOST·PORT·NAME·SCHEMA (+ USER·PASSWORD ← DATAOPS_DB_USERNAME·PASSWORD)
  DB_URL         → PROD_DB_HOST·PORT (weekly_refresh apply 의 운영 DB 도달 점검)
"""
import os
import re
import shlex
import sys
from urllib.parse import parse_qs

JDBC = re.compile(r'jdbc:postgresql://(?P<host>[A-Za-z0-9.-]+)(?::(?P<port>\d+))?/(?P<db>[A-Za-z0-9_-]+)(?:\?(?P<query>.*))?')


def parse(url):
    match = JDBC.fullmatch(url or '')
    if not match:
        raise ValueError('Unsupported JDBC URL')
    query = parse_qs(match['query'] or '')
    schema = (query.get('currentSchema') or [''])[0]
    return match['host'], match['port'] or '5432', match['db'], schema


def exports(env):
    values = {}
    if env.get('DATAOPS_DB_URL'):
        host, port, name, schema = parse(env['DATAOPS_DB_URL'])
        values.update(DATAOPS_DB_HOST=host, DATAOPS_DB_PORT=port, DATAOPS_DB_NAME=name, DATAOPS_DB_SCHEMA=schema)
        query = parse_qs((env['DATAOPS_DB_URL'].split('?', 1) + [''])[1])
        if query.get('sslmode'):
            mode = query['sslmode'][0]
            if mode not in ('require', 'verify-full'):
                raise ValueError('Unsupported dataops TLS mode')
            values['DATAOPS_DB_SSLMODE'] = mode
        if env.get('DATAOPS_DB_USERNAME'):
            values['DATAOPS_DB_USER'] = env['DATAOPS_DB_USERNAME']
        if env.get('DATAOPS_DB_PASSWORD'):
            values['DATAOPS_DB_PASSWORD'] = env['DATAOPS_DB_PASSWORD']
    if env.get('DB_URL'):
        host, port, _, _ = parse(env['DB_URL'])
        values.update(PROD_DB_HOST=host, PROD_DB_PORT=port)
    return ''.join(f'export {key}={shlex.quote(value)}\n' for key, value in sorted(values.items()))


if __name__ == '__main__':
    try:
        sys.stdout.write(exports(os.environ))
    except ValueError as error:
        sys.exit(f'jdbc_env: {error}')
