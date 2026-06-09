#!/usr/bin/env python3
"""store.location 좌표 → Kakao coord2regioncode(행정동 H) → store.region_3rd 업데이트.

- region_1st, region_2nd 는 건드리지 않음
- region_3rd 는 읍/면/동 suffix 앞 숫자 제거 (성수2가3동 → 성수동, 정자2동 → 정자동)
- 50개 단위로 배치 로그 출력

실행:
  python3 scripts/normalize_store_regions.py           # 전체 실행
  python3 scripts/normalize_store_regions.py --dry-run # DB 변경 없이 샘플 10건만 출력

의존: psycopg2, requests (시스템 Python 3.10)
"""

import argparse
import re
import time
from pathlib import Path

import psycopg2
import requests

KAKAO_COORD2REGION_URL = "https://dapi.kakao.com/v2/local/geo/coord2regioncode.json"
REQUEST_INTERVAL = 0.12
RETRY_BACKOFF = [2.0, 5.0, 10.0]
BATCH_SIZE = 50


def _load_properties() -> dict[str, str]:
    path = Path(__file__).resolve().parent.parent / "src/main/resources/application-local.properties"
    cfg: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, _, v = line.partition("=")
            cfg[k.strip()] = v.strip()
    return cfg


def db_config() -> dict:
    cfg = _load_properties()
    rest = cfg["spring.datasource.url"].split("//", 1)[1]
    hostport, _, dbname = rest.partition("/")
    host, _, port = hostport.partition(":")
    return {
        "host": host, "port": int(port or 5432), "dbname": dbname,
        "user": cfg["spring.datasource.username"],
        "password": cfg["spring.datasource.password"],
    }


def kakao_api_key() -> str:
    return _load_properties()["kakao.api.key"]


def strip_dong_number(name: str) -> str:
    """성수2가3동 → 성수동, 정자2동 → 정자동, 신촌동 → 신촌동."""
    if not name:
        return name
    return re.sub(r'\d+(가\d+)?(?=[동읍면]$)', '', name)


def coord2region_h(lng: float, lat: float, api_key: str) -> dict | None:
    """행정동(H) 문서 반환. 없거나 실패 시 None."""
    headers = {"Authorization": f"KakaoAK {api_key}"}
    params = {"x": lng, "y": lat, "input_coord": "WGS84"}

    for attempt in range(len(RETRY_BACKOFF) + 1):
        try:
            resp = requests.get(KAKAO_COORD2REGION_URL, headers=headers, params=params, timeout=10)
            if resp.status_code == 429:
                wait = RETRY_BACKOFF[min(attempt, len(RETRY_BACKOFF) - 1)]
                print(f"  [429] {wait}s 대기...", flush=True)
                time.sleep(wait)
                continue
            resp.raise_for_status()
            docs = resp.json().get("documents", [])
            return next((d for d in docs if d.get("region_type") == "H"), None)
        except Exception as e:
            if attempt < len(RETRY_BACKOFF):
                wait = RETRY_BACKOFF[attempt]
                print(f"  [오류] {e} — {wait}s 후 재시도...", flush=True)
                time.sleep(wait)
            else:
                return None
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="샘플 10건 출력, DB 변경 없음")
    args = parser.parse_args()

    api_key = kakao_api_key()
    conn = psycopg2.connect(**db_config(), connect_timeout=15)
    cur = conn.cursor()

    limit_clause = "LIMIT 10" if args.dry_run else ""
    cur.execute(f"""
        SELECT id, name, region_2nd, region_3rd,
               ST_X(location::geometry) AS lng,
               ST_Y(location::geometry) AS lat
        FROM store
        WHERE location IS NOT NULL
        ORDER BY id
        {limit_clause}
    """)
    stores = cur.fetchall()
    total = len(stores)
    print(f"{'[dry-run] ' if args.dry_run else ''}대상: {total}건\n")

    ok = fail = skip = 0
    batch_logs = []

    for idx, (store_id, name, region_2nd, old_r3, lng, lat) in enumerate(stores, 1):
        doc = coord2region_h(lng, lat, api_key)

        if doc is None:
            fail += 1
            batch_logs.append(f"  [FAIL] #{store_id} {name}")
        else:
            raw_r3 = doc.get("region_3depth_name") or None
            new_r3 = strip_dong_number(raw_r3) if raw_r3 else None

            # 숫자나 .이 남아있으면 비정상 행정동명(종로1.2.3.4가동 등) — 건너뜀
            if new_r3 and re.search(r'[\d.]', new_r3):
                skip += 1
                batch_logs.append(f"  [SKIP] #{store_id} {name} → {new_r3} (비정상 행정동명, 기존값 유지)")
            else:
                # 구 단위로 반환된 경우 처리
                if new_r3 and new_r3.endswith('구'):
                    new_r3 = region_2nd if region_2nd == '청주시' else new_r3

                if new_r3 == old_r3:
                    skip += 1
                    batch_logs.append(f"  [SKIP] #{store_id} {name} → {new_r3} (변경 없음)")
                else:
                    if not args.dry_run:
                        cur.execute(
                            "UPDATE store SET region_3rd=%s WHERE id=%s",
                            (new_r3, store_id),
                        )
                    ok += 1
                    batch_logs.append(f"  [OK]   #{store_id} {name} | {old_r3} → {new_r3}")

        # 50개 단위 배치 로그
        if idx % BATCH_SIZE == 0 or idx == total:
            batch_start = idx - (idx % BATCH_SIZE or BATCH_SIZE) + 1
            print(f"=== {batch_start}~{idx}번째 처리 완료 (✓{ok} skip:{skip} ✗{fail}) ===")
            for log in batch_logs:
                print(log)
            print()
            batch_logs = []

            if not args.dry_run:
                conn.commit()

        time.sleep(REQUEST_INTERVAL)

    print(f"최종 — 업데이트:{ok}  변경없음:{skip}  실패:{fail}")
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
