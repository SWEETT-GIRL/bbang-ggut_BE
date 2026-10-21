#!/usr/bin/env python3
"""MenuKind 보강 후보 추출 (1회성 분석).

배경: 실측(classify_menu_eval.py) 결과 '빵'/'기타' 에 남는 것의 상당수가 AI 실패가 아니라
      MenuKind 67종에 칸이 없어서였다. confidence 0.9~1.0 으로 '빵' 을 고른 사례:
      단팥빵 / 치즈 사각 카스테라 / 콘치즈 계란빵 / 초코소라빵 / 피자빵 / 델리만쥬 / 고구마크레페

목적: '빵'·'기타' 로 분류된 메뉴를 대량 표집해 **enum 제약 없이** 품목명을 받고,
      기존 67종에 없는 이름을 빈도순으로 집계한다. → enum 보강 범위를 숫자로 정한다.

실행:
  scripts/.venv/bin/python scripts/suggest_menu_kinds.py
  scripts/.venv/bin/python scripts/suggest_menu_kinds.py --sample 1000 --min-count 3
"""

import argparse
import csv
import json
import os
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

import localdb
from google import genai
from google.genai import errors, types

ROOT = Path(__file__).resolve().parent.parent
MENU_KIND_JAVA = ROOT / "core/src/main/java/sweetgirl/croissant/menu/enums/MenuKind.java"
OUT_CSV = Path(__file__).resolve().parent / "menu_kind_suggestions.csv"

JUNK_KINDS = ("빵", "기타")

PROMPT = """너는 한국 베이커리 메뉴를 품목 단위로 정리한다.

아래 메뉴명 각각에 대해 답해라.

1. kind — 그 메뉴가 어떤 '품목'인지 짧은 한국어 명사 하나.
   빵집에서 흔히 쓰는 품목명을 쓴다. 예) 단팥빵, 카스테라, 소금빵, 마카롱, 크레페, 계란빵
   맛·브랜드·수량은 빼고 품목만 남긴다.
     '두바이 초콜릿 크로플' → 크로플
     '이상복경주빵 20개입' → 경주빵
     '치즈 사각 카스테라' → 카스테라
   아래 목록에 있으면 반드시 그 이름을 그대로 쓴다. 없으면 새 품목명을 만들어라.

   품목을 정할 수 없으면 반드시 "불명" 이라고 답한다. 억지로 지어내지 마라.
   '디저트', '과자', '빵' 처럼 뭉뚱그린 말은 쓰지 않는다 — 그럴 바엔 "불명" 이다.

2. isBakeryItem — 빵집·카페의 빵/디저트류면 true.
   음료, 식사류(파스타·리조또·샐러드·피자·튀김), 굿즈·선물세트·안내문구는 false.

기존 품목 목록:
{kinds}

메뉴명:
{items}

입력과 같은 개수를, 같은 index 로 답한다."""

SCHEMA = {
	"type": "OBJECT",
	"properties": {
		"items": {
			"type": "ARRAY",
			"items": {
				"type": "OBJECT",
				"properties": {
					"index": {"type": "INTEGER"},
					"kind": {"type": "STRING"},
					"isBakeryItem": {"type": "BOOLEAN"},
				},
				"required": ["index", "kind", "isBakeryItem"],
			},
		},
	},
	"required": ["items"],
}


def load_kinds() -> list[str]:
	text = MENU_KIND_JAVA.read_text(encoding="utf-8")
	body = text.split("{", 1)[1].rsplit("}", 1)[0]
	return [k.strip() for k in body.replace("\n", "").split(",") if k.strip()]


def load_api_key() -> str:
	key = os.environ.get("GEMINI_API_KEY", "").strip()
	if key:
		return key
	key_file = Path.home() / ".gemini_key"
	if key_file.exists():
		return key_file.read_text(encoding="utf-8").strip()
	sys.exit("GEMINI_API_KEY 가 없다. 환경변수 또는 ~/.gemini_key 에 넣어라.")


