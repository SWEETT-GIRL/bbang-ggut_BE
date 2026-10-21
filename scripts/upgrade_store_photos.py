#!/usr/bin/env python3
"""기존 가게 사진 중복 제거 + 고화질 교체 (1회성 운영 데이터 정리).

배경: 스크래퍼가 네이버 리사이즈 프록시 래퍼(search.pstatic.net/...?type=w278&src=원본)를
source_url 로 저장해 (1) 278px 썸네일이 S3 에 올라갔고 (2) 같은 사진이 다른 래퍼로 와서
레이스로 중복 insert 됐다.

처리:
  1) source_url 을 canonical(안쪽 원본 origin+path)로 환산해 (store_id, canonical) 기준 중복 제거
     — 가장 작은 id 만 남기고 나머지 DB 행 + S3 객체 삭제.
  2) 남은 행: canonical 원본(풀해상도)을 다운로드해 같은 awsKey 로 S3 덮어쓰기(키 유지 → FE 영향 없음)
     하고 source_url 을 canonical 로 갱신(이후 크롤 멱등성 키 안정).
  3) 다운로드 실패한 행은 기존 저화질 유지(건너뜀).

기본은 dry-run(변경 없음). 실제 적용은 --apply.
DB/S3 접속 정보는 api/src/main/resources/application-local.properties 에서 읽음.

실행:
  scripts/.venv/bin/python scripts/upgrade_store_photos.py            # dry-run 리포트
  scripts/.venv/bin/python scripts/upgrade_store_photos.py --apply    # 실제 정리+교체
"""

import argparse
import io
import sys
import time
import urllib.parse
from collections import defaultdict
from pathlib import Path

import boto3
import psycopg2
import requests

UA = {
	"User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
				"(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"),
	"Referer": "https://pcmap.place.naver.com/",
}
MIME_MAP = {
	"IMAGE_JPEG": "image/jpeg", "IMAGE_PNG": "image/png",
	"IMAGE_WEBP": "image/webp", "IMAGE_HEIC": "image/heic",
}


def canonicalize_pstatic(src: str) -> str:
	"""naver_menu_scraper.canonicalize_pstatic 와 동일 로직."""
	if not src:
		return src
	try:
		u = urllib.parse.urlparse(src)
		inner = urllib.parse.parse_qs(u.query).get("src")
		if inner:
			iu = urllib.parse.urlparse(inner[0])
			if iu.scheme and iu.netloc:
				return f"{iu.scheme}://{iu.netloc}{iu.path}"
		if "pstatic" in u.netloc:
			return f"{u.scheme}://{u.netloc}{u.path}"
		return src
	except Exception:
		return src


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


def db_conn(cfg):
	rest = cfg["spring.datasource.url"].split("//", 1)[1]
	hostport, _, dbname = rest.partition("/")
	host, _, port = hostport.partition(":")
	return psycopg2.connect(
		host=host, port=int(port or 5432), dbname=dbname,
		user=cfg["spring.datasource.username"],
		password=cfg["spring.datasource.password"], connect_timeout=10)


