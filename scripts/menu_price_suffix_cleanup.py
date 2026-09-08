"""옛 크롤러가 메뉴 이름 끝에 붙인 가격부 정리 — 1회성 운영 데이터 정리.

배경: scripts/naver_menu_scraper.py 의 옛 parse_menu_info 가 네이버 menuInfo 의 가격이 숫자 하나가 아니면
('변동가격(업주문의)', '4,300~6,200', '3500원', '시가') 그 문구를 이름에 남겼다 (2026-09-08 수정, PR #689).
그 이름은 메뉴 탭 DOM 의 깨끗한 이름·dataops 재수집 이름과 키가 달라 같은 메뉴가 여러 행이 됐다.
2026-09-08 실측: 활성 16,859건, 그중 14,155건에 깨끗한 쌍둥이 행이 있음(대부분 9월 재수집이 만든 새 행).

처리 규칙:
  A. 쌍둥이가 있는 행(OLD=가격부 이름, NEW=깨끗한 이름)
     - NEW 를 남긴다 (사진·가격을 더 갖고 있음). OLD 는 soft-delete.
     - OLD 의 좋아요·리뷰·사진(menu_image_file)은 NEW 로 옮긴다. 같은 회원의 좋아요/리뷰가 NEW 에도 있으면 OLD 쪽을 지운다.
     - NEW 의 가격·설명이 비어 있고 OLD 에 있으면 채운다.
     - NEW 의 카테고리가 폴백('기타'·'빵'·NULL)이고 OLD 가 실제 kind 면 OLD 의 kind 를 NEW 에 준다. 둘 다 폴백이면 안 건드린다.
  B. 쌍둥이가 없는 행: 이름에서 가격부를 떼고 normalized_name 을 다시 계산한다.
     - 같은 가게에서 여러 행이 같은 이름으로 모이면 id 가 가장 작은 행만 이름을 바꾸고 나머지는 A 와 같이 병합·soft-delete.
     - 떼고 나면 이름이 비는 행은 건드리지 않고 보고만 한다.

기본은 dry-run(변경 없음). 실제 적용은 --apply.
  scripts/.venv/bin/python scripts/menu_price_suffix_cleanup.py
  scripts/.venv/bin/python scripts/menu_price_suffix_cleanup.py --apply
"""

import argparse
import time

import localdb

# 공백 뒤 가격부만 — '플레인소금빵2' 같은 이름은 건드리지 않는다. naver_menu_scraper / dataops ReconcileService 와 같은 규칙.
PRICE_SUFFIX = (
	r"\s+(변동가격\(업주문의\)|변동가격|시가|[0-9]{1,3}(,[0-9]{3})*\s*~\s*[0-9]{1,3}(,[0-9]{3})*\s*원?"
	r"|[0-9]{1,3}(,[0-9]{3})+\s*원?|[0-9]+원)\s*$"
)
FALLBACK_KINDS = ("기타", "빵")


