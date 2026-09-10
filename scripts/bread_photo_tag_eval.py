#!/usr/bin/env python3
"""빵로그 사진 AI 태그 정확도 실측 (1회성 검증).

목적: 설계(.claude/planning/feat-bread-photo-ai-tag.md)가 서 있는 가정 셋을 착수 전에 숫자로 확인한다.
  1) 사진만 보고 그 가게 메뉴 중 어느 빵인지 고를 수 있는가
  2) 가게 메뉴 목록을 프롬프트에 넣으면 정확도가 오르는가
     (메뉴 이름 분류 때는 가게 컨텍스트가 정확도를 못 올리고 틀린 답의 confidence만 올렸다.
      그때는 이름만 줬고 지금은 사진이 있으니 다를 수 있다 — 그걸 가른다)
  3) 최저가 모델로 되는가, 상위 모델이 필요한가

정답은 사람이 채점하지 않아도 된다 — **유저가 직접 단 태그가 곧 정답**이다.
사진 위 좌표까지 유저가 찍어 둬서 위치 오차도 잰다.

자동 산출 지표:
  - 이름 정확도 : 정답 태그 중 AI 가 같은 메뉴(menuId) 또는 같은 이름을 제안한 비율
  - 좌표 오차   : 이름이 맞은 쌍의 거리(정규화 0~1 평면, 사진 대각선 = 1.414)
  - 제안 개수   : 사진당 평균 (실제 정답은 사진당 평균 1.8개)
  - 과잉 제안   : 정답에 없는 것을 몇 개나 만들어 내는가
  - 음료 혼입   : 음료·비-빵을 제안한 건수 (프롬프트가 금지한 것)

사전 준비:
  1) 운영 DB 터널 (localdb 가 application-local.properties 의 localhost:15432 를 읽는다)
  2) API 키 — 환경변수 GEMINI_API_KEY 또는 ~/.gemini_key

실행:
  scripts/.venv/bin/python scripts/bread_photo_tag_eval.py --photos 12
  scripts/.venv/bin/python scripts/bread_photo_tag_eval.py --photos 40 \
      --model gemini-3.5-flash-lite --model gemini-3.8-flash
"""

import argparse
import csv
import json
import math
import os
import re
import sys
import time
from collections import Counter
from pathlib import Path

import localdb
import urllib.request
from google import genai
from google.genai import types

CDN_BASE = "https://cdn.bbang-ggut.cloud/"
OUT_CSV = Path(__file__).resolve().parent / "bread_photo_tag_eval.csv"

# 프롬프트가 제안하지 말라고 한 것들 — 정답 태그에 이게 있으면 "유저는 달았지만 AI 는 안 다는 게 맞다"
DRINK_PATTERN = re.compile(
	r"(라떼|아메리카노|아아|에이드|스무디|주스|쥬스|커피|우유|밀크티|얼그레이|콜드브루|소다|음료|워터)"
)

PROMPT_HEAD = """이 사진은 한국 베이커리·카페에서 손님이 찍은 것이다.
사진에 실제로 보이는 빵·디저트를 찾아 각각의 이름과 위치를 답하라.
"""

PROMPT_MENUS = """
이 가게의 메뉴다.
{menus}

**이 목록에서만 고른다.** 사진 속 빵이 목록에 없으면 답하지 않는다 — 일반적인 빵 이름을 지어내지 않는다.
비슷한 게 있으면 뭉뚱그리지 말고 정확한 메뉴를 고른다 — '소금빵'보다 '참깨 소금빵'이 낫다.

**확신이 안 서면 답하지 않는다.** 목록에서 후보가 둘 이상 떠오르는데 사진만으로 못 가르겠으면 비운다.
틀린 답보다 없는 답이 낫다.
"""

