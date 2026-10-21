#!/usr/bin/env python3
"""메뉴명 AI 분류 정확도 실측 (1회성 검증).

목적: "Gemini 가 크롤러 규칙 48개(naver_match.CATEGORY_RULES)보다 나은가" 를 숫자로 확인.
설계 문서의 3-2(BE 분류 스케줄러)가 이 가정 위에 서 있어 착수 전에 검증한다.

샘플 두 종류:
  - fallback: 크롤러가 실패해 '빵'/'기타' 로 떨어진 것 (AI 가 실제로 맡게 될 대상)
  - rule    : 크롤러 규칙이 맞춘 것 (AI 가 이미 맞는 걸 틀리지 않는지 회귀 체크)

자동 산출 지표(사람 채점 없이 나오는 것):
  - 구제율   : fallback 중 AI 가 '빵'/'기타' 아닌 구체 분류를 준 비율
  - 회귀유지 : rule 중 AI 가 크롤러와 같은 kind 를 준 비율
  - 비-빵 판별: isBakeryItem=false 비율 (음료·식사류·굿즈)
CSV 의 `정답` 열은 비워 두니 눈으로 채점해 최종 정확도를 낸다.

사전 준비:
  1) 운영 DB 터널 (application-local.properties 의 spring.datasource.url 이 localhost:15432 를 가리킨다)
     ssh -i <pem> -f -N -L 15432:<prod RDS>:5432 ubuntu@<bastion>
  2) API 키 — 환경변수 GEMINI_API_KEY 또는 ~/.gemini_key 파일

실행:
  scripts/.venv/bin/python scripts/classify_menu_eval.py
  scripts/.venv/bin/python scripts/classify_menu_eval.py --fallback 150 --rule 50 --model gemini-2.5-flash-lite
"""

import argparse
import csv
import json
import os
import re
import sys
import time
from collections import Counter
from pathlib import Path

import localdb
from google import genai
from google.genai import types

ROOT = Path(__file__).resolve().parent.parent
MENU_KIND_JAVA = ROOT / "core/src/main/java/sweetgirl/croissant/menu/enums/MenuKind.java"
OUT_CSV = Path(__file__).resolve().parent / "menu_ai_eval.csv"

# 크롤러가 분류에 실패했을 때 떨어지는 통 — naver_match.DEFAULT_CATEGORY 및 과거 유입분
JUNK_KINDS = ("빵", "기타")


PROMPT = """너는 한국 베이커리 메뉴를 분류한다.

아래 메뉴명 각각에 대해 세 가지를 판단해라.

1. kind — 주어진 분류 목록 중 가장 구체적으로 들어맞는 것 하나.
   '빵'과 '기타'는 정말로 다른 어떤 것에도 해당하지 않을 때만 쓴다.
   예) '소금버터빵' → 소금빵 (빵 아님), '뺑오쇼콜라' → 페이스트리, '튀소구마' → 소금빵

2. isBakeryItem — 빵집·카페에서 파는 '빵/디저트류'면 true.
   음료(커피·차·주스·스무디), 식사류(파스타·리조또·샐러드·튀김),
   굿즈·선물세트·식재료는 false. false 여도 kind 는 가장 가까운 값을 고른다.
   예) '허니레몬차' → false, '알리오 올리오' → false, '미미선물세트' → false

3. confidence — 0.0~1.0. 메뉴명만으로 확신이 서지 않으면 낮게.

분류 목록:
{kinds}

메뉴명:
{items}

입력과 같은 개수를, 같은 index 로 답한다."""


def load_kinds() -> list[str]:
	"""MenuKind.java 에서 enum 상수를 읽는다 — 하드코딩 방지."""
	text = MENU_KIND_JAVA.read_text(encoding="utf-8")
	body = text.split("{", 1)[1].rsplit("}", 1)[0]
	kinds = [k.strip() for k in body.replace("\n", "").split(",")]
	return [k for k in kinds if k]


def load_api_key() -> str:
	key = os.environ.get("GEMINI_API_KEY", "").strip()
	if key:
		return key
	key_file = Path.home() / ".gemini_key"
	if key_file.exists():
		return key_file.read_text(encoding="utf-8").strip()
	sys.exit("GEMINI_API_KEY 가 없다. 환경변수로 넣거나 ~/.gemini_key 파일에 저장해라.")


def load_or_fetch(fallback_count: int, rule_count: int, seed: float, sample_file: Path) -> list[dict]:
	"""표본을 파일에 고정 — setseed 는 병렬 스캔에서 재현이 깨져 실험 간 비교가 안 된다."""
	if sample_file.exists():
		with sample_file.open(encoding="utf-8-sig") as f:
			samples = list(csv.DictReader(f))
		print(f"고정 표본 재사용: {sample_file.name} ({len(samples)}건)")
		return samples
	samples = fetch_samples(fallback_count, rule_count, seed)
	with sample_file.open("w", newline="", encoding="utf-8-sig") as f:
		writer = csv.DictWriter(f, fieldnames=["menu_id", "name", "crawler_kind", "group"])
		writer.writeheader()
		writer.writerows(samples)
	print(f"표본 {len(samples)}건 추출 → {sample_file.name} 에 고정")
	return samples


