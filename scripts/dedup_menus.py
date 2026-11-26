#!/usr/bin/env python3
"""기존 메뉴 중복 정리 (1회성 운영 데이터 정리).

배경: 크롤러 재실행/동시실행으로 같은 가게에 정규화 이름이 같은 메뉴가 중복 insert 됐다.
정규화는 백엔드 MenuNameNormalizer 와 동일(공백·특수문자 제거 + lowercase, 한글/영문/숫자 유지).

처리 규칙(사용자 승인):
  - cutoff(기본 2026-05-27) 이전 created_at 행은 팀원 수기 작업이라 절대 건드리지 않음
    (삭제·수정 모두 X). cutoff 이후 행만 정리 대상.
  - 그룹 내 가격이 전부 동일 OR (null + 단일 값) → 자동 정리:
      · keeper = pre-cutoff(수기) 행이 있으면 그걸 보존(미수정), 없으면 사진有>설명有>가격有>id작은 순
      · keeper 가 cutoff 이후 행일 때만 빈 가격·설명을 그룹에서 채움(merge)
      · non-keeper 중 cutoff 이후 행만 soft-delete + 그 menu_image_file 행 삭제(S3 는 reconcile)
  - 그룹 내 가격이 서로 다른 실제 값 → 자동 정리하지 않고 CSV 로 출력(수동 검토)
  - cutoff 이전끼리 중복이 남는 그룹은 인덱스 생성을 막으므로 별도 집계해 경고

기본은 dry-run(변경 없음). 실제 적용은 --apply.
DB 접속 정보는 src/main/resources/application-local.properties 에서 읽음.

실행:
  scripts/.venv/bin/python scripts/dedup_menus.py            # dry-run 리포트
  scripts/.venv/bin/python scripts/dedup_menus.py --apply    # 실제 정리
"""

import argparse
import csv
from collections import defaultdict
from pathlib import Path

import psycopg2

from storage_client import s3_client

REVIEW_CSV = Path(__file__).resolve().parent / "menu_dedup_manual_review.csv"
CUTOFF = "2026-05-27"  # 이 날짜 이전 created_at 은 수기 데이터 — 건드리지 않음


def jnorm(s: str) -> str:
	"""MenuNameNormalizer 동일: 공백·특수문자 제거 + lowercase (한글/영문/숫자 유지)."""
	return "".join(c for c in (s or "") if c.isalnum()).lower()


def load_props() -> dict:
	props = (Path(__file__).resolve().parent.parent
			/ "src/main/resources/application-local.properties")
	cfg = {}
	for line in props.read_text(encoding="utf-8").splitlines():
		line = line.strip()
		if "=" in line and not line.startswith("#"):
			k, _, v = line.partition("=")
			cfg[k.strip()] = v.strip()
	return cfg


def db_config(cfg: dict) -> dict:
	url = cfg["spring.datasource.url"]  # jdbc:postgresql://host:port/db
	rest = url.split("//", 1)[1]
	hostport, _, dbname = rest.partition("/")
	host, _, port = hostport.partition(":")
	return {
		"host": host,
		"port": int(port or 5432),
		"dbname": dbname,
		"user": cfg["spring.datasource.username"],
		"password": cfg["spring.datasource.password"],
	}


def keeper_score(row: dict) -> tuple:
	# 사진 > 설명 > 가격 > id작은(오래된, 안정) 순으로 keeper 선정.
	return (
		1 if row["has_img"] else 0,
		1 if (row["desc"] and row["desc"].strip()) else 0,
		1 if row["price"] is not None else 0,
		-row["id"],
	)


