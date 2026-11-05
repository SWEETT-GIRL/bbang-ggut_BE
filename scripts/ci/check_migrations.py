#!/usr/bin/env python3
"""develop-v2 PR 의 Flyway 마이그레이션을 검사한다.

v2 는 머지 즉시 운영 RDS 에 마이그레이션을 적용하고, v1 은 같은 DB 를 계속 쓴다.
그래서 v1 이 종료되기 전까지는 v1 을 깨뜨리는 변경을 머지 전에 막는다.

- 이미 있는 파일의 수정·삭제·이름 변경은 항상 실패한다(적용 이력의 체크섬이 깨진다).
- 새 파일의 버전은 지금까지의 최신보다 커야 하고, 미래 시각이면 안 된다.
- 삭제·이름 변경·타입 변경·기본값 없는 NOT NULL 은 실패한다. 라벨로만 넘어갈 수 있다.
"""
import argparse
import datetime
import os
import re
import subprocess
import sys

MIGRATION_DIR = 'core/src/main/resources/db/migration'
ALLOW_LABEL = 'migration-breaking-ok'
KST = datetime.timezone(datetime.timedelta(hours=9))
NEW_FILE = re.compile(r'^V(\d{14})__[a-z0-9_]+\.sql$')
ANY_VERSION = re.compile(r'^V(\d+(?:[._]\d+)*)__')
SQL_NOISE = re.compile(r"--[^\n]*|/\*.*?\*/|'(?:[^']|'')*'", re.DOTALL)
BREAKING = (
    (re.compile(r'\bDROP\s+TABLE\b', re.I), 'DROP TABLE'),
    (re.compile(r'\bDROP\s+COLUMN\b', re.I), 'DROP COLUMN'),
    (re.compile(r'\bALTER\s+TABLE\b[^;]*?,?\s\bDROP\s+(?!CONSTRAINT\b|DEFAULT\b|NOT\b|COLUMN\b|IDENTITY\b|EXPRESSION\b)\w+', re.I), 'DROP COLUMN'),
    (re.compile(r'\bTRUNCATE\b', re.I), 'TRUNCATE'),
    (re.compile(r'\bRENAME\b', re.I), 'RENAME'),
    (re.compile(r'\bSET\s+NOT\s+NULL\b', re.I), 'SET NOT NULL'),
    (re.compile(r'\bALTER\s+(?:COLUMN\s+)?\w+\s+(?:SET\s+DATA\s+)?TYPE\b', re.I), 'ALTER COLUMN TYPE'),
)
ADD_COLUMN = re.compile(
    r'\bADD\s+(?:COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?(?!CONSTRAINT\b|PRIMARY\b|UNIQUE\b|FOREIGN\b|CHECK\b|EXCLUDE\b)\w+\b',
    re.I)


def version_key(filename):
    match = ANY_VERSION.match(filename)
    if not match:
        return None
    return tuple(int(part) for part in re.split(r'[._]', match.group(1)))


def strip_sql_noise(sql):
    return SQL_NOISE.sub(lambda m: "''" if m.group(0).startswith("'") else ' ', sql)


def column_clause(sql, start):
    """ADD COLUMN 절의 끝(괄호 밖 쉼표나 세미콜론)까지 잘라 낸다. numeric(10,2) 를 끊지 않는다."""
    depth = 0
    for index in range(start, len(sql)):
        char = sql[index]
        if char == '(':
            depth += 1
        elif char == ')':
            depth -= 1
        elif depth == 0 and char in ',;':
            return sql[start:index]
    return sql[start:]


def breaking_changes(sql):
    clean = strip_sql_noise(sql)
    found = {label for pattern, label in BREAKING if pattern.search(clean)}
    for match in ADD_COLUMN.finditer(clean):
        clause = column_clause(clean, match.start())
        if re.search(r'\bNOT\s+NULL\b', clause, re.I) and not re.search(r'\b(DEFAULT|GENERATED)\b', clause, re.I):
            found.add('기본값 없는 NOT NULL 컬럼')
    return sorted(found)


def check(changes, existing_names, read_sql, now, allow_breaking=False):
    """changes 는 (상태, 경로) 목록. 실패 사유 목록과 라벨로 넘어간 사유 목록을 돌려준다."""
    errors, allowed = [], []
    versions = [key for key in map(version_key, existing_names) if key]
    latest = max(versions) if versions else ()
    limit = (int((now + datetime.timedelta(days=1)).strftime('%Y%m%d%H%M%S')),)
    for status, path in changes:
        name = os.path.basename(path)
        if status != 'A':
            errors.append(f'{name}: 이미 있는 마이그레이션은 수정·삭제·이름 변경할 수 없습니다. 새 파일로 추가하세요.')
            continue
        if not NEW_FILE.match(name):
            errors.append(f'{name}: 파일명은 V{{yyyyMMddHHmmss}}__snake_case.sql 이어야 합니다.')
            continue
        key = version_key(name)
        if key <= latest:
            errors.append(f'{name}: 버전이 최신({".".join(map(str, latest))})보다 커야 합니다. 현재 시각으로 파일명을 바꾸세요.')
        if key > limit:
            errors.append(f'{name}: 버전이 미래 시각입니다. 다음 마이그레이션이 막히니 현재 시각으로 바꾸세요.')
        for reason in breaking_changes(read_sql(path)):
            message = f'{name}: {reason} 은 v1 이 쓰는 스키마를 깨뜨릴 수 있습니다.'
            (allowed if allow_breaking else errors).append(message)
    return errors, allowed


def git(*args):
    return subprocess.check_output(['git', *args], text=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', required=True)
    parser.add_argument('--labels', default='', help='쉼표로 구분한 PR 라벨')
    args = parser.parse_args()

    merge_base = git('merge-base', args.base, args.head).strip()
    changes = []
    for line in git('diff', '--no-renames', '--name-status', merge_base, args.head, '--', MIGRATION_DIR).splitlines():
        status, path = line.split('\t', 1)
        changes.append((status[0], path))
    # 머지 기준은 PR 이 갈라진 시점이 아니라 지금의 base 다. 그사이 들어온 더 최신 파일도 비교한다.
    existing = [os.path.basename(path) for path in git('ls-tree', '-r', '--name-only', args.base, MIGRATION_DIR).splitlines()]
    labels = {label.strip() for label in args.labels.split(',') if label.strip()}
    errors, allowed = check(
        changes, existing, lambda path: git('show', f'{args.head}:{path}'),
        datetime.datetime.now(KST), ALLOW_LABEL in labels)

    print(f'마이그레이션 변경 {len(changes)}개 검사')
    for message in allowed:
        print(f'::warning::라벨 {ALLOW_LABEL} 로 허용: {message}')
    for message in errors:
        print(f'::error::{message}')
    if errors:
        print(f'v1 종료 전까지 마이그레이션은 추가만 합니다. 정말 필요하면 v1 영향을 확인한 뒤 라벨 {ALLOW_LABEL} 을 답니다.')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