def fetch_samples(fallback_count: int, rule_count: int, seed: float) -> list[dict]:
	"""운영 DB 에서 두 그룹을 랜덤 추출. seed 고정으로 재실행 시 같은 표본."""
	rows: list[dict] = []
	with localdb.connect() as conn, conn.cursor() as cur:
		for group, count, operator in (
			("fallback", fallback_count, "IN"),
			("rule", rule_count, "NOT IN"),
		):
			cur.execute("SELECT setseed(%s);", (seed,))
			cur.execute(
				f"""
				SELECT m.id, m.name, mc.name::text
				FROM menu m
				JOIN menu_category mc ON mc.id = m.menu_category_id
				WHERE m.deleted_at IS NULL
				  AND mc.name::text {operator} %s
				  AND length(trim(m.name)) > 0
				ORDER BY random()
				LIMIT %s;
				""",
				(JUNK_KINDS, count),
			)
			for menu_id, name, crawler_kind in cur.fetchall():
				rows.append({
					"menu_id": menu_id,
					"name": name,
					"crawler_kind": crawler_kind,
					"group": group,
				})
	return rows


def build_schema(kinds: list[str]) -> dict:
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
						"isBakeryItem": {"type": "BOOLEAN"},
						"confidence": {"type": "NUMBER"},
					},
					"required": ["index", "kind", "isBakeryItem", "confidence"],
				},
			},
		},
		"required": ["items"],
	}


def classify(client, model: str, kinds: list[str], names: list[str]) -> tuple[dict, dict]:
	"""한 배치를 분류. (index → 결과, 토큰 사용량) 반환."""
	listed = "\n".join(f"{i}. {name}" for i, name in enumerate(names))
	prompt = PROMPT.format(kinds=", ".join(kinds), items=listed)
	response = client.models.generate_content(
		model=model,
		contents=prompt,
		config=types.GenerateContentConfig(
			response_mime_type="application/json",
			response_schema=build_schema(kinds),
			temperature=0.0,
		),
	)
	parsed = json.loads(response.text)
	usage = response.usage_metadata
	return (
		{item["index"]: item for item in parsed["items"]},
		{
			"input": usage.prompt_token_count or 0,
			"output": usage.candidates_token_count or 0,
		},
	)


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("--fallback", type=int, default=150, help="크롤러 실패분 표본 수")
	parser.add_argument("--rule", type=int, default=50, help="크롤러 성공분 표본 수")
	parser.add_argument("--batch", type=int, default=50, help="한 번에 보낼 메뉴 수")
	# 2.5-flash-lite 는 신규 사용자에게 닫혔다(404). 현재 최저가 티어는 3.5-flash-lite.
	parser.add_argument("--model", default="gemini-3.5-flash-lite")
	parser.add_argument("--seed", type=float, default=0.42, help="표본 재현용 시드")
	parser.add_argument("--sample-file", default="menu_eval_sample.csv",
	                    help="고정 표본 파일 — 있으면 재사용")
	parser.add_argument("--rpm", type=float, default=13.0, help="분당 요청 상한(무료 티어 15)")
	parser.add_argument("--runs", type=int, default=1,
	                    help="같은 표본을 N회 분류해 자기일관성을 잰다")
	parser.add_argument("--extra-kinds", default="",
	                    help="enum 보강 효과 측정용. 쉼표로 구분한 후보 품목 (MenuKind.java 는 안 건드림)")
	args = parser.parse_args()

	kinds = load_kinds()
	extra = [k.strip() for k in args.extra_kinds.split(",") if k.strip()]
	if extra:
		kinds = kinds + [k for k in extra if k not in kinds]
		print(f"MenuKind {len(kinds) - len(extra)}종 + 후보 {len(extra)}종 = {len(kinds)}종")
	else:
		print(f"MenuKind {len(kinds)}종 로드")

	samples = load_or_fetch(args.fallback, args.rule, args.seed,
	                        Path(__file__).resolve().parent / args.sample_file)
	print(f"표본 {len(samples)}건 추출 "
	      f"(fallback {sum(1 for s in samples if s['group'] == 'fallback')} / "
	      f"rule {sum(1 for s in samples if s['group'] == 'rule')})")

	client = genai.Client(api_key=load_api_key())
	total_usage = Counter()
	elapsed = 0.0

	for run in range(args.runs):
		for start in range(0, len(samples), args.batch):
			chunk = samples[start:start + args.batch]
			began = time.time()
			results, usage = classify(client, args.model, kinds, [s["name"] for s in chunk])
			elapsed += time.time() - began
			total_usage.update(usage)
			for offset, sample in enumerate(chunk):
				result = results.get(offset, {})
				sample.setdefault("runs", []).append(result.get("kind", ""))
				sample["ai_kind"] = result.get("kind", "")
				sample["ai_is_bakery"] = result.get("isBakeryItem", "")
				sample["ai_confidence"] = result.get("confidence", "")
			if args.rpm > 0:
				time.sleep(60.0 / args.rpm)
		print(f"  run {run + 1}/{args.runs} 완료")

	if args.runs > 1:
		consistency_report(samples)

	with OUT_CSV.open("w", newline="", encoding="utf-8-sig") as f:
		writer = csv.DictWriter(f, fieldnames=[
			"menu_id", "name", "group", "crawler_kind",
			"ai_kind", "ai_is_bakery", "ai_confidence", "runs", "일관", "정답", "비고",
		])
		writer.writeheader()
		for sample in samples:
			runs = sample.get("runs", [])
			writer.writerow({**sample,
			                 "runs": " / ".join(runs),
			                 "일관": "" if len(runs) < 2 else ("O" if len(set(runs)) == 1 else "X"),
			                 "정답": "", "비고": ""})

	report(samples, total_usage, elapsed, args.model)
	print(f"\n채점용 CSV: {OUT_CSV}")