def build_plan(cur):
	started = time.time()
	cur.execute(
		"""
		CREATE TEMP TABLE sfx AS
		SELECT m.id, m.store_id, m.name, m.price, m.description, m.menu_category_id, m.created_at,
			trim(regexp_replace(m.name, %s, '')) AS clean_name,
			lower(regexp_replace(trim(regexp_replace(m.name, %s, '')), '[^[:alnum:]]+', '', 'g')) AS clean_key
		FROM menu m WHERE m.deleted_at IS NULL AND m.name ~ %s
		""",
		(PRICE_SUFFIX, PRICE_SUFFIX, PRICE_SUFFIX),
	)
	cur.execute("CREATE INDEX ON sfx (store_id, clean_key)")
	cur.execute("SELECT COUNT(*) FROM sfx")
	total = cur.fetchone()[0]

	# A. 쌍둥이 — 같은 가게의 활성 행 중 normalized_name 이 clean_key 인 것 (id 가장 작은 것)
	cur.execute(
		"""
		CREATE TEMP TABLE pairs AS
		SELECT s.id AS old_id, s.store_id, t.id AS new_id
		FROM sfx s
		JOIN LATERAL (
			SELECT t.id FROM menu t
			WHERE t.store_id = s.store_id AND t.deleted_at IS NULL AND t.id <> s.id AND t.normalized_name = s.clean_key
			ORDER BY t.id LIMIT 1
		) t ON true
		"""
	)
	# B. 쌍둥이 없음 — 같은 가게·같은 clean_key 끼리는 id 최소를 keeper 로, 나머지는 keeper 로 병합
	cur.execute(
		"""
		CREATE TEMP TABLE renames AS
		SELECT s.id, s.store_id, s.clean_name, s.clean_key,
			MIN(s.id) OVER (PARTITION BY s.store_id, s.clean_key) AS keeper_id
		FROM sfx s
		WHERE s.id NOT IN (SELECT old_id FROM pairs) AND s.clean_key <> ''
		"""
	)
	cur.execute(
		"""
		INSERT INTO pairs (old_id, store_id, new_id)
		SELECT id, store_id, keeper_id FROM renames WHERE id <> keeper_id
		"""
	)
	cur.execute("SELECT COUNT(*) FROM sfx WHERE clean_key = ''")
	empty = cur.fetchone()[0]

	# 병합에 필요한 신호를 한 번에 집계 (per-row EXISTS 는 menu_image_file 에 menu_id 인덱스가 없어 매우 느리다)
	cur.execute(
		"""
		CREATE TEMP TABLE pair_signals AS
		SELECT p.old_id, p.new_id,
			COALESCE(oi.cnt, 0) AS old_images, COALESCE(ol.cnt, 0) AS old_likes, COALESCE(orv.cnt, 0) AS old_reviews,
			o.price AS old_price, o.description AS old_desc, oc.name AS old_kind,
			n.price AS new_price, n.description AS new_desc, nc.name AS new_kind
		FROM pairs p
		JOIN menu o ON o.id = p.old_id
		JOIN menu n ON n.id = p.new_id
		LEFT JOIN menu_category oc ON oc.id = o.menu_category_id
		LEFT JOIN menu_category nc ON nc.id = n.menu_category_id
		LEFT JOIN (SELECT menu_id, COUNT(*) cnt FROM menu_image_file GROUP BY menu_id) oi ON oi.menu_id = p.old_id
		LEFT JOIN (SELECT menu_id, COUNT(*) cnt FROM menu_like GROUP BY menu_id) ol ON ol.menu_id = p.old_id
		LEFT JOIN (SELECT menu_id, COUNT(*) cnt FROM review WHERE deleted_at IS NULL GROUP BY menu_id) orv ON orv.menu_id = p.old_id
		"""
	)
	cur.execute(
		"""
		SELECT COUNT(*),
			COUNT(*) FILTER (WHERE old_images > 0), SUM(old_images),
			COUNT(*) FILTER (WHERE old_likes > 0), SUM(old_likes),
			COUNT(*) FILTER (WHERE old_reviews > 0), SUM(old_reviews),
			COUNT(*) FILTER (WHERE new_price IS NULL AND old_price IS NOT NULL),
			COUNT(*) FILTER (WHERE COALESCE(new_desc, '') = '' AND COALESCE(old_desc, '') <> ''),
			COUNT(*) FILTER (WHERE (new_kind IS NULL OR new_kind IN %s) AND old_kind IS NOT NULL AND old_kind NOT IN %s)
		FROM pair_signals
		""",
		(FALLBACK_KINDS, FALLBACK_KINDS),
	)
	stats = cur.fetchone()
	cur.execute("SELECT COUNT(*) FROM renames WHERE id = keeper_id")
	rename_count = cur.fetchone()[0]
	return {
		"total": total,
		"pairs": stats[0],
		"images_rows": stats[1], "images": stats[2] or 0,
		"likes_rows": stats[3], "likes": stats[4] or 0,
		"reviews_rows": stats[5], "reviews": stats[6] or 0,
		"fill_price": stats[7], "fill_desc": stats[8], "copy_kind": stats[9],
		"renames": rename_count, "empty": empty,
		"seconds": round(time.time() - started),
	}


def print_plan(plan):
	print(f"가격부 붙은 활성 이름: {plan['total']}건 (집계 {plan['seconds']}초)")
	print(f"  A. 쌍둥이/중복으로 병합 후 soft-delete: {plan['pairs']}건")
	print(f"     - 사진 이동 {plan['images']}장({plan['images_rows']}행), 좋아요 이동 {plan['likes']}건({plan['likes_rows']}행), "
		f"리뷰 이동 {plan['reviews']}건({plan['reviews_rows']}행)")
	print(f"     - 가격 채움 {plan['fill_price']}건, 설명 채움 {plan['fill_desc']}건, 폴백 카테고리에 옛 kind 부여 {plan['copy_kind']}건")
	print(f"  B. 이름만 정리(가격부 제거): {plan['renames']}건")
	print(f"  건너뜀(떼면 이름이 빔): {plan['empty']}건")


