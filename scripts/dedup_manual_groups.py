"""2026-05-27 이전 수기 메뉴의 (가게, 정규화 이름) 중복 31그룹 정리 — 1회성.

dedup_menus.py 는 cutoff 이전 수기 데이터를 일부러 건드리지 않는다. 이 스크립트는 그 31그룹만 다룬다.
keeper 규칙(사용자 승인): 사진 > 설명 > 가격 > id 작은 것. 나머지 1행을 soft-delete 하고,
그 행의 리뷰·좋아요는 keeper 로 옮긴다. keeper 의 빈 가격·설명은 지워지는 행의 값으로 채운다.
menu_image_file 은 건드리지 않는다(삭제 행에 붙은 채로 둠, S3 그대로).

기본은 dry-run. 실제 적용은 --apply.
  scripts/.venv/bin/python scripts/dedup_manual_groups.py
  scripts/.venv/bin/python scripts/dedup_manual_groups.py --apply
"""

import argparse
from collections import defaultdict

import localdb

# 2026-09-08 승인된 keeper 목록 — 규칙 계산 결과가 이것과 다르면 멈춘다.
APPROVED_KEEPERS = {
	3293, 3292, 3354, 9550, 4888, 3710, 3801, 2184, 9572, 2449, 9246, 2522, 2617, 2630, 2810, 4317,
	2891, 4547, 4546, 5378, 5844, 9071, 7130, 7349, 7350, 7560, 7607, 9469, 9466, 9507, 9534,
}

GROUP_SQL = """
WITH dup AS (
	SELECT store_id, normalized_name FROM menu
	WHERE deleted_at IS NULL AND created_at < '2026-05-27'
	GROUP BY store_id, normalized_name HAVING COUNT(*) > 1
)
SELECT m.store_id, s.name, m.normalized_name, m.id, m.name, m.price, COALESCE(m.description, ''),
	(SELECT COUNT(*) FROM menu_image_file x WHERE x.menu_id = m.id),
	(SELECT COUNT(*) FROM review x WHERE x.menu_id = m.id),
	(SELECT COUNT(*) FROM menu_like x WHERE x.menu_id = m.id),
	(SELECT COUNT(*) FROM menu_contribution_report x WHERE x.menu_id = m.id),
	(SELECT COUNT(*) FROM recommend_menu x WHERE x.menu_id = m.id)
FROM menu m
JOIN dup d ON d.store_id = m.store_id AND d.normalized_name = m.normalized_name
JOIN store s ON s.id = m.store_id
WHERE m.deleted_at IS NULL
ORDER BY m.store_id, m.normalized_name, m.id
"""


def build_plan(cur):
	cur.execute(GROUP_SQL)
	groups = defaultdict(list)
	for row in cur.fetchall():
		groups[(row[0], row[1], row[2])].append(row)
	plan = []
	for key, rows in groups.items():
		keeper = sorted(rows, key=lambda r: (-(r[7] > 0), -(r[6] != ""), -(r[5] is not None), r[3]))[0]
		losers = [r for r in rows if r[3] != keeper[3]]
		if len(losers) != 1:
			raise SystemExit(f"그룹 {key} 행이 2개가 아님: {[r[3] for r in rows]}")
		loser = losers[0]
		if loser[10] or loser[11]:
			raise SystemExit(f"지울 행 {loser[3]} 에 제보/추천이 걸려 있음 — 수동 확인 필요")
		merge = {}
		if keeper[5] is None and loser[5] is not None:
			merge["price"] = loser[5]
		if keeper[6] == "" and loser[6] != "":
			merge["description"] = loser[6]
		plan.append((key[1], keeper, loser, merge))
	return plan


def print_plan(plan):
	print("store | keep | drop | actions")
	for store, keeper, loser, merge in plan:
		actions = [f"soft-delete {loser[3]}"]
		if merge:
			actions.append("keeper 채움 " + ", ".join(f"{k}={str(v)[:20]}" for k, v in merge.items()))
		if loser[8]:
			actions.append(f"review {loser[8]}건 {loser[3]}→{keeper[3]}")
		if loser[9]:
			actions.append(f"menu_like {loser[9]}건 {loser[3]}→{keeper[3]}")
		print(f"{store} | {keeper[3]} {keeper[4].strip()} | {loser[3]} {loser[4].strip()} | " + "; ".join(actions))


def apply(conn, cur, plan):
	deleted = reviews = likes = merges = 0
	for _, keeper, loser, merge in plan:
		if merge:
			sets = ", ".join(f"{col} = %s" for col in merge)
			cur.execute(f"UPDATE menu SET {sets}, updated_at = now() WHERE id = %s", (*merge.values(), keeper[3]))
			merges += cur.rowcount
		cur.execute("UPDATE review SET menu_id = %s WHERE menu_id = %s", (keeper[3], loser[3]))
		reviews += cur.rowcount
		cur.execute("UPDATE menu_like SET menu_id = %s WHERE menu_id = %s", (keeper[3], loser[3]))
		moved = cur.rowcount
		likes += moved
		if moved:
			cur.execute(
				"UPDATE menu SET like_count = (SELECT COUNT(*) FROM menu_like WHERE menu_id = %s) WHERE id = %s",
				(keeper[3], keeper[3]),
			)
		cur.execute("UPDATE menu SET deleted_at = now(), updated_at = now() WHERE id = %s AND deleted_at IS NULL", (loser[3],))
		if cur.rowcount != 1:
			raise RuntimeError(f"soft-delete rowcount {cur.rowcount} for {loser[3]}")
		deleted += 1
	conn.commit()
	print(f"applied: deleted {deleted}, reviews moved {reviews}, likes moved {likes}, keeper merges {merges}")


def verify(cur):
	cur.execute(
		"SELECT COUNT(*) FROM (SELECT 1 FROM menu WHERE deleted_at IS NULL AND created_at < '2026-05-27' "
		"GROUP BY store_id, normalized_name HAVING COUNT(*) > 1) t"
	)
	print("pre-cutoff dup groups left:", cur.fetchone()[0])
	cur.execute("SELECT menu_id, COUNT(*) FROM review WHERE menu_id IN (7130, 7134) GROUP BY 1 ORDER BY 1")
	print("reviews on 7130/7134:", cur.fetchall())
	cur.execute("SELECT id, price FROM menu WHERE id = 9550")
	print("keeper 9550 price:", cur.fetchone())


def main():
	parser = argparse.ArgumentParser()
	parser.add_argument("--apply", action="store_true", help="실제 적용 (기본 dry-run)")
	args = parser.parse_args()

	conn = localdb.connect()
	cur = conn.cursor()
	plan = build_plan(cur)
	keepers = {p[1][3] for p in plan}
	if len(plan) != 31 or keepers != APPROVED_KEEPERS:
		print_plan(plan)
		raise SystemExit(f"승인된 keeper 표와 다름 — 그룹 {len(plan)}개, 차이 {sorted(keepers ^ APPROVED_KEEPERS)}")
	print_plan(plan)
	if not args.apply:
		print("\n(dry-run — 변경 없음. 적용은 --apply)")
		conn.rollback()
		return
	try:
		apply(conn, cur, plan)
	except Exception:
		conn.rollback()
		raise
	verify(cur)


if __name__ == "__main__":
	main()