def main() -> None:
	parser = argparse.ArgumentParser(description="메뉴 중복 정리")
	parser.add_argument("--apply", action="store_true", help="실제 DB 변경 (기본은 dry-run)")
	parser.add_argument("--since", default=CUTOFF,
		help=f"이 날짜(created_at) 이후 행만 정리 — 이전은 수기 데이터라 보존 (기본 {CUTOFF})")
	args = parser.parse_args()
	since = args.since

	cfg = load_props()
	conn = psycopg2.connect(**db_config(cfg), connect_timeout=10)
	conn.autocommit = False
	cur = conn.cursor()
	s3 = s3_client(cfg)

	cur.execute("SELECT id, store_id, name, price, description, created_at FROM menu WHERE deleted_at IS NULL")
	rows = cur.fetchall()
	cur.execute("SELECT DISTINCT menu_id FROM menu_image_file")
	has_img = {r[0] for r in cur.fetchall()}

	groups = defaultdict(list)
	for mid, sid, name, price, desc, created in rows:
		groups[(sid, jnorm(name))].append(
			{"id": mid, "store_id": sid, "name": name, "price": price,
			 "desc": desc, "has_img": mid in has_img,
			 "pre": str(created) < since})  # cutoff 이전 = 수기, 보존

	dup_groups = {k: v for k, v in groups.items() if len(v) > 1}

	# ── 그룹별 정리 (cutoff 이후 행만 건드림) ──
	# 규칙:
	#   · pre(수기) 행이 있으면 그걸 보존(미수정), post(크롤 중복)는 전부 삭제.
	#   · pre 가 없으면 post 중 richest 만 남기고 삭제. 가격이 전부 동일/null 이면 keeper 보강(merge),
	#     가격이 서로 다르면(크롤 충돌) keeper 가격 유지하고 conflict 로 CSV 기록(검증용).
	#   · pre 끼리 중복(>=2) 은 보존돼 잔존 → 날짜 스코프 인덱스에서 제외됨(별도 집계).
	soft_deleted = 0
	img_rows_deleted = 0
	merged = 0
	orphan_keys = []
	residual_pre = 0           # pre-cutoff 끼리 중복 — 보존(스코프 인덱스에서 제외)
	conflict_rows = []         # 가격상이 그룹 — CSV 기록 (post 자동정리 / pre 보존 구분)
	for members in dup_groups.values():
		pre = [m for m in members if m["pre"]]
		post = [m for m in members if not m["pre"]]
		non_null = {m["price"] for m in members if m["price"] is not None}
		price_conflict = len(non_null) > 1
		if price_conflict:
			conflict_rows.append(members)
		if len(pre) > 1:
			residual_pre += 1

		need_merge = False
		new_price = new_desc = None
		if pre:
			keeper = sorted(pre, key=keeper_score, reverse=True)[0]
			losers = post  # 크롤 중복만 삭제, 수기 keeper 미수정
		else:
			ordered = sorted(post, key=keeper_score, reverse=True)
			keeper, losers = ordered[0], ordered[1:]
			if not price_conflict:  # 가격 안전할 때만 keeper 빈 필드 보강
				new_price = keeper["price"] or next(
					(m["price"] for m in losers if m["price"] is not None), None)
				new_desc = keeper["desc"] if (keeper["desc"] and keeper["desc"].strip()) \
					else next((m["desc"] for m in losers if m["desc"] and m["desc"].strip()), None)
				need_merge = (new_price != keeper["price"]) or (new_desc != keeper["desc"])

		loser_ids = [m["id"] for m in losers]
		if not loser_ids and not need_merge:
			continue
		if args.apply:
			if need_merge:
				cur.execute(
					"UPDATE menu SET price=%s, description=%s, updated_at=now() WHERE id=%s",
					(new_price, new_desc, keeper["id"]))
			if loser_ids:
				cur.execute(
					"SELECT aws_key FROM menu_image_file WHERE menu_id = ANY(%s)", (loser_ids,))
				orphan_keys.extend(r[0] for r in cur.fetchall())
				cur.execute("DELETE FROM menu_image_file WHERE menu_id = ANY(%s)", (loser_ids,))
				img_rows_deleted += cur.rowcount
				cur.execute("UPDATE menu SET deleted_at=now() WHERE id = ANY(%s)", (loser_ids,))
				soft_deleted += cur.rowcount
		else:
			if loser_ids:
				cur.execute(
					"SELECT count(*) FROM menu_image_file WHERE menu_id = ANY(%s)", (loser_ids,))
				img_rows_deleted += cur.fetchone()[0]
				soft_deleted += len(loser_ids)
		if need_merge:
			merged += 1

	# ── 가격상이(검토) CSV — pre 보존분/ post 자동정리분 모두 기록 ──
	with REVIEW_CSV.open("w", newline="", encoding="utf-8-sig") as f:
		w = csv.writer(f)
		w.writerow(["store_id", "menu_id", "name", "price", "has_img", "pre_cutoff", "description"])
		for members in conflict_rows:
			for m in sorted(members, key=lambda x: x["id"]):
				w.writerow([m["store_id"], m["id"], m["name"], m["price"],
							"Y" if m["has_img"] else "N", "Y" if m["pre"] else "N",
							(m["desc"] or "")[:60]])
			w.writerow([])

	# 삭제한 menu_image_file 행의 S3 객체 정리 (커밋 후 — DB 가 source of truth).
	s3_deleted = 0
	if args.apply:
		conn.commit()
		bucket = cfg["storage.bucket"]
		for i in range(0, len(orphan_keys), 1000):
			batch = [{"Key": k} for k in orphan_keys[i:i + 1000]]
			if batch:
				try:
					resp = s3.delete_objects(Bucket=bucket, Delete={"Objects": batch})
					s3_deleted += len(resp.get("Deleted", []))
				except Exception as e:
					print(f"  [S3 일괄삭제 실패] {e}")
	else:
		conn.rollback()

	mode = "APPLIED" if args.apply else "DRY-RUN (변경 없음)"
	print(f"=== 메뉴 중복 정리 [{mode}]  (cutoff: {since} 이전 보존) ===")
	print(f"중복 그룹 총 {len(dup_groups)}  (가격상이 {len(conflict_rows)})")
	print(f"soft-delete 된 메뉴(cutoff 이후 잉여행) = {soft_deleted}")
	print(f"keeper 보강(merge) 그룹 = {merged}")
	print(f"삭제된 menu_image_file 행 = {img_rows_deleted}  (S3 객체는 reconcile 로 정리)")
	print(f"가격상이 그룹 {len(conflict_rows)} → CSV: {REVIEW_CSV}")
	print(f"\n⚠️ pre-cutoff(수기) 끼리 중복 {residual_pre}그룹은 보존 — 메뉴 UNIQUE 인덱스는 "
		f"created_at >= {since} 로 스코프해 이들을 제외함.")
	if args.apply:
		print(f"삭제된 S3 객체 = {s3_deleted}")
	elif orphan_keys:
		print(f"S3 삭제 예정 객체 ≈ {len(orphan_keys)}개")

	cur.close()
	conn.close()
	if not args.apply:
		print("\n실제 적용하려면 --apply 옵션으로 다시 실행하세요.")


if __name__ == "__main__":
	main()
