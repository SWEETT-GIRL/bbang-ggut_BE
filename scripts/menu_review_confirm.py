#!/usr/bin/env python3
"""메뉴 AI 분류 검수 큐 — 이름 단위 CSV 로 뽑고, 사람이 kind 를 채운 CSV 를 어드민 API 로 한 번에 확정한다.

`menu_review_queue.py` 는 메뉴 행 단위라 같은 이름이 여러 줄로 나온다. 확정은 이름(정규화 키) 단위라
여기서는 이름 하나에 한 줄만 낸다.

흐름:
  1) 뽑기   scripts/.venv/bin/python scripts/menu_review_confirm.py --export
     → scripts/menu_review_confirm.csv (kind 칸이 비어 있음. suggested_kind 는 참고용 추측)
  2) 채우기 CSV 의 kind 칸에 MenuKind 이름을 적는다 (비워 두면 그 줄은 건너뛴다).
     음료·굿즈처럼 빵집 먹거리가 아니면 kind=기타, bakery_item=false.
  3) 넣기   scripts/.venv/bin/python scripts/menu_review_confirm.py --apply scripts/menu_review_confirm.csv
     → POST /api/admin/menu-classifications 를 줄마다 호출. 캐시에 ADMIN 판정으로 남고 같은 이름의
       미분류 활성 메뉴에 적용된다 (이미 분류된 메뉴는 안 건드린다).

CSV 열:
  normalized_name   정규화 키 (건드리지 말 것 — 확정은 이 키로 나간다)
  sample_name       사람이 읽을 원본 이름 하나
  unclassified      이 이름의 미분류 활성 메뉴 수
  same_name_total   이 이름의 활성 메뉴 총수 (이미 분류된 것 포함)
  existing_kinds    같은 이름이 이미 받은 분류 (룰·AI 가 준 것). 판단 근거
  stores            그 이름을 파는 가게 예시
  suggested_kind    끝단어 규칙으로 뽑은 추측 (참고용, 그대로 쓰지 말 것)
  kind              ← 여기에 사람이 적는다
  bakery_item       빈칸이면 true. 음료·굿즈면 false
"""

import argparse
import csv
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

import admin_auth
import localdb

DEFAULT_CSV = Path(__file__).resolve().parent / "menu_review_confirm.csv"
DEFAULT_SERVER = "https://bbang-ggut.cloud"
COLUMNS = [
	"normalized_name", "sample_name", "unclassified", "same_name_total",
	"existing_kinds", "stores", "suggested_kind", "kind", "bakery_item",
]

EXPORT_SQL = """
SELECT m.normalized_name,
	min(m.name) AS sample_name,
	count(*) AS unclassified,
	(SELECT count(*) FROM menu x WHERE x.normalized_name = m.normalized_name AND x.deleted_at IS NULL) AS same_name_total,
	(SELECT string_agg(k.name || ':' || k.cnt, ', ' ORDER BY k.cnt DESC)
	   FROM (SELECT c.name, count(*) AS cnt
			   FROM menu y JOIN menu_category c ON c.id = y.menu_category_id
			  WHERE y.normalized_name = m.normalized_name AND y.deleted_at IS NULL
			  GROUP BY c.name ORDER BY count(*) DESC LIMIT 3) k) AS existing_kinds,
	(SELECT string_agg(DISTINCT s.name, ', ')
	   FROM (SELECT z.store_id FROM menu z
			  WHERE z.normalized_name = m.normalized_name AND z.deleted_at IS NULL LIMIT 3) t
	   JOIN store s ON s.id = t.store_id) AS stores
FROM menu m
WHERE m.menu_category_id IS NULL AND m.classified_at IS NOT NULL AND m.deleted_at IS NULL
GROUP BY m.normalized_name
ORDER BY same_name_total DESC, unclassified DESC, m.normalized_name
LIMIT %s
"""


