#!/usr/bin/env python3
"""마이그레이션 .sql 파일을 application-local.properties 의 DB 에 적용 (psql 미설치 환경용).

실행:
  scripts/.venv/bin/python scripts/apply_migration.py docs/migrations/<파일>.sql
"""

import sys
from pathlib import Path

import psycopg2


def db_config() -> dict:
	props = (Path(__file__).resolve().parent.parent
			/ "api/src/main/resources/application-local.properties")
	cfg = {}
	for line in props.read_text(encoding="utf-8").splitlines():
		line = line.strip()
		if line.startswith("spring.datasource.") and "=" in line:
			k, _, v = line.partition("=")
			cfg[k.strip()] = v.strip()
	rest = cfg["spring.datasource.url"].split("//", 1)[1]
	hostport, _, dbname = rest.partition("/")
	host, _, port = hostport.partition(":")
	return {"host": host, "port": int(port or 5432), "dbname": dbname,
			"user": cfg["spring.datasource.username"],
			"password": cfg["spring.datasource.password"]}


def main() -> None:
	if len(sys.argv) < 2:
		print("usage: apply_migration.py <path-to.sql>")
		sys.exit(1)
	sql_path = Path(sys.argv[1])
	if not sql_path.is_absolute():
		sql_path = Path(__file__).resolve().parent.parent / sql_path
	sql = sql_path.read_text(encoding="utf-8")

	conn = psycopg2.connect(**db_config(), connect_timeout=15)
	conn.autocommit = True  # DDL — 인덱스 생성은 즉시 커밋
	cur = conn.cursor()
	print(f"[migration] 적용: {sql_path}")
	cur.execute(sql)
	# 생성된 uq_ 인덱스 확인
	cur.execute("""SELECT indexname FROM pg_indexes
		WHERE indexname LIKE 'uq_%' AND (tablename IN ('menu','store_image_file','menu_image_file'))
		ORDER BY indexname""")
	print("[migration] 현재 uq_ 인덱스:")
	for (n,) in cur.fetchall():
		print("  ", n)
	cur.close()
	conn.close()
	print("[migration] 완료")


if __name__ == "__main__":
	main()
