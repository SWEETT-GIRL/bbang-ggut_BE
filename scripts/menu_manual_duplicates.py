#!/usr/bin/env python3
"""2026-05-27 이전 수기 메뉴 중 (가게, 정규화 이름) 이 겹치는 그룹을 CSV 로 뽑는다.

배경: 운영 유니크 인덱스 uq_menu_store_normalized_name 은 created_at >= 2026-05-27 부분 인덱스라 그 이전
수기 데이터의 중복은 막지 못한다 (2026-09-08 실측 31그룹 62행). 그 가게에서 그 이름을 회원이 등록하면
MenuCommandService 가 한 건을 고르는데(오래된 것 우선), 어느 쪽을 남길지는 사람이 정해야 한다.

CSV 열: store_id, store_name, normalized_name, menu_id, name, price, created_at, has_image
  같은 그룹이 연속 행으로 나온다. keeper 를 고르고 나머지를 soft-delete 하면 된다 (dedup_menus.py 참고).

실행:
  scripts/.venv/bin/python scripts/menu_manual_duplicates.py
"""

import argparse
import csv
from pathlib import Path

import localdb

SQL = """
WITH dup AS (
  SELECT store_id, normalized_name
  FROM menu WHERE deleted_at IS NULL
  GROUP BY 1, 2 HAVING count(*) > 1
)
SELECT m.store_id, s.name AS store_name, m.normalized_name, m.id, m.name, m.price, m.created_at,
       EXISTS (SELECT 1 FROM menu_image_file i WHERE i.menu_id = m.id) AS has_image
FROM menu m
JOIN dup d ON d.store_id = m.store_id AND d.normalized_name = m.normalized_name
JOIN store s ON s.id = m.store_id
WHERE m.deleted_at IS NULL
ORDER BY m.store_id, m.normalized_name, m.created_at, m.id
"""


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("--out", default=str(Path(__file__).resolve().parent / "menu_manual_duplicates.csv"))
	args = parser.parse_args()

	with localdb.connect() as conn, conn.cursor() as cur:
		cur.execute(SQL)
		rows = cur.fetchall()

	with open(args.out, "w", newline="", encoding="utf-8-sig") as f:
		writer = csv.writer(f)
		writer.writerow(["store_id", "store_name", "normalized_name", "menu_id", "name", "price", "created_at", "has_image"])
		writer.writerows(rows)

	groups = len({(r[0], r[2]) for r in rows})
	print(f"중복 {groups}그룹 {len(rows)}행 → {args.out}")


if __name__ == "__main__":
	main()
