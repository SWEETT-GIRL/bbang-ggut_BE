#!/usr/bin/env python3
"""망원동 빵집 블로그 점수 PoC 오케스트레이터 (룰 기반, LLM 없음).

흐름: 스냅샷 로드 → 가게별 블로그 검색/본문 스크래핑 → 협찬제외·감성·카테고리 분석
      → §3 점수공식 → HTML 리포트.

사용:
    .venv/bin/python -m blog_score.run_poc --limit 15            # 서브셋 검증
    .venv/bin/python -m blog_score.run_poc                       # 전체 168
옵션:
    --limit N            가게 수 제한(기본 전체)
    --posts-per-store N  가게당 검색 글 수(기본 20)
    --no-body            본문 스크래핑 생략(스니펫만, 빠름·협찬판별 약함)
    --no-review          플레이스 리뷰수 스크래핑 생략
"""
import argparse
import json
import sys
from datetime import date, datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from blog_score import analyze, naver_scrape as ns, report, scoring  # noqa: E402
from blog_score.dictionaries import CATEGORY_KEYWORDS  # noqa: E402

BASE = Path(__file__).resolve().parent
SNAP_DIR = BASE / "snapshots"
OUT_DIR = BASE.parent.parent / "docs" / "reports"


def latest_snapshot() -> Path:
	snaps = sorted(SNAP_DIR.glob("mangwon_stores_*.json"))
	if not snaps:
		sys.exit("스냅샷이 없습니다. 먼저 export_mangwon_snapshot.py 실행")
	return snaps[-1]


def _name_key(name: str) -> str:
	"""가게명 매칭 키 — 첫 토큰(지점명 제외) 공백제거 소문자. 예: '후와후와 모치점'→'후와후와'."""
	tok = name.split()[0] if name.split() else name
	return tok.replace(" ", "").lower()


def crawl_store(store: dict, *, today: date, posts_per_store: int,
				use_body: bool, use_review: bool) -> dict:
	query = f"망원동 {store['name']}"
	found = ns.search_posts(query, today=today, max_posts=posts_per_store)
	posts = []
	for p in found:
		text = p["title"] + " " + p["snippet"]
		if use_body:
			body = ns.fetch_body(p["url"])
			if body:
				text = p["title"] + " " + body
		is_ad, reason = analyze.detect_ad(text)
		senti, mult = analyze.sentiment(text)
		cats = analyze.category_hits(text)
		posts.append({**p, "text_norm": text.replace(" ", "").lower(),
					  "is_ad": is_ad, "ad_reason": reason,
					  "sentiment": senti, "senti_mult": mult, "cats": cats})
	review = ns.fetch_review_count(store.get("naver_place_id")) if use_review else None
	return {"posts": posts, "review_count": review}


def assign_focus(per_store: dict, all_stores: list[dict], this_key: dict) -> None:
	"""묶음글 분배: 글 1건의 점수를 그 글이 언급한 가게들에 가게명 언급 비율로 분배.
	focus = (이 가게 언급수) / (글 내 전체 가게명 언급수). 본 가게 미언급 글은 focus=0(제외).
	text_norm은 점수계산 후 제거(리포트 JSON 경량화)."""
	keys = {s["store_id"]: _name_key(s["name"]) for s in all_stores}
	all_keys = list({k for k in keys.values() if len(k) >= 2})
	for sid, res in per_store.items():
		mine = this_key[sid]
		for p in res["posts"]:
			t = p.pop("text_norm", "")
			mine_cnt = t.count(mine) if len(mine) >= 2 else 0
			total = sum(t.count(k) for k in all_keys)
			p["focus"] = round(mine_cnt / total, 3) if (mine_cnt and total) else 0.0


