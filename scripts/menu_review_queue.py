#!/usr/bin/env python3
"""메뉴 AI 분류 검수 큐 — 3회 답이 갈려 카테고리를 못 받은 메뉴를 CSV 로 뽑는다.

대상: menu_category_id IS NULL AND classified_at IS NOT NULL AND deleted_at IS NULL
      (스케줄러가 시도했지만 확정 못 한 것. 같은 이름은 캐시(menu_name_classification)에 kind NULL 로 남아
       다시 묻지 않으므로, 여기서 사람이 정하지 않으면 계속 미분류다)

실측 기준 전체의 5~8%. 화면을 만들지 않는 이유는 월 12만 건 유입에 이 비율이면 사람이 전수 검수할 양이 아니라서 —
CSV 로 뽑아 훑고, 패턴이 보이면 프롬프트나 끝단어 표를 고친다.

CSV 열: menu_id, store_id, name, normalized_name, classified_at, cached_confidence, same_name_count
  same_name_count — 같은 정규화 이름의 활성 메뉴 수. 크면 그 이름 하나만 정해도 여러 건이 풀린다.

실행:
  scripts/.venv/bin/python scripts/menu_review_queue.py                 # scripts/menu_review_queue.csv
  scripts/.venv/bin/python scripts/menu_review_queue.py --out /tmp/q.csv --limit 500
"""

import argparse
import csv
from pathlib import Path

import localdb

SQL = """
SELECT m.id, m.store_id, m.name, m.normalized_name, m.classified_at, c.confidence,
       (SELECT count(*) FROM menu x WHERE x.normalized_name = m.normalized_name AND x.deleted_at IS NULL) AS same_name_count
FROM menu m
LEFT JOIN menu_name_classification c ON c.normalized_name = m.normalized_name
WHERE m.menu_category_id IS NULL AND m.classified_at IS NOT NULL AND m.deleted_at IS NULL
ORDER BY same_name_count DESC, m.classified_at DESC
LIMIT %s
"""


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("--out", default=str(Path(__file__).resolve().parent / "menu_review_queue.csv"))
	parser.add_argument("--limit", type=int, default=2000)
	args = parser.parse_args()

	with localdb.connect() as conn, conn.cursor() as cur:
		cur.execute(SQL, (args.limit,))
		rows = cur.fetchall()

	with open(args.out, "w", newline="", encoding="utf-8-sig") as f:
		writer = csv.writer(f)
		writer.writerow(["menu_id", "store_id", "name", "normalized_name", "classified_at", "cached_confidence", "same_name_count"])
		writer.writerows(rows)

	distinct = len({r[3] for r in rows})
	print(f"검수 대상 {len(rows)}건 (고유 이름 {distinct}개) → {args.out}")
	if rows:
		print("같은 이름이 많은 것부터:")
		for r in rows[:10]:
			print(f"  {r[6]:>3}건  {r[2]}")


if __name__ == "__main__":
	main()
