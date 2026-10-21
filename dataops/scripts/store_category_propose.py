"""가게 분류(store.category) 제안 배치 — 네이버 카테고리 → BE StoreCategory 코드 → change_proposal.

경로는 store.region 과 같다(정규 경로):
  이 스크립트 --propose  →  change_proposal(target=store.category, source=NAVER, PENDING)
  approve_stage3_pending.py --families category (writer 서버 :8091)  →  apply_log 선기록  →  ServingWriter.updateStoreCategory

값의 정본은 BE StoreCategory.java(#730)이고 store_category_map.py 는 그 생성물이다(store_category_sync.py). 네이버 표기가 바뀌면
BE enum 을 고치고 생성표를 다시 만든 뒤 이 스크립트를 돌리면, 코드가 바뀐 가게만 다시 제안된다 — 첫 적재와 이후 갱신이 같은 길이다.

--target 은 "현재값을 어디서 읽나"다. 제안은 언제나 로컬 change_proposal 에 쌓이고, 실제 쓰기는 승인 서버의 PROD_DB_URL 이 가리키는
DB 에 간다. 둘이 같은 DB 여야 한다(개발 리허설이면 둘 다 개발).

운영은 BE #730 이 배포된 뒤에만 — 옛 BE(enum WESTERN 하나)는 BAKERY 를 읽는 순간 JPA 가 터진다. store.category 컬럼 주석으로 확인한다.

사용
    python scripts/store_category_propose.py --env ..\bbang-ggut_dataops_BE-localdata\.env --target dev --report
    python scripts/store_category_propose.py --env ... --target dev --propose [--limit 100]
"""
import argparse
import json
import os
import sys
from collections import Counter

import psycopg2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from store_category_map import BE_COMMIT, CODES, category_of  # noqa: E402  (생성표)
from store_tags import local_csv, read_env  # noqa: E402

TARGETS = {
    "dev": {"port": 15434, "user": "sweetgirl", "password_key": "PROD_DB_PASSWORD"},
    "prod": {"port": 15432, "user": None, "password_key": "PROD_DB_PASSWORD"},   # user 는 PROD_DB_USERNAME(reader)
}
TARGET_KEY = "store.category"


def read_target(target, env_path):
    t = TARGETS[target]
    user = t["user"] or read_env(env_path, "PROD_DB_USERNAME")
    conn = psycopg2.connect(host="127.0.0.1", port=t["port"], user=user, password=read_env(env_path, t["password_key"]), dbname="soboro",
                            connect_timeout=10, options="-c default_transaction_read_only=on", application_name="store_category_propose")
    cur = conn.cursor()
    cur.execute("SELECT col_description('store'::regclass, ordinal_position) FROM information_schema.columns "
                "WHERE table_name = 'store' AND column_name = 'category'")
    comment = (cur.fetchone() or [None])[0] or ""
    if "StoreCategory" not in comment:
        raise SystemExit(f"{target}: store.category 가 아직 #730 모양이 아니다(컬럼 주석 없음). BE 배포 뒤에 돌릴 것 — 옛 BE 는 새 코드를 읽으면 터진다")
    cur.execute("SELECT id, status, category FROM store")
    rows = {sid: (status, cat) for sid, status, cat in cur.fetchall()}
    conn.close()
    return rows


def plan(target_rows):
    sig = local_csv("SELECT store_id, snapshot_id, category FROM store_signal WHERE category IS NOT NULL AND category <> '';")
    changes, same, not_in_target, inactive = [], 0, 0, 0
    for r in sig:
        sid = int(r["store_id"])
        if sid not in target_rows:
            not_in_target += 1
            continue
        status, current = target_rows[sid]
        if status != "ACTIVE":
            inactive += 1
            continue
        code = category_of(r["category"])
        if code == current:
            same += 1
            continue
        changes.append((sid, current, code, int(r["snapshot_id"]) if r["snapshot_id"] else None, r["category"]))
    return changes, same, not_in_target, inactive, len(sig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", required=True)
    ap.add_argument("--target", choices=TARGETS, required=True)
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--propose", action="store_true")
    ap.add_argument("--limit", type=int, default=0, help="리허설용 — 앞에서 N건만 제안")
    args = ap.parse_args()
    if not (args.report or args.propose):
        ap.error("--report 나 --propose 중 하나")

    target_rows = read_target(args.target, args.env)
    changes, same, not_in_target, inactive, n_sig = plan(target_rows)
    active_total = sum(1 for s, _ in target_rows.values() if s == "ACTIVE")
    print(f"[category] 생성표 BE@{BE_COMMIT} · 코드 {len(CODES)}종")
    print(f"[category] {args.target} ACTIVE {active_total:,} · 신호 카테고리 {n_sig:,} — 대상에 없음 {not_in_target:,} · ACTIVE 아님 {inactive:,} · 이미 같음 {same:,} · 제안 {len(changes):,}")
    dist = Counter(code for _, _, code, _, _ in changes)
    print("[category] 제안 코드 분포:", ", ".join(f"{c} {n:,}" for c, n in dist.most_common()))
    old = Counter(cur for _, cur, _, _, _ in changes)
    print("[category] 현재값:", ", ".join(f"{k or 'NULL'} {n:,}" for k, n in old.most_common(5)))
    for sid, cur, code, _, raw in changes[:5]:
        print(f"   예: store {sid} {cur or 'NULL'} → {code}  (네이버 '{raw}')")
    if not args.propose:
        return

    todo = changes[: args.limit] if args.limit else changes
    conn = psycopg2.connect(host="localhost", port=15433, dbname="dataops", user="dataops", password="dataops")
    cur = conn.cursor()
    cur.execute("SELECT store_id FROM change_proposal WHERE target = %s "
                "AND status IN ('PENDING','APPLYING')", (TARGET_KEY,))
    pending = {r[0] for r in cur.fetchall()}
    n = 0
    for sid, current, code, snapshot_id, _ in todo:
        if sid in pending:
            continue
        # producer/target_family/policy_version = 재판정 범위 표지 (v3 P1-2) — 네이버 스냅샷 재판정이
        # 이 제안을 자기 세대로 오인해 정리하지 않도록 만든 주체를 밝힌다
        cur.execute("INSERT INTO change_proposal (store_id, target, old_value, new_value, source, snapshot_id, "
                    "status, created_at, updated_at, producer, target_family, policy_version) "
                    "VALUES (%s, %s, %s, %s, 'NAVER', %s, 'PENDING', now(), now(), "
                    "'STORE_CATEGORY_PROPOSE', 'STORE_CATEGORY', 'store_category_propose-2026-09-26')",
                    (sid, TARGET_KEY, json.dumps(current), json.dumps(code), snapshot_id))
        n += 1
    conn.commit()
    conn.close()
    print(f"[category] PENDING 생성 {n:,}건 (이미 PENDING {len(todo) - n:,} 건너뜀) → approve_stage3_pending.py --families category")


if __name__ == "__main__":
    main()
