# -*- coding: utf-8 -*-
"""재동기화 2단계 — PENDING menu.add 를 정크 게이트로 나눠 거절(사유 기록)·승인한다. 8월 게이트(음료·0원·옵션·기타)와 같은 축.

승인 경로 그대로: PATCH /api/proposals/{id}/reject {"note"} / PATCH /api/proposals/{id}/approve → apply_log 선기록 → ServingWriter.insertMenu.
판정 규칙은 scripts/menu_add_junk_rules.py (judge). 재실행하면 남은 PENDING 만 이어서.

이름 충돌(사용자 등록 메뉴·운영 정규화상 동명 별개 상품)은 서버가 승인 도중 FAILED(MENU_CONFLICT, 운영 미반영)로
확정한다(v3 P1-1). 이 스크립트는 그 건을 충돌로 세기만 한다 — 예전처럼 반려로 바꾸거나 선기록 apply_log 를 지우지 않는다
(apply_log 는 시도의 감사 기록이고, FAILED 는 이미 닫힌 상태다).

사용:
  DATAOPS_DB_PORT=15434 python scripts/approve_menu_add_pending.py --dry-run --report <경로.tsv>        # 규칙별 건수 + 결정 TSV
  DATAOPS_DB_PORT=15434 python scripts/approve_menu_add_pending.py --server http://localhost:8090      # 거절 → 승인 순서로 실행 (writer 서버)
  DATAOPS_DB_PORT=15434 python scripts/approve_menu_add_pending.py --store-ids-file <목록.json> --max 20  # 범위 지정 시운전
"""
import argparse
import csv
import json
import os
import sys
import time
from collections import Counter

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import local_db  # noqa: E402 — 로컬 dataops DB. 이전에는 15433(개발 DB 터널)에 하드코딩돼 있었다
from menu_add_junk_rules import judge  # noqa: E402
from dataops_server import server_headers


def load_store_ids(path):
    """가게 id 목록 파일 — JSON 배열 또는 {"store_ids": [...]}"""
    data = json.load(open(path, encoding="utf-8"))
    return [int(x) for x in (data["store_ids"] if isinstance(data, dict) else data)]


def load_pending(store_ids=None, created_after=None):
    conn = local_db.connect()
    cur = conn.cursor()
    sql = "SELECT id, store_id, new_value FROM change_proposal WHERE status = 'PENDING' AND target LIKE 'menu.add.%%'"
    params = []
    if store_ids is not None:
        sql += " AND store_id = ANY(%s)"
        params.append(store_ids)
    if created_after:
        sql += " AND created_at >= %s"
        params.append(created_after)
    cur.execute(sql + " ORDER BY id", params)
    rows = cur.fetchall()
    conn.close()
    out = []
    for pid, sid, nv in rows:
        v = nv if isinstance(nv, dict) else json.loads(nv)
        j = judge(v.get("name"), v.get("price"))
        out.append({"id": pid, "store_id": sid, "name": v.get("name"), "price": v.get("price"), "decision": j[0] if j else "approve", "note": j[1] if j else ""})
    return out


def call(server, pid, decision, note):
    if decision == "approve":
        return requests.patch(f"{server}/api/proposals/{pid}/approve", headers=server_headers(), timeout=60)
    return requests.patch(f"{server}/api/proposals/{pid}/reject", headers=server_headers(), json={"note": note}, timeout=60)


CONFLICT_CODES = {"MENU_CONFLICTS_USER_MENU", "MENU_CONFLICTS_DISTINCT_NAME"}


def run(server, items, label, sleep, log):
    ok = failed = conflict = 0
    t0 = time.time()
    for i, it in enumerate(items, 1):
        try:
            r = call(server, it["id"], it["decision"], it["note"])
            if r.ok:
                ok += 1
            elif r.status_code == 409 and (r.json().get("code") in CONFLICT_CODES):
                # 서버가 FAILED(MENU_CONFLICT, 운영 미반영)로 이미 확정했다 — 세기만 한다
                conflict += 1
                if conflict <= 10:
                    log(f"[{label}] 충돌(FAILED) {it['id']} {it['name']!r} — {r.json().get('code')}")
            else:
                failed += 1
                if failed <= 20:
                    log(f"[{label}] 실패 {it['id']} {it['name']!r} — {r.status_code} {r.text[:160]}")
                if failed >= 50 and ok == 0:
                    raise SystemExit(f"[{label}] 초반 50건 연속 실패 — 서버/writer 설정 확인 후 재실행")
        except requests.RequestException as e:
            failed += 1
            log(f"[{label}] 예외 {it['id']}: {e}")
        if i % 1000 == 0:
            rate = i / (time.time() - t0)
            log(f"[{label}] {i}/{len(items)} — 성공 {ok} 실패 {failed} · {rate:.0f}/s · 남은 ≈{(len(items) - i) / rate / 60:.0f}분")
        time.sleep(sleep)
    log(f"[{label}] 종료 — 성공 {ok} / 충돌(FAILED·운영 미반영) {conflict} / 실패 {failed} · {(time.time() - t0) / 60:.1f}분")
    return failed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://localhost:8090")
    ap.add_argument("--store-ids-file", default=None, help="이 가게들의 제안만 (JSON 배열 또는 {\"store_ids\": [...]})")
    ap.add_argument("--created-after", default=None, help="이 시각 이후 만든 제안만 (주간 실행 — 그 주에 새로 생긴 것)")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--report", help="결정 TSV 경로")
    ap.add_argument("--phase", choices=["all", "reject", "approve"], default="all")
    ap.add_argument("--max", type=int, default=0, dest="max_items", help="각 단계 앞에서 N건만 (시운전)")
    ap.add_argument("--sleep", type=float, default=0.02)
    args = ap.parse_args()

    def log(s):
        print(s, flush=True)

    items = load_pending(load_store_ids(args.store_ids_file) if args.store_ids_file else None, args.created_after)
    cnt = Counter(it["decision"] for it in items)
    log(f"[menu.add] PENDING {len(items):,}건 → 승인 {cnt['approve']:,} · 거절 {len(items) - cnt['approve']:,} " +
        " ".join(f"{k}:{v:,}" for k, v in cnt.most_common() if k != "approve"))
    if args.report:
        os.makedirs(os.path.dirname(args.report) or ".", exist_ok=True)
        with open(args.report, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=["id", "store_id", "name", "price", "decision", "note"], delimiter="\t")
            w.writeheader()
            w.writerows(items)
        log(f"[menu.add] 결정 TSV: {args.report}")
    if args.dry_run:
        return 0

    health = requests.get(f"{args.server}/actuator/health", headers=server_headers(), timeout=5).json().get("status")
    if health != "UP":
        raise SystemExit(f"서버 상태 {health} — 중단")
    rejects = [it for it in items if it["decision"] != "approve"]
    approves = [it for it in items if it["decision"] == "approve"]
    if args.max_items:
        rejects, approves = rejects[: args.max_items], approves[: args.max_items]
    failed = 0
    if args.phase in ("all", "reject"):
        failed += run(args.server, rejects, "reject", args.sleep, log)
    if args.phase in ("all", "approve"):
        failed += run(args.server, approves, "approve", args.sleep, log)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