def parse_retry_delay(error, default: float = 25.0) -> float:
	"""429 응답의 RetryInfo.retryDelay('21s') 를 초로. 없으면 기본값."""
	try:
		for detail in error.details.get("error", {}).get("details", []):
			delay = detail.get("retryDelay")
			if delay:
				return float(str(delay).rstrip("s")) + 1.0
	except (AttributeError, TypeError, ValueError):
		pass
	return default


def load_or_fetch(count: int, sample_file: Path) -> list[dict]:
	"""표본을 파일에 고정한다 — 프롬프트·모델을 바꿔가며 비교하려면 같은 표본이어야 한다."""
	if sample_file.exists():
		with sample_file.open(encoding="utf-8-sig") as f:
			menus = list(csv.DictReader(f))
		print(f"고정 표본 재사용: {sample_file.name} ({len(menus)}건)")
		return menus
	menus = fetch_junk_menus(count)
	with sample_file.open("w", newline="", encoding="utf-8-sig") as f:
		writer = csv.DictWriter(f, fieldnames=["menu_id", "name", "crawler_kind"])
		writer.writeheader()
		writer.writerows(menus)
	print(f"표본 {len(menus)}건 추출 → {sample_file.name} 에 고정")
	return menus


def fetch_junk_menus(count: int) -> list[dict]:
	with localdb.connect() as conn, conn.cursor() as cur:
		cur.execute(
			"""
			SELECT m.id, m.name, mc.name::text
			FROM menu m
			JOIN menu_category mc ON mc.id = m.menu_category_id
			WHERE m.deleted_at IS NULL
			  AND mc.name::text IN %s
			  AND length(trim(m.name)) > 0
			ORDER BY random()
			LIMIT %s;
			""",
			(JUNK_KINDS, count),
		)
		return [
			{"menu_id": menu_id, "name": name, "crawler_kind": crawler_kind}
			for menu_id, name, crawler_kind in cur.fetchall()
		]