PROMPT_TAIL = """
규칙:
- **같은 빵은 한 번만 답한다.** 접시에 크루아상이 다섯 개 놓여 있어도 답은 크루아상 하나다.
  이름이 같아질 것을 여러 줄로 적지 않는다. 가장 잘 보이는 것 하나를 골라 그 위치를 답한다.
- **최대 5개까지만 답한다.** 진열대처럼 여러 종류가 늘어서 있으면 크고 선명한 것부터 5개.
  뒤쪽에 흐릿하게 걸친 것, 잘려서 일부만 보이는 것은 빼도 된다.
- 진열대나 매장 전경처럼 무엇이 주인공인지 알 수 없는 사진은 빈 목록을 답한다.
  손님이 먹으려고 고른 빵이 앞에 놓인 사진만 답한다.
- 음료(커피·차·주스·스무디·우유)는 답하지 않는다. 빵과 같이 찍혀 있어도 무시한다.
- 접시·포크·냅킨·컵·간판·메뉴판·사람은 답하지 않는다.
- 포장지에 싸여 있거나 봉투에 든 것도 빵이면 답한다.
- 사진에 빵이 없으면 빈 목록을 답한다. 없는 것을 지어내지 않는다.
- 위치는 그 빵을 감싸는 사각형을 [ymin, xmin, ymax, xmax] 로, 각 값은 0~1000 으로 정규화해 답한다.
"""

# 제안 상한 — 프롬프트가 어기는 걸 봤으므로(크루아상 하나를 5번 답함) 서버가 다시 자른다
MAX_SUGGESTIONS = 5

# 무료 티어 RPM 15. 요청이 3.5초에 끝나면 분당 17회가 되어 넘는다 — 사이를 벌려 14회로 맞춘다.
MIN_REQUEST_GAP = 4.3

# 한 호출을 이보다 오래 기다리지 않는다 (밀리초)
REQUEST_TIMEOUT_MS = 30_000


class QuotaExhausted(RuntimeError):
	"""하루 한도를 다 썼다. 여기까지의 결과로 리포트를 내고 끝낸다."""

RESPONSE_SCHEMA = {
	"type": "OBJECT",
	"properties": {
		"items": {
			"type": "ARRAY",
			"items": {
				"type": "OBJECT",
				"properties": {
					"menuId": {"type": "INTEGER", "nullable": True},
					"name": {"type": "STRING"},
					"box_2d": {"type": "ARRAY", "items": {"type": "INTEGER"}},
				},
				"required": ["name", "box_2d"],
			},
		}
	},
	"required": ["items"],
}


def normalize(name: str) -> str:
	"""MenuNameNormalizer 와 같은 규칙 — 한글·영문·숫자만 남기고 소문자."""
	return re.sub(r"[^\w가-힣]", "", (name or "").lower(), flags=re.UNICODE)


def fetch_dataset(cursor, limit: int) -> list[dict]:
	"""정답이 있는 사진 — 태그가 달렸고, 가게가 정해졌고, 640 리사이즈본이 있는 것."""
	cursor.execute(
		"""
		SELECT p.id, p.resized_key_640, r.store_id, s.name
		FROM bread_record_photo p
		JOIN bread_record r ON r.id = p.record_id AND r.deleted_at IS NULL AND r.store_id IS NOT NULL
		JOIN store s ON s.id = r.store_id
		WHERE p.resized_key_640 IS NOT NULL
		  AND EXISTS (SELECT 1 FROM bread_record_tag t WHERE t.photo_id = p.id)
		ORDER BY p.id DESC
		LIMIT %s
		""",
		(limit,),
	)
	photos = [
		{"photo_id": row[0], "key": row[1], "store_id": row[2], "store_name": row[3]}
		for row in cursor.fetchall()
	]

	for photo in photos:
		cursor.execute(
			"SELECT name, menu_id, x, y FROM bread_record_tag WHERE photo_id = %s ORDER BY id",
			(photo["photo_id"],),
		)
		photo["truth"] = [
			{"name": row[0], "menu_id": row[1], "x": float(row[2]), "y": float(row[3])}
			for row in cursor.fetchall()
		]
		cursor.execute(
			"""
			SELECT id, name FROM menu
			WHERE store_id = %s AND deleted_at IS NULL
			ORDER BY like_count DESC, id
			LIMIT 60
			""",
			(photo["store_id"],),
		)
		photo["menus"] = [{"id": row[0], "name": row[1]} for row in cursor.fetchall()]
	return photos


def download(key: str) -> bytes | None:
	try:
		with urllib.request.urlopen(CDN_BASE + key, timeout=20) as response:
			return response.read()
	except Exception as failed:  # noqa: BLE001 — 한 장 못 받는다고 평가를 멈추지 않는다
		print(f"  이미지 실패 {key}: {failed}", file=sys.stderr)
		return None


