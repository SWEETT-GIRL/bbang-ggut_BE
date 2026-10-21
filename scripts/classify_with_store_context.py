#!/usr/bin/env python3
"""가게 컨텍스트가 메뉴 분류 정확도를 올리는지 검증 (1회성).

문제: '무화과크림치즈' 같은 이름은 메뉴명만으로 빵 종류를 알 수 없다.
      (실측에서 '호두랑 호두', '도도 플레인' 등이 confidence 0.5)

가설: 같은 가게의 다른 메뉴를 함께 보여주면 맞출 수 있다.
      '무화과크림치즈' + 그 가게에 '플레인 소금빵/말차 소금빵' 이 있으면 → 소금빵

비교:
  A) 메뉴명만 단독으로 분류 (현행 방식)
  B) 가게 이름 + 그 가게 메뉴 전체를 컨텍스트로 주고 분류

실행:
  scripts/.venv/bin/python scripts/classify_with_store_context.py --stores 20
"""

import argparse
import csv
import json
import os
import sys
import time
from collections import Counter
from pathlib import Path

import localdb
from google import genai
from google.genai import errors, types

ROOT = Path(__file__).resolve().parent.parent
MENU_KIND_JAVA = ROOT / "core/src/main/java/sweetgirl/croissant/menu/enums/MenuKind.java"
OUT_CSV = Path(__file__).resolve().parent / "store_context_eval.csv"
SAMPLE_FILE = Path(__file__).resolve().parent / "store_context_sample.csv"

JUNK_KINDS = ("빵", "기타")

PROMPT_PLAIN = """너는 한국 베이커리 메뉴를 분류한다.

각 메뉴에 대해 답해라.
1. kind — 분류 목록 중 가장 구체적으로 맞는 것 하나. '빵'·'기타'는 최후의 수단.
2. confidence — 0.0~1.0. 메뉴명만으로 확신이 안 서면 낮게.

분류 목록:
{kinds}

메뉴명:
{items}

입력과 같은 개수를, 같은 index 로 답한다."""

PROMPT_CONTEXT = """너는 한국 베이커리 메뉴를 분류한다.

가게: {store_name}
이 가게가 파는 메뉴 전체:
{all_menus}

위 맥락을 근거로 아래 메뉴를 분류해라.
같은 가게 메뉴들이 한 종류의 변형인 경우가 많다 —
예) '플레인 소금빵'·'말차 소금빵' 이 있는 가게의 '무화과크림치즈' 는 소금빵일 가능성이 높다.

1. kind — 분류 목록 중 가장 구체적으로 맞는 것 하나. '빵'·'기타'는 최후의 수단.
2. confidence — 0.0~1.0.

분류 목록:
{kinds}

분류할 메뉴명:
{items}

입력과 같은 개수를, 같은 index 로 답한다."""


def build_schema(kinds):
	return {
		"type": "OBJECT",
		"properties": {
			"items": {
				"type": "ARRAY",
				"items": {
					"type": "OBJECT",
					"properties": {
						"index": {"type": "INTEGER"},
						"kind": {"type": "STRING", "enum": kinds},
						"confidence": {"type": "NUMBER"},
					},
					"required": ["index", "kind", "confidence"],
				},
			},
		},
		"required": ["items"],
	}


def load_kinds():
	text = MENU_KIND_JAVA.read_text(encoding="utf-8")
	body = text.split("{", 1)[1].rsplit("}", 1)[0]
	return [k.strip() for k in body.replace("\n", "").split(",") if k.strip()]


def load_api_key():
	key = os.environ.get("GEMINI_API_KEY", "").strip()
	if key:
		return key
	key_file = Path.home() / ".gemini_key"
	if key_file.exists():
		return key_file.read_text(encoding="utf-8").strip()
	sys.exit("GEMINI_API_KEY 가 없다.")


def fetch_stores(store_count: int):
	"""'빵'/'기타' 가 섞여 있으면서 메뉴가 충분히 많은 가게를 고른다."""
	if SAMPLE_FILE.exists():
		with SAMPLE_FILE.open(encoding="utf-8-sig") as f:
			rows = list(csv.DictReader(f))
		stores = {}
		for row in rows:
			stores.setdefault(row["store_id"], {"name": row["store_name"], "menus": []})
			stores[row["store_id"]]["menus"].append(
				{"menu_id": row["menu_id"], "name": row["name"], "crawler_kind": row["crawler_kind"]})
		print(f"고정 표본 재사용: 가게 {len(stores)}곳")
		return stores

	with localdb.connect() as conn, conn.cursor() as cur:
		cur.execute(
			"""
			WITH target AS (
				SELECT m.store_id
				FROM menu m JOIN menu_category mc ON mc.id = m.menu_category_id
				WHERE m.deleted_at IS NULL
				GROUP BY m.store_id
				HAVING count(*) FILTER (WHERE mc.name::text IN %s) >= 3
				   AND count(*) BETWEEN 8 AND 40
				ORDER BY random()
				LIMIT %s
			)
			SELECT s.id, s.name, m.id, m.name, mc.name::text
			FROM target t
			JOIN store s ON s.id = t.store_id
			JOIN menu m ON m.store_id = t.store_id AND m.deleted_at IS NULL
			JOIN menu_category mc ON mc.id = m.menu_category_id
			ORDER BY s.id, m.id;
			""",
			(JUNK_KINDS, store_count),
		)
		stores = {}
		for store_id, store_name, menu_id, menu_name, crawler_kind in cur.fetchall():
			key = str(store_id)
			stores.setdefault(key, {"name": store_name, "menus": []})
			stores[key]["menus"].append(
				{"menu_id": str(menu_id), "name": menu_name, "crawler_kind": crawler_kind})

	with SAMPLE_FILE.open("w", newline="", encoding="utf-8-sig") as f:
		writer = csv.DictWriter(
			f, fieldnames=["store_id", "store_name", "menu_id", "name", "crawler_kind"])
		writer.writeheader()
		for store_id, store in stores.items():
			for menu in store["menus"]:
				writer.writerow({"store_id": store_id, "store_name": store["name"], **menu})
	print(f"가게 {len(stores)}곳 추출 → {SAMPLE_FILE.name} 에 고정")
	return stores