def suggest(client, model: str, kinds: list[str], names: list[str]) -> tuple[dict, dict]:
	listed = "\n".join(f"{i}. {name}" for i, name in enumerate(names))
	prompt = PROMPT.format(kinds=", ".join(kinds), items=listed)
	# 무료 티어는 분당 15요청(RPM). 429 는 응답이 알려주는 대기시간만큼 쉬고 재시도한다.
	for attempt in range(6):
		try:
			response = client.models.generate_content(
				model=model,
				contents=prompt,
				config=types.GenerateContentConfig(
					response_mime_type="application/json",
					response_schema=SCHEMA,
					temperature=0.0,
				),
			)
			break
		except errors.APIError as error:
			if error.code not in (429, 503) or attempt == 5:
				raise
			wait = parse_retry_delay(error) if error.code == 429 else 10
			print(f"    {error.code} — {wait:.0f}초 대기 후 재시도")
			time.sleep(wait)
	else:
		raise RuntimeError("재시도 한도 초과")
	parsed = json.loads(response.text)
	usage = response.usage_metadata
	return (
		{item["index"]: item for item in parsed["items"]},
		{"input": usage.prompt_token_count or 0, "output": usage.candidates_token_count or 0},
	)


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("--sample", type=int, default=1000, help="표집할 '빵'/'기타' 메뉴 수")
	parser.add_argument("--batch", type=int, default=100)
	parser.add_argument("--rpm", type=float, default=13.0,
	                    help="분당 요청 상한. 무료 티어는 15 — 여유를 두고 13")
	parser.add_argument("--model", default="gemini-3.5-flash-lite")
	parser.add_argument("--min-count", type=int, default=3, help="후보로 볼 최소 등장 횟수")
	parser.add_argument("--sample-file", default="menu_kind_sample.csv",
	                    help="고정 표본 파일 — 있으면 재사용, 없으면 만들어 저장")
	args = parser.parse_args()

	existing = load_kinds()
	existing_set = set(existing)
	print(f"기존 MenuKind {len(existing)}종")

	menus = load_or_fetch(args.sample, Path(__file__).resolve().parent / args.sample_file)

	client = genai.Client(api_key=load_api_key())
	usage_total = Counter()
	began = time.time()

	for start in range(0, len(menus), args.batch):
		chunk = menus[start:start + args.batch]
		results, usage = suggest(client, args.model, existing, [m["name"] for m in chunk])
		usage_total.update(usage)
		for offset, menu in enumerate(chunk):
			result = results.get(offset, {})
			menu["suggested_kind"] = (result.get("kind") or "").strip()
			menu["is_bakery"] = result.get("isBakeryItem")
		if (start // args.batch) % 4 == 3 or start + args.batch >= len(menus):
			print(f"  {min(start + args.batch, len(menus))}/{len(menus)}")
		if start + args.batch < len(menus) and args.rpm > 0:
			time.sleep(60.0 / args.rpm)   # 무료 티어 RPM 을 넘지 않게 미리 간격을 둔다

	report(menus, existing_set, args.min_count, usage_total, time.time() - began, args.model)

	with OUT_CSV.open("w", newline="", encoding="utf-8-sig") as f:
		writer = csv.DictWriter(
			f, fieldnames=["menu_id", "name", "crawler_kind", "suggested_kind", "is_bakery"])
		writer.writeheader()
		writer.writerows(menus)
	print(f"\n전체 결과: {OUT_CSV}")


def report(menus, existing_set, min_count, usage, elapsed, model):
	bakery = [m for m in menus if m["is_bakery"] is True]
	non_bakery = [m for m in menus if m["is_bakery"] is False]

	new_kinds = Counter(m["suggested_kind"] for m in bakery
	                    if m["suggested_kind"] and m["suggested_kind"] not in existing_set)
	hit_existing = [m for m in bakery if m["suggested_kind"] in existing_set]

	examples = defaultdict(list)
	for menu in bakery:
		if menu["suggested_kind"] in new_kinds:
			examples[menu["suggested_kind"]].append(menu["name"])

	print("\n" + "=" * 66)
	print(f"모델 {model} · {elapsed:.0f}초 · 입력 {usage['input']:,} / 출력 {usage['output']:,} 토큰")
	print("=" * 66)
	print(f"표본 {len(menus)}건 → 빵 {len(bakery)} / 비-빵 {len(non_bakery)} "
	      f"({100 * len(non_bakery) / len(menus):.0f}%)")
	print(f"빵 중 기존 MenuKind 로 해결 가능: {len(hit_existing)} "
	      f"({100 * len(hit_existing) / len(bakery):.0f}%)  ← 지금 '빵/기타' 에 잘못 갇힌 것")
	print(f"새 품목명 종류: {len(new_kinds)}개, 그중 {min_count}회 이상 등장: "
	      f"{sum(1 for _, c in new_kinds.items() if c >= min_count)}개")

	covered = sum(c for _, c in new_kinds.most_common() if c >= min_count)
	print(f"{min_count}회 이상 후보만 enum 에 넣으면 빵 표본의 "
	      f"{100 * (len(hit_existing) + covered) / len(bakery):.0f}% 를 커버")

	print(f"\n── 신규 품목 후보 ({min_count}회 이상, 빈도순) ──")
	for kind, count in new_kinds.most_common():
		if count < min_count:
			continue
		sample = " / ".join(examples[kind][:3])
		print(f"  {count:>4}  {kind:<12} {sample[:56]}")

	rare = [(k, c) for k, c in new_kinds.most_common() if c < min_count]
	if rare:
		print(f"\n── {min_count}회 미만 (참고, {len(rare)}종) ──")
		print("  " + ", ".join(f"{k}({c})" for k, c in rare[:40]))


if __name__ == "__main__":
	main()