def build_prompt(photo: dict, with_menus: bool) -> str:
	prompt = PROMPT_HEAD
	if with_menus and photo["menus"]:
		listing = "\n".join(f"  {menu['id']}: {menu['name']}" for menu in photo["menus"])
		prompt += PROMPT_MENUS.format(menus=listing)
	return prompt + PROMPT_TAIL


def ask(client, model: str, image: bytes, prompt: str, retries: int = 1) -> tuple[list[dict], float]:
	"""제안 목록과 걸린 시간(초). 사용자가 기다리는 경로가 될 것이라 시간이 정확도만큼 중요하다."""
	started = time.time()
	for attempt in range(retries + 1):
		try:
			response = client.models.generate_content(
				model=model,
				contents=[
					types.Part.from_bytes(data=image, mime_type="image/webp"),
					types.Part.from_text(text=prompt),
				],
				config=types.GenerateContentConfig(
					temperature=0.0,
					response_mime_type="application/json",
					response_schema=RESPONSE_SCHEMA,
				),
			)
			return (json.loads(response.text) or {}).get("items") or [], time.time() - started
		except Exception as failed:  # noqa: BLE001
			message = str(failed)
			# 하루 총량이라 기다려도 안 풀린다. 다만 여기까지 모은 결과는 살려야 하므로 신호만 올린다
			if "429" in message or "RESOURCE_EXHAUSTED" in message:
				raise QuotaExhausted(message[:300]) from failed
			if attempt < retries and "503" in message:
				time.sleep(10)
				continue
			print(f"  호출 실패: {message[:160]}", file=sys.stderr)
			return [], time.time() - started
	return [], time.time() - started


def dedupe(items: list[dict], menu_name: dict[int, str]) -> list[dict]:
	"""같은 빵을 여러 번 답한 것을 접고, 상한까지 자른다.

	프롬프트로 막아도 새는 걸 실측에서 봤다 — 접시에 놓인 크루아상 하나를 다섯 줄로 답했다.
	같은 메뉴(menuId)거나 이름이 같으면 한 번만 남기고, 남긴 것은 상자가 가장 큰 것(= 제일 잘 보이는 것)이다.
	그 뒤 큰 순서로 5개까지만 쓴다 — 진열대 사진에서 뒤쪽 흐린 것까지 태그로 만들지 않기 위해.
	"""
	def area(item: dict) -> float:
		box = item.get("box_2d") or []
		if len(box) != 4:
			return 0.0
		ymin, xmin, ymax, xmax = box
		return max(0, ymax - ymin) * max(0, xmax - xmin)

	best: dict[str, dict] = {}
	for item in sorted(items, key=area, reverse=True):
		menu_id = item.get("menuId")
		key = f"menu:{menu_id}" if menu_id is not None else f"name:{menu_name.get(menu_id) or normalize(item.get('name'))}"
		best.setdefault(key, item)
	return sorted(best.values(), key=area, reverse=True)[:MAX_SUGGESTIONS]


def agree(runs: list[list[dict]], menu_name: dict[int, str], need: int) -> list[dict]:
	"""여러 번 물어 need 번 이상 나온 것만 남긴다.

	메뉴 이름 분류에서 얻은 원리를 사진에 그대로 옮긴 것 — 모델의 자기 점수는 못 믿지만
	같은 질문에 매번 같은 답을 하는지는 믿을 만하다. 흔들리는 답은 "모르겠다"로 친다.
	위치는 그 빵을 답한 실행들의 상자 중 가장 큰 것을 쓴다.
	"""
	seen: dict[str, list[dict]] = {}
	for items in runs:
		for item in dedupe(items, menu_name):
			menu_id = item.get("menuId")
			key = f"menu:{menu_id}" if menu_id is not None else f"name:{normalize(item.get('name'))}"
			seen.setdefault(key, []).append(item)

	def area(item: dict) -> float:
		box = item.get("box_2d") or []
		if len(box) != 4:
			return 0.0
		return max(0, box[2] - box[0]) * max(0, box[3] - box[1])

	agreed = [max(group, key=area) for group in seen.values() if len(group) >= need]
	return sorted(agreed, key=area, reverse=True)[:MAX_SUGGESTIONS]