def report(samples: list[dict], usage: Counter, elapsed: float, model: str) -> None:
	fallback = [s for s in samples if s["group"] == "fallback"]
	rule = [s for s in samples if s["group"] == "rule"]

	fallback_bread = [s for s in fallback if s["ai_is_bakery"] is True]
	rescued = [s for s in fallback_bread if s["ai_kind"] and s["ai_kind"] not in JUNK_KINDS]
	kept = [s for s in rule if s["ai_kind"] == s["crawler_kind"]]
	non_bakery = [s for s in samples if s["ai_is_bakery"] is False]
	low_conf = [s for s in samples
	            if isinstance(s["ai_confidence"], (int, float)) and s["ai_confidence"] < 0.6]

	def pct(part: list, whole: list) -> str:
		return f"{len(part)}/{len(whole)} ({100 * len(part) / len(whole):.1f}%)" if whole else "-"

	print("\n" + "=" * 60)
	print(f"모델 {model} · {elapsed:.1f}초 · 입력 {usage['input']:,} / 출력 {usage['output']:,} 토큰")
	print("=" * 60)
	print(f"fallback  빵 {len(fallback_bread)} / 비-빵 {len(fallback) - len(fallback_bread)}")
	print(f"구제율    (빵 중 구체 분류)      {pct(rescued, fallback_bread)}")
	print(f"회귀유지  (rule → 크롤러와 동일)  {pct(kept, rule)}")
	print(f"비-빵 판별 (isBakeryItem=false)   {pct(non_bakery, samples)}")
	print(f"저신뢰    (confidence < 0.6)      {pct(low_conf, samples)}")

	print("\n─ fallback 이 어디로 갔나 (상위 15) ─")
	for kind, count in Counter(s["ai_kind"] for s in fallback).most_common(15):
		print(f"  {kind:<12} {count}")

	mismatch = [s for s in rule if s["ai_kind"] != s["crawler_kind"]]
	if mismatch:
		print(f"\n─ 크롤러와 다르게 본 것 {len(mismatch)}건 (앞 15) ─")
		for sample in mismatch[:15]:
			print(f"  {sample['name'][:28]:<30} 크롤러={sample['crawler_kind']:<8} "
			      f"AI={sample['ai_kind']} ({sample['ai_confidence']})")

def consistency_report(samples) -> None:
	"""여러 번 돌렸을 때 답이 갈리는지 — confidence 보다 믿을 만한 위험 신호."""
	stable, unstable = [], []
	for sample in samples:
		(stable if len(set(sample["runs"])) == 1 else unstable).append(sample)

	def mean_conf(rows):
		values = [float(r["ai_confidence"]) for r in rows
		          if isinstance(r["ai_confidence"], (int, float))]
		return sum(values) / len(values) if values else 0

	total = len(samples)
	print("\n" + "=" * 62)
	print(f"자기일관성 — {len(samples[0]['runs'])}회 반복")
	print("=" * 62)
	print(f"매번 같은 답  {len(stable)}/{total} ({100 * len(stable) / total:.1f}%)"
	      f"   평균 conf {mean_conf(stable):.2f}")
	print(f"답이 갈림    {len(unstable)}/{total} ({100 * len(unstable) / total:.1f}%)"
	      f"   평균 conf {mean_conf(unstable):.2f}")

	high_conf_unstable = [s for s in unstable
	                      if isinstance(s["ai_confidence"], (int, float))
	                      and s["ai_confidence"] >= 0.8]
	print(f"\n▶ 갈렸는데 confidence 0.8 이상: {len(high_conf_unstable)}건 "
	      f"— confidence 로는 못 거르는 것들")
	for sample in unstable[:20]:
		print(f"  {sample['name'][:30]:<32} {' / '.join(sample['runs'])}"
		      f"   (conf {sample['ai_confidence']})")

if __name__ == "__main__":
	main()