def suggest_kind(sample_name: str) -> str:
	"""끝단어(마지막 제품어) 규칙 — BreadCategoryClassifier·MenuCategoryClassifier 와 같은 발상의 가벼운 추측."""
	compact = "".join(ch for ch in sample_name if ch.isalnum()).lower()
	best_kind, best_end = "", -1
	for kind, variants in KIND_HINTS:
		for variant in variants:
			end = compact.rfind(variant)
			if end >= 0 and end + len(variant) > best_end:
				best_end, best_kind = end + len(variant), kind
	return best_kind


# (MenuKind, 이름에 나타나는 표기 변형) — 끝에 더 가까운 것이 이긴다. 추측용이라 촘촘할 필요는 없다.
KIND_HINTS = [
	("소금빵", ["소금빵", "시오빵"]), ("식빵", ["식빵"]), ("바게트", ["바게트", "바게뜨", "바겟"]),
	("크루아상", ["크루아상", "크로와상", "크로아상", "크루아쌍"]), ("퀸아망", ["퀸아망", "쿠인아망"]),
	("치아바타", ["치아바타"]), ("포카치아", ["포카치아"]), ("사워도우", ["사워도우"]), ("깜빠뉴", ["깜빠뉴", "캄파뉴"]),
	("베이글", ["베이글"]), ("샌드위치", ["샌드위치", "샌드"]), ("토스트", ["토스트"]), ("쫀득빵", ["쫀득빵", "찹쌀빵"]),
	("맘모스", ["맘모스"]), ("앙버터", ["앙버터"]), ("소보로", ["소보로", "곰보빵"]), ("브리오슈", ["브리오슈", "브리오쉬"]),
	("페이스트리", ["페이스트리", "데니쉬", "뺑오쇼콜라", "칸놀리", "카놀리"]), ("스콘", ["스콘"]),
	("에그타르트", ["에그타르트"]), ("타르트", ["타르트", "타르뜨"]), ("휘낭시에", ["휘낭시에", "피낭시에"]),
	("마들렌", ["마들렌"]), ("까눌레", ["까눌레", "카눌레"]), ("쿠키", ["쿠키"]), ("쫀득쿠키", ["쫀득쿠키", "두쫀쿠"]),
	("브라우니", ["브라우니"]), ("케이크", ["케이크", "케익", "케잌", "테린느", "갸또", "제누와즈"]),
	("파운드", ["파운드"]), ("티라미수", ["티라미수"]), ("푸딩", ["푸딩"]), ("러스크", ["러스크"]),
	("파이", ["파이", "크럼블", "갈레트"]), ("도넛", ["도넛", "도너츠", "도나츠"]), ("떡", ["모찌", "찰떡", "인절미"]),
	("소세지빵", ["소세지빵", "소시지빵"]), ("마늘빵", ["마늘빵", "갈릭브레드"]), ("고구마빵", ["고구마빵"]),
	("밤빵", ["밤빵"]), ("빙수", ["빙수"]), ("크로플", ["크로플"]), ("아이스크림", ["아이스크림", "파르페", "젤라또"]),
	("요거트", ["요거트", "그릭"]), ("크림빵", ["크림빵"]), ("단팥빵", ["단팥빵", "팥빵"]), ("마카롱", ["마카롱"]),
	("카스테라", ["카스테라", "카스텔라"]), ("머핀", ["머핀"]), ("다쿠아즈", ["다쿠아즈"]), ("산도", ["산도"]),
	("약과", ["약과"]), ("양갱", ["양갱"]), ("프레첼", ["프레첼", "프레즐"]), ("와플", ["와플"]),
	("번", ["번"]), ("롤", ["롤"]), ("빵", ["빵", "브레드"]),
]