def center_of(box: list[int]) -> tuple[float, float]:
	"""[ymin, xmin, ymax, xmax] 0~1000 → 중심점 (x, y) 0~1. bread_record_tag 의 x·y 와 같은 좌표계."""
	if not box or len(box) != 4:
		return (0.5, 0.5)
	ymin, xmin, ymax, xmax = box
	return ((xmin + xmax) / 2 / 1000, (ymin + ymax) / 2 / 1000)


def score(photo: dict, items: list[dict]) -> dict:
	"""정답 태그 하나하나에 대해 '같은 빵을 제안했는가'를 본다. 좌표는 맞은 쌍에서만 잰다."""
	menu_name = {menu["id"]: normalize(menu["name"]) for menu in photo["menus"]}
	suggestions = []
	for item in dedupe(items, menu_name):
		menu_id = item.get("menuId")
		name = item.get("name") or ""
		suggestions.append(
			{
				"menu_id": menu_id,
				"key": menu_name.get(menu_id) or normalize(name),
				"raw": name,
				"point": center_of(item.get("box_2d") or []),
				"used": False,
			}
		)

	hits, distances = 0, []
	drink_truth = 0
	for truth in photo["truth"]:
		if DRINK_PATTERN.search(truth["name"]):
			drink_truth += 1
			continue
		target = menu_name.get(truth["menu_id"]) or normalize(truth["name"])
		match = next(
			(s for s in suggestions if not s["used"] and (
				(truth["menu_id"] is not None and s["menu_id"] == truth["menu_id"]) or s["key"] == target
			)),
			None,
		)
		if match:
			match["used"] = True
			hits += 1
			distances.append(math.dist((truth["x"], truth["y"]), match["point"]))

	return {
		"truth_count": len(photo["truth"]) - drink_truth,
		"drink_truth": drink_truth,
		"suggested": len(suggestions),
		"hits": hits,
		"extra": sum(1 for s in suggestions if not s["used"]),
		"drink_suggested": sum(1 for s in suggestions if DRINK_PATTERN.search(s["raw"])),
		"distances": distances,
	}


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("--photos", type=int, default=12, help="평가할 사진 수")
	parser.add_argument("--model", action="append", default=None, help="모델 (여러 번 지정 가능)")
	parser.add_argument("--menus-only", action="store_true", help="메뉴 주입 조합만 (호출 절반)")
	parser.add_argument("--runs", type=int, default=1, help="같은 사진을 몇 번 묻고 합의를 볼지")
	parser.add_argument("--agree", type=int, default=None, help="몇 번 이상 나와야 제안으로 칠지 (기본: runs 전부)")
	args = parser.parse_args()
	models = args.model or ["gemini-3.5-flash-lite", "gemini-3.8-flash"]

	key = os.environ.get("GEMINI_API_KEY") or (Path.home() / ".gemini_key").read_text().strip()
	# 타임아웃이 없으면 응답 없는 호출에 영원히 매달린다 — 실측에서 4번째 사진에 걸려 멈췄다.
	# 앱은 이보다 훨씬 짧게 끊어야 한다(메뉴 즉답은 3초). 여기서는 재는 게 목적이라 넉넉히.
	client = genai.Client(api_key=key, http_options=types.HttpOptions(timeout=REQUEST_TIMEOUT_MS))

	connection = localdb.connect()
	with connection.cursor() as cursor:
		photos = fetch_dataset(cursor, args.photos)
	connection.close()

	images = {}
	for photo in photos:
		data = download(photo["key"])
		if data:
			images[photo["photo_id"]] = data
	photos = [p for p in photos if p["photo_id"] in images]
	truth_total = sum(len(p["truth"]) for p in photos)
	print(f"사진 {len(photos)}장 · 정답 태그 {truth_total}개 · 가게 {len({p['store_id'] for p in photos})}곳\n")

	rows = []
	for model in models:
		for with_menus in ((True,) if args.menus_only else (True, False)):
			need = args.agree or args.runs
			label = (f"{model} / 메뉴 {'주입' if with_menus else '없음'}"
				+ (f" / {args.runs}회 중 {need}회 일치" if args.runs > 1 else " / 1회"))
			totals = Counter()
			distances = []
			elapsed = []
			last_call = 0.0
			stopped = False
			for index, photo in enumerate(photos, 1):
				# RPM 15 를 넘지 않게 사이를 벌린다 — 넘기면 그날 남은 요청까지 429 로 버린다
				wait = MIN_REQUEST_GAP - (time.time() - last_call)
				if last_call and wait > 0:
					time.sleep(wait)
				last_call = time.time()
				try:
					runs_out, seconds = [], 0.0
					for run in range(args.runs):
						if run:
							gap = MIN_REQUEST_GAP - (time.time() - last_call)
							if gap > 0:
								time.sleep(gap)
							last_call = time.time()
						one, took = ask(client, model, images[photo["photo_id"]], build_prompt(photo, with_menus))
						runs_out.append(one)
						# 실제 서비스는 세 번을 나란히 부른다 — 체감 시간은 가장 느린 하나다
						seconds = max(seconds, took)
				except QuotaExhausted as exhausted:
					print(f"\n한도 소진 — {index - 1}장까지의 결과로 정리한다.\n{exhausted}", flush=True)
					stopped = True
					break
				# 화면에 나갈 것과 채점 대상은 후처리를 거친 결과다 — 앱이 받는 것과 같아야 한다
				menu_name = {menu["id"]: normalize(menu["name"]) for menu in photo["menus"]}
				items = agree(runs_out, menu_name, args.agree or args.runs)
				elapsed.append(seconds)
				result = score(photo, items)
				for field in ("truth_count", "suggested", "hits", "extra", "drink_suggested", "drink_truth"):
					totals[field] += result[field]
				distances += result["distances"]
				rows.append(
					{
						"model": model,
						"menus": "주입" if with_menus else "없음",
						"photo_id": photo["photo_id"],
						"store": photo["store_name"],
						"정답": " | ".join(t["name"] for t in photo["truth"]),
						"제안": " | ".join(
							f"{i.get('name')}({i.get('menuId')})" for i in items
						),
						"맞은수": result["hits"],
						"정답수": result["truth_count"],
						"초": round(seconds, 1),
						# 눈으로 겹쳐 보려고 좌표까지 남긴다 — 이름이 맞아도 엉뚱한 데를 짚을 수 있다
						"정답좌표": json.dumps(
							[{"name": t["name"], "x": t["x"], "y": t["y"]} for t in photo["truth"]],
							ensure_ascii=False),
						"제안좌표": json.dumps(
							[{"name": i.get("name"), "menuId": i.get("menuId"),
								"box": i.get("box_2d"), "point": center_of(i.get("box_2d") or [])}
								for i in items], ensure_ascii=False),
						"key": photo["key"],
					}
				)
				print(f"  [{label}] {index}/{len(photos)} {seconds:.1f}s "
					f"맞음 {result['hits']}/{result['truth_count']}", flush=True)

			accuracy = totals["hits"] / totals["truth_count"] * 100 if totals["truth_count"] else 0
			mean_distance = sum(distances) / len(distances) if distances else float("nan")
			if not elapsed:
				print(f"\n■ {label} — 한 장도 못 돌렸다")
				continue
			ordered = sorted(elapsed)
			print(f"\n■ {label}" + (f"  (한도로 {len(elapsed)}/{len(photos)}장에서 멈춤)" if stopped else f"  ({len(elapsed)}장)"))
			print(f"   응답 시간     중앙 {ordered[len(ordered) // 2]:.1f}s · "
				f"최대 {ordered[-1]:.1f}s  (앱 타임아웃 10초, 메뉴 즉답 기준 3초)")
			print(f"   이름 정확도   {accuracy:5.1f}%  ({totals['hits']}/{totals['truth_count']})")
			print(f"   좌표 오차     {mean_distance:.3f}  (사진 대각선 1.414 기준, 맞은 {len(distances)}쌍)")
			print(f"   제안 개수     사진당 {totals['suggested'] / len(elapsed):.2f}개")
			print(f"   과잉 제안     {totals['extra']}개")
			print(f"   음료 제안     {totals['drink_suggested']}개  (정답에 있던 음료 {totals['drink_truth']}개)")

	if not rows:
		print("\n결과 없음")
		return
	with OUT_CSV.open("w", newline="", encoding="utf-8-sig") as handle:
		writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
		writer.writeheader()
		writer.writerows(rows)
	print(f"\n건별 결과: {OUT_CSV}")


if __name__ == "__main__":
	main()
