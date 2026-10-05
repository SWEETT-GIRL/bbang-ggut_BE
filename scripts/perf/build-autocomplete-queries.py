#!/usr/bin/env python3
"""운영 자동완성 로그를 k6 입력으로 바꾼다.

    python3 scripts/perf/build-autocomplete-queries.py <request_log 내보내기.csv> [출력.json]

입력은 운영 request_log 에서 읽기 전용으로 뽑은 CSV(created_at, query_params, ...)다:

    \\copy (SELECT created_at, query_params FROM request_log
           WHERE endpoint = '/stores/autocomplete' AND created_at >= now() - interval '35 days')
      TO 'ac.csv' CSV HEADER

## 왜 시간 순서를 그대로 두나
캐시 적중률은 "같은 검색어가 얼마나 반복되나"로 정해진다. 빈도대로 무작위로 뽑으면 요청이 많을수록
드문 검색어까지 반복돼 적중률이 부풀려진다. 실제로 사람들이 친 순서를 그대로 빨리 감아야 반복 비율이 실제와 같다.

## 빼는 것
- 이메일처럼 보이는 검색어와, 그 이메일을 입력하는 도중의 접두어(h, ho, hoh …) — 사용자 개인정보이고,
  운영 로그에서는 테스트 계정 1명이 이메일을 글자마다 반복 입력한 요청 약 2,200건(전체의 34%)이 분포를 왜곡했다
- 정규화하면 빈 검색어(앱이 DB 까지 안 간다)

출력 파일은 사용자 입력이 담겨 git 에 올리지 않는다(.gitignore).
"""
import csv
import json
import re
import sys
from urllib.parse import parse_qs

NON_SEARCHABLE = re.compile(r'[^0-9a-z가-힣]')
EMAIL_LIKE = re.compile(r'(gmail|naver|daum|hanmail|kakao|icloud|outlook|hotmail)(com|net)')


def is_email(raw, normalized):
    return '@' in raw or EMAIL_LIKE.search(normalized) is not None


def main():
    source = sys.argv[1]
    target = sys.argv[2] if len(sys.argv) > 2 else 'scripts/perf/autocomplete-queries.json'
    parsed = []
    with open(source, encoding='utf-8') as file:
        for row in csv.DictReader(file):
            params = parse_qs(row['query_params'] or '')
            raw = params.get('query', [''])[0]
            parsed.append((row['created_at'], raw, NON_SEARCHABLE.sub('', raw.lower()), params))
    # 이메일을 치는 도중의 접두어까지 뺀다 — 이메일 자체를 파일에 남기지 않으려고 목록은 실행 중에만 만든다
    emails = {normalized for _, raw, normalized, _ in parsed if normalized and is_email(raw, normalized)}
    rows = []
    for created_at, raw, normalized, params in parsed:
        if not normalized or any(email.startswith(normalized) for email in emails):
            continue
        rows.append((created_at, {
            'q': raw,
            'size': params.get('size', [''])[0],
            'types': params.get('types', [''])[0],
        }))
    rows.sort(key=lambda pair: pair[0])
    queries = [query for _, query in rows]
    with open(target, 'w', encoding='utf-8') as file:
        json.dump(queries, file, ensure_ascii=False)
    unique = len({NON_SEARCHABLE.sub('', query['q'].lower()) for query in queries})
    print(f'{len(queries)}건 (고유 검색어 {unique}개) → {target}')


if __name__ == '__main__':
    main()
