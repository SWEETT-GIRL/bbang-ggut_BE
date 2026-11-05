"""클라우드/로컬 공통 작업 경로와 운영 조회 연결. 비밀값을 출력하지 않는다."""
import os
from pathlib import Path
import re
from urllib.parse import parse_qs, urlsplit

import psycopg2
from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parent.parent


def matching_dir():
    return Path(os.environ.get("DATAOPS_MATCHING_DIR", ROOT / "matching"))


def raw_dir():
    return Path(os.environ.get("DATAOPS_RAW_DIR", matching_dir() / "localdata_raw"))


def settings(env_file=None):
    # 클라우드에서 .env 파일이 없어도 동작한다. 프로세스 환경변수가 우선이다.
    path = Path(env_file) if env_file else ROOT / ".env"
    return {**(dotenv_values(path) if path.is_file() else {}), **os.environ}


def serving_parameters(env_file=None):
    values = settings(env_file)
    url = values.get("DB_URL") or values.get("PROD_DB_URL")
    if not url or not url.startswith("jdbc:postgresql://"):
        raise ValueError("DB_URL must be a PostgreSQL JDBC URL")
    parsed = urlsplit(url[5:])
    query = parse_qs(parsed.query)
    if (not parsed.hostname or parsed.username or parsed.fragment or parsed.path != "/soboro"
            or set(query) - {"currentSchema", "sslmode"}):
        raise ValueError("Serving reader must target soboro without embedded credentials")
    schema = query.get("currentSchema", ["public"])[0]
    ssl = query.get("sslmode", ["require"])[0]
    if schema != "public" or ssl not in ("require", "verify-full"):
        raise ValueError("Serving reader requires public schema and TLS")
    user = values.get("DATAOPS_PROD_DB_USERNAME") or values.get("PROD_DB_USERNAME")
    password = values.get("DATAOPS_PROD_DB_PASSWORD") or values.get("PROD_DB_PASSWORD")
    if not user or not password:
        raise ValueError("Serving reader credentials are required")
    return dict(host=parsed.hostname, port=parsed.port or 5432, dbname="soboro", user=user,
                password=password, sslmode=ssl, connect_timeout=10,
                options="-c default_transaction_read_only=on -c search_path=public -c statement_timeout=30000")


def serving_connect(env_file=None):
    return psycopg2.connect(**serving_parameters(env_file))


def serving_csv(sql, env_file=None):
    conn = serving_connect(env_file)
    try:
        with conn.cursor() as cursor:
            cursor.execute(sql)
            columns = [c[0] for c in cursor.description]
            return [dict(zip(columns, ("" if x is None else str(x) for x in row))) for row in cursor]
    finally:
        conn.close()


def cycle_id(value):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", value):
        raise ValueError("cycle ID must use 1-64 letters, digits, underscores or hyphens")
    return value