def apply(conn, cur):
	# A. 병합
	cur.execute(
		"""
		UPDATE menu_image_file f SET menu_id = p.new_id
		FROM pair_signals p WHERE f.menu_id = p.old_id
		  AND NOT EXISTS (SELECT 1 FROM menu_image_file g WHERE g.menu_id = p.new_id AND g.source_url IS NOT NULL AND g.source_url = f.source_url)
		"""
	)
	moved_images = cur.rowcount
	cur.execute(
		"""
		DELETE FROM menu_like l USING pair_signals p
		WHERE l.menu_id = p.old_id AND EXISTS (SELECT 1 FROM menu_like k WHERE k.menu_id = p.new_id AND k.member_id = l.member_id)
		"""
	)
	dropped_likes = cur.rowcount
	cur.execute("UPDATE menu_like l SET menu_id = p.new_id FROM pair_signals p WHERE l.menu_id = p.old_id")
	moved_likes = cur.rowcount
	cur.execute(
		"""
		UPDATE review r SET deleted_at = now()
		FROM pair_signals p
		WHERE r.menu_id = p.old_id AND r.deleted_at IS NULL
		  AND EXISTS (SELECT 1 FROM review k WHERE k.menu_id = p.new_id AND k.member_id = r.member_id AND k.deleted_at IS NULL)
		"""
	)
	dropped_reviews = cur.rowcount
	cur.execute("UPDATE review r SET menu_id = p.new_id FROM pair_signals p WHERE r.menu_id = p.old_id")
	moved_reviews = cur.rowcount
	cur.execute(
		"""
		UPDATE menu n SET like_count = (SELECT COUNT(*) FROM menu_like l WHERE l.menu_id = n.id), updated_at = now()
		WHERE n.id IN (SELECT new_id FROM pair_signals WHERE old_likes > 0)
		"""
	)
	cur.execute(
		"""
		UPDATE menu n SET price = p.old_price, updated_at = now()
		FROM pair_signals p WHERE n.id = p.new_id AND n.price IS NULL AND p.old_price IS NOT NULL
		"""
	)
	filled_price = cur.rowcount
	cur.execute(
		"""
		UPDATE menu n SET description = p.old_desc, updated_at = now()
		FROM pair_signals p WHERE n.id = p.new_id AND COALESCE(n.description, '') = '' AND COALESCE(p.old_desc, '') <> ''
		"""
	)
	filled_desc = cur.rowcount
	cur.execute(
		"""
		UPDATE menu n SET menu_category_id = (SELECT id FROM menu_category WHERE name = p.old_kind), updated_at = now()
		FROM pair_signals p
		WHERE n.id = p.new_id AND (p.new_kind IS NULL OR p.new_kind IN %s) AND p.old_kind IS NOT NULL AND p.old_kind NOT IN %s
		""",
		(FALLBACK_KINDS, FALLBACK_KINDS),
	)
	copied_kind = cur.rowcount
	cur.execute(
		"UPDATE menu o SET deleted_at = now(), updated_at = now() FROM pair_signals p WHERE o.id = p.old_id AND o.deleted_at IS NULL"
	)
	deleted = cur.rowcount

	# B. 이름 정리 — keeper 만. normalized_name 은 유니크 인덱스와 같은 식으로 다시 계산
	cur.execute(
		"""
		UPDATE menu m SET name = r.clean_name,
			normalized_name = lower(regexp_replace(r.clean_name, '[^[:alnum:]]+', '', 'g')),
			updated_at = now()
		FROM renames r WHERE m.id = r.id AND r.id = r.keeper_id AND m.deleted_at IS NULL
		"""
	)
	renamed = cur.rowcount
	conn.commit()
	print(
		f"적용: soft-delete {deleted}, 사진 이동 {moved_images}, 좋아요 이동 {moved_likes}(중복 제거 {dropped_likes}), "
		f"리뷰 이동 {moved_reviews}(중복 제거 {dropped_reviews}), 가격 채움 {filled_price}, 설명 채움 {filled_desc}, "
		f"kind 부여 {copied_kind}, 이름 정리 {renamed}"
	)


def verify(cur):
	cur.execute("SELECT COUNT(*) FROM menu WHERE deleted_at IS NULL AND name ~ %s", (PRICE_SUFFIX,))
	print("남은 가격부 이름(활성):", cur.fetchone()[0])
	cur.execute(
		"SELECT COUNT(*) FROM (SELECT 1 FROM menu WHERE deleted_at IS NULL GROUP BY store_id, normalized_name HAVING COUNT(*) > 1) t"
	)
	print("활성 (가게, 정규화 이름) 중복 그룹:", cur.fetchone()[0])


def main():
	parser = argparse.ArgumentParser()
	parser.add_argument("--apply", action="store_true", help="실제 적용 (기본 dry-run)")
	args = parser.parse_args()

	conn = localdb.connect()
	cur = conn.cursor()
	plan = build_plan(cur)
	print_plan(plan)
	if not args.apply:
		print("\n(dry-run — 변경 없음. 적용은 --apply)")
		conn.rollback()
		return
	try:
		apply(conn, cur)
	except Exception:
		conn.rollback()
		raise
	verify(cur)


if __name__ == "__main__":
	main()
