# -*- coding: utf-8 -*-
"""빈칸 채움 PENDING 일괄 승인 — 운영에 값이 없던 필드(영업시간·인스타·전화)에 스냅샷 값이 처음 들어가는 proposal만.

승인 경로 그대로: PATCH /api/proposals/{id}/approve → apply_log 선기록 → ServingWriter → provenance (운영 DB 직접 UPDATE 없음).
대상 = status PENDING · target ∈ {store.business_hours, store.instagram_handle, store.phone} · old_value 가 비어 있음(NULL/""/[]).
변경(기존 값이 있던 것)·이름·주소·가격·menu.add/removed 는 여기서 다루지 않는다 (표본 검수 후 별도).

영업시간 24시간 게이트 (2026-09-27): 7요일 모두 00:00~23:59 인 영업시간 채움은 기본으로 뺀다. 주문 제작 케이크 가게가
"매일 00~24"를 연락 가능 시간으로 적는 경우가 많아(표본 745곳 중 이름에 무인·24·셀프가 있는 곳 3%), 그대로 쓰면 앱에 새벽에도
영업 중으로 보인다. --include-all-day 로만 포함한다.
영업시간 반영은 서버가 조건부로 한다 — 운영에 그새 영업시간이 생겼거나 DELETED 면 0행, FAILED(운영 미반영)로 확정된다.

사용:
  DATAOPS_DB_PORT=15434 python scripts/approve_fill_pending.py --dry-run                       # 대상 수만
  DATAOPS_DB_PORT=15434 python scripts/approve_fill_pending.py --server http://localhost:8090   # 실행 (재실행하면 남은 PENDING 만 이어서)
  DATAOPS_DB_PORT=15434 python scripts/approve_fill_pending.py --targets store.phone --max 20   # 시운전
  DATAOPS_DB_PORT=15434 python scripts/approve_fill_pending.py --targets store.business_hours \
      --created-after '2026-09-27 14:59' --max 20                                            # "매일" 수정분만 시운전
"""
import argparse
import sys
import time
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
import local_db  # noqa: E402 — 로컬 dataops DB(15434). 이전에는 15433(개발 DB 터널)에 하드코딩돼 있었다
from dataops_server import server_headers

FILL_TARGETS = ["store.business_hours", "store.instagram_handle", "store.phone"]
EMPTY_OLD = "(old_value IS NULL OR old_value::text IN ('null', '\"\"', '[]'))"
# 값 모양 게이트 — 전화는 지역/휴대/070·0507 또는 15xx/16xx/18xx 대표번호만 (예: "1433-00" 같은 깨진 값 제외), 인스타는 핸들 문자만, 영업시간은 JSON 배열
SHAPE_OK = ("(target <> 'store.phone' OR new_value::text ~ '^\"(0[0-9-]{8,13}|1[0-9]{3}-[0-9]{4})\"$') "
            "AND (target <> 'store.instagram_handle' OR new_value::text ~ '^\"[A-Za-z0-9._]{1,30}\"$') "
            "AND (target <> 'store.business_hours' OR jsonb_typeof(new_value) = 'array')")