def main() -> None:
	parser = argparse.ArgumentParser(description="가게 사진 중복 제거 + 고화질 교체")
	parser.add_argument("--apply", action="store_true", help="실제 DB/S3 변경 (기본은 dry-run)")
	parser.add_argument("--limit", type=int, default=0, help="처리 행 수 제한 (테스트용)")
	parser.add_argument("--since", default="2026-05-27",
		help="이 날짜(created_at) 이후 행만 정리 — 이전은 수기 데이터라 보존 (기본 2026-05-27)")
	parser.add_argument("--domain", default="stores", choices=["stores", "menus"],
		help="대상 테이블 — stores(store_image_file) | menus(menu_image_file)")
	parser.add_argument("--wrapper-only", action="store_true",
		help="아직 search.pstatic 래퍼인(=미교체) 행만 재처리 — 이미 canonical 로 바뀐 건 스킵")
	args = parser.parse_args()
	table = "store_image_file" if args.domain == "stores" else "menu_image_file"
	fkcol = "store_id" if args.domain == "stores" else "menu_id"

	cfg = load_props()
	bucket = cfg["aws.s3.bucket"]
	s3 = boto3.client(
		"s3", region_name=cfg["aws.region"],
		aws_access_key_id=cfg["aws.credentials.access-key"],
		aws_secret_access_key=cfg["aws.credentials.secret-key"])

	conn = db_conn(cfg)
	conn.autocommit = False
	cur = conn.cursor()
	# cutoff 이전(수기) 행은 제외 — created_at >= since 인 크롤러 사진만 정리.
	# --wrapper-only: 아직 search.pstatic 래퍼인 행만(=이전 실행에서 건너뛴/실패한 미교체분).
	# psycopg2 파라미터 쿼리라 리터럴 % 는 %% 로 이스케이프 (안 하면 '%s' 로 오인).
	wrapper_filter = "AND source_url LIKE '%%search.pstatic.net/common%%'" if args.wrapper_only else ""
	cur.execute(f"""SELECT id, {fkcol}, aws_key, source_url, mime_type
				FROM {table}
				WHERE source_url IS NOT NULL AND created_at >= %s {wrapper_filter} ORDER BY id""",
				(args.since,))
	rows = [{"id": r[0], "fk": r[1], "aws_key": r[2], "source_url": r[3],
			"mime": r[4], "canon": canonicalize_pstatic(r[3])} for r in cur.fetchall()]

	# 1) (fk, canonical) 중복 — 가장 작은 id 만 keeper, 나머지 삭제.
	groups = defaultdict(list)
	for r in rows:
		groups[(r["fk"], r["canon"])].append(r)
	survivors = []
	to_delete = []
	for members in groups.values():
		members.sort(key=lambda x: x["id"])
		survivors.append(members[0])
		to_delete.extend(members[1:])

	if args.limit:
		survivors = survivors[:args.limit]

	deleted_rows = 0
	deleted_s3 = 0
	for r in to_delete:
		if args.apply:
			cur.execute(f"DELETE FROM {table} WHERE id=%s", (r["id"],))
			deleted_rows += cur.rowcount
			try:
				s3.delete_object(Bucket=bucket, Key=r["aws_key"])
				deleted_s3 += 1
			except Exception as e:
				print(f"  [S3 삭제 실패] {r['aws_key']}: {e}")
		else:
			deleted_rows += 1

	# 2) survivor 고화질 교체
	upgraded = 0
	skipped = 0
	failed = 0
	size_before = 0
	size_after = 0
	for i, r in enumerate(survivors):
		canon = r["canon"]
		netloc = urllib.parse.urlparse(canon).netloc
		# canon 이 유효한 URL 이면 호스트 가리지 않고 시도(naver·baemin 등 원본 src 가 다양).
		# 다운로드 실패(403/404 등)는 아래에서 건너뛰고 기존 이미지 유지하므로 host 화이트리스트 불필요.
		if not netloc:
			skipped += 1
			continue
		if not args.apply:
			# dry-run: 앞 5개만 실제 다운로드해 화질 개선 샘플 표시.
			if i < 5:
				try:
					resp = requests.get(canon, headers=UA, timeout=15)
					obj = s3.head_object(Bucket=bucket, Key=r["aws_key"])
					if resp.status_code == 200:
						print(f"  샘플 {r['aws_key']}: S3 {obj['ContentLength']//1024}KB → 원본 {len(resp.content)//1024}KB")
				except Exception as e:
					print(f"  샘플 조회 실패 {r['aws_key']}: {e}")
			upgraded += 1
			continue
		try:
			resp = requests.get(canon, headers=UA, timeout=20)
			if resp.status_code != 200 or not resp.content:
				failed += 1
				continue
			ct = resp.headers.get("content-type", "").split(";")[0].strip()
			if not ct.startswith("image/"):
				ct = MIME_MAP.get(r["mime"], "image/jpeg")
			s3.put_object(Bucket=bucket, Key=r["aws_key"], Body=resp.content, ContentType=ct)
			cur.execute(
				f"UPDATE {table} SET source_url=%s WHERE id=%s", (canon, r["id"]))
			upgraded += 1
			size_after += len(resp.content)
		except Exception as e:
			print(f"  [교체 실패] id={r['id']} {canon[:70]}: {e}")
			failed += 1
		if (i + 1) % 100 == 0:
			print(f"  ... {i + 1}/{len(survivors)} 처리 (성공 {upgraded}, 실패 {failed})")
			if args.apply:
				conn.commit()
		time.sleep(0.05)

	if args.apply:
		conn.commit()
	else:
		conn.rollback()

	mode = "APPLIED" if args.apply else "DRY-RUN (변경 없음)"
	print(f"\n=== 가게 사진 정리+고화질 [{mode}] ===")
	print(f"source_url 보유 사진 행 = {len(rows)}")
	print(f"중복 제거: DB 행 {deleted_rows}개" + (f", S3 객체 {deleted_s3}개" if args.apply else ""))
	print(f"고화질 교체 대상 survivor = {len(survivors)}  (교체 {upgraded}, 건너뜀 {skipped}, 실패 {failed})")
	if args.apply and size_after:
		print(f"교체분 평균 크기 ≈ {size_after // max(upgraded,1) // 1024}KB/장 (이전 ~10KB 썸네일)")

	cur.close()
	conn.close()
	if not args.apply:
		print("\n실제 적용하려면 --apply 옵션으로 다시 실행하세요.")


if __name__ == "__main__":
	main()