def main() -> int:
	ap = argparse.ArgumentParser()
	ap.add_argument("--limit", type=int, default=None)
	ap.add_argument("--posts-per-store", type=int, default=20)
	ap.add_argument("--no-body", action="store_true")
	ap.add_argument("--no-review", action="store_true")
	args = ap.parse_args()

	today = datetime.now().date()
	snap = json.loads(latest_snapshot().read_text(encoding="utf-8"))
	stores = snap["stores"][: args.limit] if args.limit else snap["stores"]
	print(f"대상 {len(stores)}곳 · posts/store={args.posts_per_store} · "
		  f"body={'off' if args.no_body else 'on'} · review={'off' if args.no_review else 'on'}")

	per_store = {}        # sid -> {posts, review_count}
	total_posts = ad_excluded = 0
	for i, s in enumerate(stores, 1):
		res = crawl_store(s, today=today, posts_per_store=args.posts_per_store,
						  use_body=not args.no_body, use_review=not args.no_review)
		per_store[s["store_id"]] = res
		total_posts += len(res["posts"])
		ad_excluded += sum(1 for p in res["posts"] if p["is_ad"])
		print(f"  [{i}/{len(stores)}] {s['name']}: 글 {len(res['posts'])} "
			  f"(광고 {sum(1 for p in res['posts'] if p['is_ad'])}) 리뷰 {res['review_count']}")

	# 묶음글 분배 가중(focus) — 본 가게 미언급 글 제외 + 점수 비율 분배
	# 분모(묶음글 탐지)는 전체 168개 가게명 사용
	this_key = {s["store_id"]: _name_key(s["name"]) for s in stores}
	assign_focus(per_store, snap["stores"], this_key)

	# ── 점수 계산 ───────────────────────────────────────────
	# 1) 가게×카테고리 ScoreQ + n_c
	score_q = {c: {} for c in CATEGORY_KEYWORDS}
	n_c_map = {c: {} for c in CATEGORY_KEYWORDS}
	recent_map = {c: {} for c in CATEGORY_KEYWORDS}
	for s in stores:
		sid = s["store_id"]
		clean = [p for p in per_store[sid]["posts"] if not p["is_ad"]]
		q = scoring.quality(per_store[sid]["review_count"])
		for cat in CATEGORY_KEYWORDS:
			raw, n_c = scoring.raw_cat_score(clean, cat, today=today)
			if n_c:
				score_q[cat][sid] = raw * q
				n_c_map[cat][sid] = n_c
				recent_map[cat][sid] = sum(
					1 for p in clean if cat in p["cats"] and p.get("focus", 0) > 0 and p["date"]
					and (today - date(*map(int, p["date"].split("-")))).days <= 90)

	# 2) 카테고리 평균 → 베이지안 수축 → 정규화
	categories = {}
	store_by_id = {s["store_id"]: s for s in stores}
	for cat in CATEGORY_KEYWORDS:
		sq = score_q[cat]
		if not sq:
			categories[cat] = []
			continue
		mean = sum(sq.values()) / len(sq)
		final = {sid: scoring.final_cat_score(v, n_c_map[cat][sid], mean) for sid, v in sq.items()}
		norm = scoring.normalize(final)
		rows = []
		for sid, fv in final.items():
			st = store_by_id[sid]
			mention = sum(1 for p in per_store[sid]["posts"]
						  if not p["is_ad"] and cat in p["cats"] and p.get("focus", 0) > 0)
			rows.append({"store_id": sid, "name": st["name"], "address": st.get("address", ""),
						 "final_score": round(fv, 3), "normalized": norm[sid],
						 "mention_count": mention, "recent_count": recent_map[cat].get(sid, 0),
						 "review_count": per_store[sid]["review_count"]})
		rows.sort(key=lambda r: r["normalized"], reverse=True)
		categories[cat] = rows

	# ── 출력 ────────────────────────────────────────────────
	run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
	posts_by_store = {str(sid): per_store[sid]["posts"] for sid in per_store}
	results = {"run": run_id, "snapshot_at": snap.get("snapshot_at"),
			   "store_count": len(stores), "total_posts": total_posts,
			   "ad_excluded": ad_excluded, "categories": categories,
			   "posts_by_store": posts_by_store}

	OUT_DIR.mkdir(parents=True, exist_ok=True)
	html_path = OUT_DIR / f"mangwon_blog_score_{run_id}.html"
	html_path.write_text(report.build_html(results), encoding="utf-8")
	json_path = OUT_DIR / f"mangwon_blog_score_{run_id}.json"
	json_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

	print(f"\n완료: 글 {total_posts} (광고제외 {ad_excluded})")
	print(f"  HTML: {html_path}")
	print(f"  JSON: {json_path}")
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