def call(client, model, kinds, prompt, rpm):
	for attempt in range(6):
		try:
			response = client.models.generate_content(
				model=model, contents=prompt,
				config=types.GenerateContentConfig(
					response_mime_type="application/json",
					response_schema=build_schema(kinds),
					temperature=0.0),
			)
			if rpm > 0:
				time.sleep(60.0 / rpm)
			return {item["index"]: item for item in json.loads(response.text)["items"]}
		except errors.APIError as error:
			if error.code not in (429, 503) or attempt == 5:
				raise
			time.sleep(25)
	raise RuntimeError("재시도 한도 초과")


def main():
	parser = argparse.ArgumentParser()
	parser.add_argument("--stores", type=int, default=20)
	parser.add_argument("--model", default="gemini-3.5-flash-lite")
	parser.add_argument("--rpm", type=float, default=13.0)
	args = parser.parse_args()

	kinds = load_kinds()
	stores = fetch_stores(args.stores)
	client = genai.Client(api_key=load_api_key())

	rows = []
	for order, (store_id, store) in enumerate(stores.items(), 1):
		targets = [m for m in store["menus"] if m["crawler_kind"] in JUNK_KINDS]
		if not targets:
			continue
		names = [m["name"] for m in targets]
		listed = "\n".join(f"{i}. {n}" for i, n in enumerate(names))

		plain = call(client, args.model, kinds,
		             PROMPT_PLAIN.format(kinds=", ".join(kinds), items=listed), args.rpm)
		context = call(client, args.model, kinds,
		               PROMPT_CONTEXT.format(
			               store_name=store["name"],
			               all_menus="\n".join(f"- {m['name']}" for m in store["menus"]),
			               kinds=", ".join(kinds), items=listed), args.rpm)

		for offset, menu in enumerate(targets):
			a, b = plain.get(offset, {}), context.get(offset, {})
			rows.append({
				"store_id": store_id, "store_name": store["name"], "name": menu["name"],
				"plain_kind": a.get("kind", ""), "plain_conf": a.get("confidence", 0),
				"ctx_kind": b.get("kind", ""), "ctx_conf": b.get("confidence", 0),
			})
		print(f"  [{order}/{len(stores)}] {store['name'][:20]} — 대상 {len(targets)}건")

	report(rows)
	with OUT_CSV.open("w", newline="", encoding="utf-8-sig") as f:
		writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
		writer.writeheader()
		writer.writerows(rows)
	print(f"\n전체 결과: {OUT_CSV}")


def report(rows):
	def specific(kind):
		return kind and kind not in JUNK_KINDS

	plain_spec = [r for r in rows if specific(r["plain_kind"])]
	ctx_spec = [r for r in rows if specific(r["ctx_kind"])]
	changed = [r for r in rows if r["plain_kind"] != r["ctx_kind"]]
	gained = [r for r in changed if not specific(r["plain_kind"]) and specific(r["ctx_kind"])]
	lost = [r for r in changed if specific(r["plain_kind"]) and not specific(r["ctx_kind"])]

	avg = lambda key: sum(float(r[key] or 0) for r in rows) / len(rows) if rows else 0

	print("\n" + "=" * 62)
	print(f"대상 메뉴 {len(rows)}건 ('빵'·'기타' 로 들어온 것)")
	print("=" * 62)
	print(f"구체 분류    단독 {len(plain_spec)} ({100*len(plain_spec)/len(rows):.0f}%)"
	      f"  →  가게 컨텍스트 {len(ctx_spec)} ({100*len(ctx_spec)/len(rows):.0f}%)")
	print(f"평균 confidence  단독 {avg('plain_conf'):.2f}  →  컨텍스트 {avg('ctx_conf'):.2f}")
	print(f"분류가 바뀐 것 {len(changed)}건 (구체화 {len(gained)} / 후퇴 {len(lost)})")

	if gained:
		print("\n── 컨텍스트로 새로 잡아낸 것 (앞 20) ──")
		for r in gained[:20]:
			print(f"  [{r['store_name'][:14]:<15}] {r['name'][:24]:<26} "
			      f"{r['plain_kind']} → {r['ctx_kind']} ({r['ctx_conf']})")
	if lost:
		print("\n── 컨텍스트 때문에 후퇴한 것 ──")
		for r in lost[:10]:
			print(f"  [{r['store_name'][:14]:<15}] {r['name'][:24]:<26} "
			      f"{r['plain_kind']} → {r['ctx_kind']}")


if __name__ == "__main__":
	main()
