"""dataops 판정 서버 호출 공통 — API 키 헤더.

배치 EC2 의 서버는 X-Dataops-Key 로 막는다(DataopsApiKeyFilter). 키는 환경 변수 DATAOPS_API_KEY 로 받고,
없으면 헤더를 붙이지 않는다 — 키 없이 띄운 로컬 서버는 지금처럼 그대로 부른다.
네이버 등 외부 요청에는 쓰지 않는다(키가 밖으로 나가지 않게).
"""
import os

API_KEY_HEADER = "X-Dataops-Key"


def server_headers(extra=None):
    headers = dict(extra or {})
    key = os.environ.get("DATAOPS_API_KEY", "").strip()
    if key:
        headers[API_KEY_HEADER] = key
    return headers