def export(limit: int, out: Path) -> None:
	with localdb.connect() as conn, conn.cursor() as cur:
		cur.execute(EXPORT_SQL, (limit,))
		rows = cur.fetchall()

	with open(out, "w", newline="", encoding="utf-8-sig") as f:
		writer = csv.writer(f)
		writer.writerow(COLUMNS)
		for normalized_name, sample_name, unclassified, same_name_total, existing_kinds, stores in rows:
			writer.writerow([
				normalized_name, sample_name, unclassified, same_name_total,
				existing_kinds or "", stores or "", suggest_kind(sample_name or ""), "", "",
			])

	total_menus = sum(r[2] for r in rows)
	print(f"검수 큐 이름 {len(rows)}개 (미분류 메뉴 {total_menus}건) → {out}")
	print("kind 칸을 채운 뒤: menu_review_confirm.py --apply <csv>")
	if rows:
		print("같은 이름이 많은 것부터:")
		for r in rows[:10]:
			print(f"  활성 {r[3]:>4}건 / 미분류 {r[2]:>3}건  {r[1]}  (기존 분류 {r[4] or '없음'})")


def post_confirm(server: str, name: str, kind: str, bakery_item: bool) -> tuple[bool, str]:
	body = json.dumps({"name": name, "kind": kind, "bakeryItem": bakery_item}).encode()
	url = f"{server}/api/admin/menu-classifications"
	request = urllib.request.Request(
		url, data=body,
		headers={"Content-Type": "application/json", **admin_auth.headers_for(url)}, method="POST")
	try:
		with urllib.request.urlopen(request, timeout=30) as response:
			payload = json.loads(response.read())
		applied = payload.get("data", {}).get("appliedMenus")
		return True, f"적용 {applied}건"
	except urllib.error.HTTPError as error:
		detail = json.loads(error.read() or b"{}")
		return False, f"HTTP {error.code} {detail.get('code', '')} {detail.get('message', '')}".strip()
	except Exception as error:  # 네트워크 등
		return False, repr(error)


def apply(path: Path, server: str, dry_run: bool) -> int:
	with open(path, newline="", encoding="utf-8-sig") as f:
		rows = [row for row in csv.DictReader(f)]

	targets = [row for row in rows if (row.get("kind") or "").strip()]
	print(f"CSV {len(rows)}줄 중 kind 가 채워진 {len(targets)}줄 처리 ({'dry-run' if dry_run else server})")
	failed = 0
	for row in targets:
		# 확정은 정규화 키로 나간다 — 원본 이름을 쓰면 서버가 다시 정규화하므로 결과는 같지만 키가 명시적이다.
		name = (row.get("normalized_name") or "").strip()
		kind = row["kind"].strip()
		bakery_item = (row.get("bakery_item") or "").strip().lower() not in ("false", "f", "0", "no", "n")
		if dry_run:
			print(f"  [dry] {name} → {kind} (bakery_item={bakery_item})")
			continue
		ok, message = post_confirm(server, name, kind, bakery_item)
		print(f"  {'OK ' if ok else 'FAIL'} {name} → {kind}: {message}")
		failed += 0 if ok else 1
	if failed:
		print(f"실패 {failed}줄 — kind 이름이 MenuKind 에 있는지 확인할 것")
	return failed


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("--export", action="store_true", help="검수 큐를 이름 단위 CSV 로 뽑는다 (기본)")
	parser.add_argument("--apply", metavar="CSV", help="kind 를 채운 CSV 로 확정 API 호출")
	parser.add_argument("--out", default=str(DEFAULT_CSV))
	parser.add_argument("--limit", type=int, default=5000)
	parser.add_argument("--server", default=DEFAULT_SERVER)
	parser.add_argument("--dry-run", action="store_true", help="--apply 와 함께: 호출 없이 어떤 줄이 나갈지만 출력")
	args = parser.parse_args()

	if args.apply:
		sys.exit(1 if apply(Path(args.apply), args.server.rstrip("/"), args.dry_run) else 0)
	export(args.limit, Path(args.out))


if __name__ == "__main__":
	main()
