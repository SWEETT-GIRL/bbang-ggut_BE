"""어드민 API 열쇠.

서버는 /api/admin/** 와 /admin/** 요청에 X-Admin-Key 헤더를 요구한다. 스크립트는 환경변수
ADMIN_API_KEY 에서 열쇠를 읽어 붙인다. 열쇠를 코드나 명령줄 인자에 적지 않는다.

	export ADMIN_API_KEY=...   # 서버 .env 의 ADMIN_API_KEY 와 같은 값
	python3 scripts/naver_menu_scraper.py --loop

환경변수가 없으면 아무것도 붙이지 않는다 — 열쇠 없이 띄운 로컬 서버는 그대로 통과시킨다.
"""

import ipaddress
import os
import urllib.parse

HEADER = "X-Admin-Key"

_ADMIN_ROOTS = ("/api/admin/", "/admin/")
_OUR_DOMAINS = ("bbang-ggut.cloud", "bbang-ggut.site")


def _key() -> str:
	return os.environ.get("ADMIN_API_KEY", "").strip()


def _is_our_server(host: str) -> bool:
	"""열쇠를 보내도 되는 곳인가. 네이버 같은 바깥 주소로 열쇠가 새지 않게 우리 서버로만 한정한다."""
	if host == "localhost" or any(host == domain or host.endswith("." + domain) for domain in _OUR_DOMAINS):
		return True
	try:
		address = ipaddress.ip_address(host)
	except ValueError:
		return False
	return address.is_loopback or address.is_private


def headers_for(url: str) -> dict:
	"""이 주소로 보낼 때 붙일 헤더. 우리 서버의 어드민 경로가 아니거나 열쇠가 없으면 비어 있다."""
	key = _key()
	parsed = urllib.parse.urlparse(url)
	if not key or not parsed.path.startswith(_ADMIN_ROOTS) or not _is_our_server(parsed.hostname or ""):
		return {}
	return {HEADER: key}


def install() -> None:
	"""requests 로 나가는 모든 요청 가운데 우리 서버의 어드민 경로에만 열쇠를 붙인다.

	호출부가 수십 곳이라 하나씩 헤더를 넘기면 새로 더한 호출에서 빠진다. 한 번만 부르면 된다.
	"""
	import requests

	if getattr(requests.Session.request, "_admin_auth", False):
		return
	original = requests.Session.request

	def request(self, method, url, **kwargs):
		extra = headers_for(str(url))
		if extra:
			kwargs["headers"] = {**extra, **(kwargs.get("headers") or {})}
		return original(self, method, url, **kwargs)

	request._admin_auth = True
	requests.Session.request = request
