#!/usr/bin/env python3
"""법정동코드 기준표 스냅샷 — 행정안전부 법정동코드 API 전체를 받아 버전 고정 파일로 저장한다 (v3 P4-4 · D-07).

왜 API 인가 (2026-09-26 확인)
- 공공데이터포털 파일(국토교통부_법정동코드, 수정일 08-13)은 2026-07-01 행정구역 개편 전 내용이었다
  (전남광주통합특별시 12·인천 서해구 등이 없다). API 는 개편이 반영돼 있다.
- 상위 코드(locathigh_cd)와 최하위 이름(locallow_nm)을 따로 줘서 이름을 쪼개 계층을 추정할 필요가 없다.

왜 해소할 때마다 부르지 않나
- API 는 날마다 바뀔 수 있다. 판정이 어느 기준표에서 나왔는지 남기려면 고정된 파일이 필요하다.
- 개발계정 일일 10,000회 한도. 전체(약 2만 건)는 1,000건씩 약 21회로 받는다.

API 는 현존 코드만 준다(폐지 코드 없음). 폐지 이력은 국토교통부 파일로 보조한다.
행 순서는 API 페이지 순서에 기대지 않고 코드순으로 정렬해 저장한다 — 같은 내용이면 같은 해시가 나온다.

사용
  python scripts/bjd_reference_snapshot.py            # reference/bjd_code_api_{오늘}.csv
키: .env 의 DATA_GO_KR_SERVICE_KEY (인코딩형이면 풀어서 쓴다). 값은 어디에도 찍지 않는다.
"""
import csv
import datetime as dt
import hashlib
import sys
import time
import urllib.parse
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
URL = "https://apis.data.go.kr/1741000/StanReginCd/getStanReginCdList"
PAGE = 1000
FIELDS = ["region_cd", "sido_cd", "sgg_cd", "umd_cd", "ri_cd", "locatadd_nm", "locallow_nm",
          "locathigh_cd", "locat_order", "adpt_de", "locat_rm", "locatjumin_cd", "locatjijuk_cd"]


def service_key():
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if line.startswith("DATA_GO_KR_SERVICE_KEY="):
            raw = line.split("=", 1)[1].strip()
            return urllib.parse.unquote(raw) if "%" in raw else raw
    raise SystemExit("DATA_GO_KR_SERVICE_KEY 가 .env 에 없다")


def page(session, key, number):
    for attempt in range(4):
        r = session.get(URL, params={"ServiceKey": key, "pageNo": number, "numOfRows": PAGE, "type": "json"},
                        timeout=60)
        if r.status_code == 200:
            body = r.json().get("StanReginCd")
            if body is None:
                raise SystemExit(f"API 오류 응답: {str(r.json())[:200]}")
            total = next(h["totalCount"] for h in body[0]["head"] if "totalCount" in h)
            return int(total), body[1].get("row", []) if len(body) > 1 else []
        time.sleep(3 * (attempt + 1))
    raise SystemExit(f"API 페이지 {number} 실패: HTTP {r.status_code}")


def main():
    key, session = service_key(), requests.Session()
    total, rows = page(session, key, 1)
    for number in range(2, (total + PAGE - 1) // PAGE + 1):
        _, more = page(session, key, number)
        rows.extend(more)
        time.sleep(0.2)
    codes = [r["region_cd"] for r in rows]
    if len(rows) != total or len(set(codes)) != total:
        raise SystemExit(f"받은 행 {len(rows)} · 고유 코드 {len(set(codes))} ≠ totalCount {total} — 저장하지 않는다")
    rows.sort(key=lambda r: r["region_cd"])
    out = ROOT / "reference" / f"bjd_code_api_{dt.date.today():%Y%m%d}.csv"
    if out.exists():
        raise SystemExit(f"{out.name} 이 이미 있다 — 기준표는 덮어쓰지 않는다")
    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS, extrasaction="ignore", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    print(f"{out.name}: {total:,}행 · {out.stat().st_size:,} bytes · sha256 {digest}")


if __name__ == "__main__":
    sys.exit(main())
