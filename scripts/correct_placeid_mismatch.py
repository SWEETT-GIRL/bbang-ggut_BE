#!/usr/bin/env python3
"""오매칭(잘못된 placeId) 가게 교정 — scan_placeid_mismatch.py 결과(CSV) 기반.

각 오매칭 가게에 대해:
  1. naver_place_id 를 NULL 로 해제 (잘못된 장소 매칭 해제 → 재매칭 가능 + 진짜 주인 차단 해제)
  2. 그 가게가 잘못된 장소에서 긁어온 사진(source_url 있는 store_image_file) 삭제 (DB + S3)
  3. 그 가게의 활성 메뉴 soft-delete (잘못된 장소 메뉴라 신뢰 불가 — 복구 가능, 재크롤로 채움)

soft-delete/placeId NULL 은 되돌릴 수 있고, 사진은 재크롤로 복원되므로 false-positive 도 안전.
기본 dry-run, 실제 적용은 --apply.
"""

import argparse
import csv
from pathlib import Path

import boto3
import psycopg2

CSV = Path(__file__).resolve().parent / "placeid_mismatch.csv"


def load_props() -> dict:
	props = (Path(__file__).resolve().parent.parent
			/ "api/src/main/resources/application-local.properties")
	cfg = {}
	for line in props.read_text(encoding="utf-8").splitlines():
		line = line.strip()
		if "=" in line and not line.startswith("#"):
			k, _, v = line.partition("=")
			cfg[k.strip()] = v.strip()
	return cfg


def main() -> None:
	ap = argparse.ArgumentParser()
	ap.add_argument("--apply", action="store_true")
	args = ap.parse_args()

	ids = []
	with CSV.open(encoding="utf-8-sig") as f:
		for row in csv.DictReader(f):
			if row.get("store_id"):
				ids.append(int(row["store_id"]))
	print(f"오매칭 가게 {len(ids)}곳: {ids}")
	if not ids:
		print("교정 대상 없음."); return

	cfg = load_props()
	rest = cfg["spring.datasource.url"].split("//", 1)[1]
	hostport, _, dbname = rest.partition("/")
	host, _, port = hostport.partition(":")
	conn = psycopg2.connect(host=host, port=int(port or 5432), dbname=dbname,
		user=cfg["spring.datasource.username"], password=cfg["spring.datasource.password"],
		connect_timeout=15)
	conn.autocommit = False
	cur = conn.cursor()
	s3 = boto3.client("s3", region_name=cfg["aws.region"],
		aws_access_key_id=cfg["aws.credentials.access-key"],
		aws_secret_access_key=cfg["aws.credentials.secret-key"])
	bucket = cfg["aws.s3.bucket"]

	cur.execute("SELECT aws_key FROM store_image_file WHERE store_id = ANY(%s) AND source_url IS NOT NULL", (ids,))
	keys = [r[0] for r in cur.fetchall()]
	cur.execute("SELECT count(*) FROM menu WHERE store_id = ANY(%s) AND deleted_at IS NULL", (ids,))
	menu_cnt = cur.fetchone()[0]
	print(f"삭제 예정: 크롤사진 {len(keys)}장, soft-delete 메뉴 {menu_cnt}개, placeId 해제 {len(ids)}곳")

	if not args.apply:
		conn.rollback(); print("\n[dry-run] --apply 로 실제 적용."); cur.close(); conn.close(); return

	cur.execute("DELETE FROM store_image_file WHERE store_id = ANY(%s) AND source_url IS NOT NULL", (ids,))
	photo_del = cur.rowcount
	cur.execute("UPDATE menu SET deleted_at = now() WHERE store_id = ANY(%s) AND deleted_at IS NULL", (ids,))
	menu_del = cur.rowcount
	cur.execute("UPDATE store SET naver_place_id = NULL WHERE id = ANY(%s)", (ids,))
	pid_cleared = cur.rowcount
	conn.commit()

	s3_del = 0
	for i in range(0, len(keys), 1000):
		batch = [{"Key": k} for k in keys[i:i + 1000]]
		if batch:
			s3_del += len(s3.delete_objects(Bucket=bucket, Delete={"Objects": batch}).get("Deleted", []))

	print(f"\n=== 교정 완료 ===")
	print(f"placeId 해제 {pid_cleared}곳, 크롤사진 삭제 {photo_del}(S3 {s3_del}), 메뉴 soft-delete {menu_del}")
	print("→ 이 가게들은 이제 미매칭 상태 → 일괄 매칭 재크롤로 자기 진짜 장소에 재매칭됨.")
	cur.close()
	conn.close()


if __name__ == "__main__":
	main()