# 7요일 모두 00:00~23:59 인 영업시간 — 기본 제외(위 설명)
ALL_DAY = ("(target = 'store.business_hours' AND jsonb_array_length(new_value) = 7 AND NOT EXISTS ("
           "SELECT 1 FROM jsonb_array_elements(new_value) d "
           "WHERE d->>'open' IS DISTINCT FROM '00:00' OR d->>'close' IS DISTINCT FROM '23:59'))")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://localhost:8090")
    ap.add_argument("--targets", default=",".join(FILL_TARGETS), help="쉼표 구분 target")
    ap.add_argument("--max", type=int, default=0, dest="max_items")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--sleep", type=float, default=0.02)
    ap.add_argument("--created-after", default=None, help="이 시각 이후 만든 제안만 (예: '2026-09-27 14:59')")
    ap.add_argument("--store-ids-file", default=None, help="이 가게들의 제안만 (JSON 배열 또는 {\"store_ids\": [...]})")
    ap.add_argument("--full-week-only", action="store_true",
                    help="영업시간 채움은 7요일이 다 있는 것만 — BE 는 빠진 요일을 휴무로 본다(StoreBusinessHourSyncService). "
                         "명절 주 스냅샷의 채움은 날짜 붙은 요일이 빠져 있다(09-27: 2,144건 중 1,384건)")
    ap.add_argument("--include-all-day", action="store_true", help="7요일 00:00~23:59 영업시간 채움도 포함한다")
    args = ap.parse_args()
    targets = [t.strip() for t in args.targets.split(",") if t.strip()]
    bad = [t for t in targets if t not in FILL_TARGETS]
    if bad:
        raise SystemExit(f"허용되지 않는 target: {bad} — 빈칸 채움 3종만")

    conn = local_db.connect()
    cur = conn.cursor()
    where = f"status = 'PENDING' AND target = ANY(%s) AND {EMPTY_OLD} AND {SHAPE_OK}"
    params = [targets]
    if args.created_after:
        where += " AND created_at >= %s"
        params.append(args.created_after)
    if args.store_ids_file:
        import json
        data = json.load(open(args.store_ids_file, encoding="utf-8"))
        where += " AND store_id = ANY(%s)"
        params.append([int(x) for x in (data["store_ids"] if isinstance(data, dict) else data)])
    cur.execute(f"SELECT count(*) FROM change_proposal WHERE {where} AND {ALL_DAY}", params)
    all_day = cur.fetchone()[0]
    if not args.include_all_day:
        where += f" AND NOT {ALL_DAY}"
    if args.full_week_only:
        where += " AND (target <> 'store.business_hours' OR jsonb_array_length(new_value) = 7)"
    cur.execute(f"SELECT id, target FROM change_proposal WHERE {where} ORDER BY target, id", params)
    rows = cur.fetchall()
    conn.close()
    print(f"[approve-fill] 24시간 영업시간 채움 {all_day}건 — {'포함' if args.include_all_day else '제외'}", flush=True)
    if args.max_items:
        rows = rows[: args.max_items]
    per = {t: sum(1 for _, x in rows if x == t) for t in targets}
    print(f"[approve-fill] 대상 {len(rows)}건 " + " · ".join(f"{t.split('.')[-1]} {n}" for t, n in per.items()), flush=True)
    if args.dry_run:
        return 0

    health = requests.get(f"{args.server}/actuator/health", headers=server_headers(), timeout=5).json().get("status")
    if health != "UP":
        raise SystemExit(f"서버 상태 {health} — 중단")
    approved = failed = 0
    t0 = time.time()
    for i, (pid, target) in enumerate(rows, 1):
        try:
            r = requests.patch(f"{args.server}/api/proposals/{pid}/approve", headers=server_headers(), timeout=60)
            if r.ok:
                approved += 1
            else:
                failed += 1
                if failed <= 20:
                    print(f"[approve-fill] 실패 {pid} {target} — {r.status_code} {r.text[:160]}", flush=True)
                if failed >= 50 and approved == 0:
                    raise SystemExit("초반 50건 연속 실패 — 서버/writer 설정 확인 후 재실행")
        except requests.RequestException as e:
            failed += 1
            print(f"[approve-fill] 예외 {pid}: {e}", flush=True)
        if i % 1000 == 0:
            rate = i / (time.time() - t0)
            print(f"[approve-fill] {i}/{len(rows)} — 승인 {approved} 실패 {failed} · {rate:.0f}/s · 남은 ≈{(len(rows) - i) / rate / 60:.0f}분", flush=True)
        time.sleep(args.sleep)
    print(f"[approve-fill] 종료 — 승인 {approved} / 실패 {failed} · {(time.time() - t0) / 60:.1f}분", flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
