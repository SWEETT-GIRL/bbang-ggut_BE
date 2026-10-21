# -*- coding: utf-8 -*-
"""일회성 복원 — 운영 menu #676008 (가게 47336 '✔️답례세트②') 가격 7500 → 4500.

원인: 2026-09-07 00:36 menu.add 승인에서 '답례세트③~⑦'(제안 1000770~1000774)이 운영 정규화 키가 같은 ②에 병합되며
가격이 4500→5000→5500→5500→8000→7500 으로 덮였다. 그 뒤 upsert 를 "이름까지 같을 때만 갱신"으로 고쳤고(BLOCKED_DISTINCT_NAME),
이 스크립트는 덮이기 전 값으로 되돌린다. 승인 경로와 같은 감사 규칙: 로컬 apply_log 선기록 → writer 로 UPDATE(가격이 아직 7500 일 때만).

실행 (사용자가 직접):  python scripts/oneoff_restore_menu_676008_price.py --env ../bbang-ggut_dataops_BE-localdata/.env
"""
import argparse
import os
import sys

import psycopg2
from psycopg2.extras import Json

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from store_tags import read_env  # noqa: E402

MENU_ID, STORE_ID, WRONG_PRICE, RIGHT_PRICE = 676008, 47336, 7500, 4500


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", required=True)
    args = ap.parse_args()
    local = psycopg2.connect(host="localhost", port=15433, dbname="dataops", user="dataops", password="dataops")
    prod = psycopg2.connect(host="localhost", port=15432, dbname="soboro", user="dataops_writer",
                            password=os.environ.get("DATAOPS_WRITER_PASSWORD") or read_env(args.env, "DATAOPS_WRITER_PASSWORD"), sslmode="require")
    try:
        with prod.cursor() as pc:
            pc.execute("SELECT name, price FROM menu WHERE id = %s AND store_id = %s", (MENU_ID, STORE_ID))
            name, price = pc.fetchone()
            if price != WRONG_PRICE:
                print(f"건너뜀 — #{MENU_ID} {name} 현재 가격 {price} (복원 대상 {WRONG_PRICE} 아님)")
                return 0
            with local.cursor() as lc:
                lc.execute("INSERT INTO apply_log (store_id, target, old_value, new_value, origin, applied_at) "
                           "VALUES (%s, %s, %s, %s, 'PROPOSAL', now())",
                           (STORE_ID, f"menu.{MENU_ID}.price", Json(WRONG_PRICE),
                            Json({"price": RIGHT_PRICE, "reason": "menu.add 병합(제안 1000770~1000774)이 별개 상품 가격으로 덮은 것을 원래 값으로 복원"})))
            pc.execute("UPDATE menu SET price = %s, updated_at = now() WHERE id = %s AND price = %s", (RIGHT_PRICE, MENU_ID, WRONG_PRICE))
            assert pc.rowcount == 1, pc.rowcount
        local.commit()
        prod.commit()
        print(f"복원 완료 — #{MENU_ID} {name} {WRONG_PRICE} → {RIGHT_PRICE} (apply_log 선기록)")
        return 0
    except Exception:
        local.rollback()
        prod.rollback()
        raise
    finally:
        local.close()
        prod.close()


if __name__ == "__main__":
    sys.exit(main())
