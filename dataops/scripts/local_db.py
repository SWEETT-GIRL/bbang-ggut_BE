"""로컬 dataops DB 접속 한 곳 — 포트를 환경변수로 바꿀 수 있게.

docker-compose 는 15433 을 쓰지만, 기계에 따라 그 포트가 다른 터널에 잡혀 있어 도커를 15434 로 띄우기도 한다
(09-20 이 Mac: 15433 = 개발 DB SSH 터널, 15434 = 로컬 dataops 도커). 스크립트가 포트를 박아 두면
그대로 개발 DB 에 쓰게 되므로 여기서 한 번만 읽는다.

    DATAOPS_DB_PORT=15434 python scripts/localdata_enqueue.py --execute

기본값은 저장소 관례대로 15433 이다.
"""
import os

import psycopg2

HOST = os.environ.get("DATAOPS_DB_HOST", "localhost")
PORT = int(os.environ.get("DATAOPS_DB_PORT", "15433"))
NAME = os.environ.get("DATAOPS_DB_NAME", "dataops")
USER = os.environ.get("DATAOPS_DB_USER", "dataops")
PASSWORD = os.environ.get("DATAOPS_DB_PASSWORD", "dataops")
# 배치 EC2 는 운영 RDS 의 dataops 스키마를 쓴다(로컬 도커는 public). 비어 있으면 서버 기본 search_path
SCHEMA = os.environ.get("DATAOPS_DB_SCHEMA", "").strip()


def connect(**kwargs):
    if os.environ.get("DATAOPS_DB_SSLMODE") and "sslmode" not in kwargs:
        kwargs["sslmode"] = os.environ["DATAOPS_DB_SSLMODE"]
    if SCHEMA and "options" not in kwargs:
        kwargs["options"] = f"-c search_path={SCHEMA}"
    return psycopg2.connect(host=HOST, port=PORT, dbname=NAME, user=USER, password=PASSWORD, **kwargs)


def describe():
    return f"{HOST}:{PORT}/{NAME}" + (f"?schema={SCHEMA}" if SCHEMA else "")
