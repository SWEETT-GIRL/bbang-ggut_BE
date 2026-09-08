"""application-local.properties 의 DB 접속으로 psycopg2 연결.

운영 DB 는 bastion 터널(localhost:15432) 경유 — properties 의 spring.datasource.url 이 그 포트를 가리킨다.
dedup_menus.py 의 load_props / db_config 와 같은 규칙. 비밀번호를 스크립트에 박지 않기 위한 공용 모듈.
"""

from pathlib import Path

import psycopg2


def load_props() -> dict:
	props = Path(__file__).resolve().parent.parent / "src/main/resources/application-local.properties"
	cfg = {}
	for line in props.read_text(encoding="utf-8").splitlines():
		line = line.strip()
		if "=" in line and not line.startswith("#"):
			key, _, value = line.partition("=")
			cfg[key.strip()] = value.strip()
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


def connect():
	return psycopg2.connect(sslmode="require", **db_config(load_props()))
