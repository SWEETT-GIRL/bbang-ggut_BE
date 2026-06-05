#!/usr/bin/env python3
"""베스트프랙티스 합성 점수 + 카테고리 순위 (LLM 없음).

입력: crawl_multi의 본문 캐시(JSONL) + 스냅샷(가게명/place_id) + 리뷰수.
방법(다신호 랭킹 정석):
  1) 근접 귀속(analyze_window)으로 가게별·카테고리별 신호 집계
  2) log1p 변환(리뷰·언급) → 신호별 z-score 표준화
  3) 가중 합성(인기도 비중↑)  4) 베이지안 수축(증거 적으면 평균으로)
  5) 정규분포 CDF로 0~100 (50=평균 가게)
카테고리 순위: Σ_가게 [log1p(리뷰) × 카테고리 언급수] 로 '망원동에서 핫한 빵' 랭킹.

사용: .venv/bin/python -m blog_score.score_v2 [--refetch-reviews]
"""
import argparse
import json
import math
import statistics
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from blog_score import analyze_window as aw, naver_scrape as ns, report, scoring  # noqa: E402
from blog_score.dictionaries import CATEGORY_KEYWORDS  # noqa: E402

BASE = Path(__file__).resolve().parent
OUT_DIR = BASE.parent.parent / "docs" / "reports"
SNAP_DIR = BASE / "snapshots"
CACHE_DIR = BASE / "cache"

MIN_MENTIONS = 2
# 신호 가중치 (합 1.0) — 인기도(리뷰)·최신성 비중 높임 (사용자 요청)
W_POP, W_VOL, W_REC, W_SEN = 0.48, 0.22, 0.22, 0.08
SHRINK_K = 3.0   # 베이지안 수축 강도


def _latest(pat: str, root: Path) -> Path:
	fs = sorted(root.glob(pat))
	if not fs:
		sys.exit(f"파일 없음: {pat} in {root}")
	return fs[-1]


def _phi(x: float) -> float:
	return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def _zscores(d: dict) -> dict:
	if len(d) < 2:
		return {k: 0.0 for k in d}
	vals = list(d.values())
	mu = statistics.mean(vals)
	sd = statistics.pstdev(vals)
	if sd == 0:
		return {k: 0.0 for k in d}
	return {k: (v - mu) / sd for k, v in d.items()}


def load_cache() -> dict:
	"""store_id -> [post dict]"""
	path = _latest("mangwon_bodies_*.jsonl", CACHE_DIR)
	print(f"본문 캐시: {path.name}")
	by = {}
	for line in path.read_text(encoding="utf-8").splitlines():
		if not line.strip():
			continue
		r = json.loads(line)
		by.setdefault(r["store_id"], []).append(r)
	return by


def main() -> int:
	ap = argparse.ArgumentParser()
	ap.add_argument("--refetch-reviews", action="store_true")
	args = ap.parse_args()
	today = datetime.now().date()

	snap = json.loads(_latest("mangwon_stores_*.json", SNAP_DIR).read_text(encoding="utf-8"))
	meta = {s["store_id"]: s for s in snap["stores"]}
	cache = load_cache()

	# ── 리뷰수 ──
	if args.refetch_reviews:
		print("리뷰수 수집...")
		reviews = {}
		ids = list(cache)
		for i, sid in enumerate(ids, 1):
			pid = meta[sid].get("naver_place_id")
			rc = ns.fetch_review_count(pid) if pid else None
			if rc is None:
				rid = ns.resolve_place_id(meta[sid]["name"])
				rc = ns.fetch_review_count(rid) if rid else None
			reviews[sid] = rc
			if i % 25 == 0:
				print(f"  {i}/{len(ids)}")
	else:
		prev = _latest("mangwon_blog_score_*_pop.json", OUT_DIR)
		pj = json.loads(prev.read_text(encoding="utf-8"))
		reviews = {}
		for rows in pj["categories"].values():
			for r in rows:
				reviews.setdefault(r["store_id"], r.get("review_count"))
	known = [v for v in reviews.values() if v]
	median_rev = statistics.median(known) if known else 100
	print(f"리뷰 보유 {len(known)}/{len(cache)} · 중앙값 {int(median_rev)}")

	def review_eff(sid):
		return float(reviews.get(sid) or median_rev)

	# ── 가게×카테고리 신호 집계 (근접 귀속) ──
	cats = list(CATEGORY_KEYWORDS)
	agg = {sid: {} for sid in cache}          # sid -> cat -> {n, rec_sum, sen_sum}
	posts_dump = {}                            # 리포트용 글 근거
	for sid, posts in cache.items():
		aliases = aw.name_aliases(meta[sid]["name"])
		dump = []
		for p in posts:
			a = aw.analyze_post(p["body"], p.get("title", ""), aliases)
			dump.append({"url": p["url"], "title": p.get("title", ""), "date": p.get("date"),
						 "is_ad": a["ad"], "ad_reason": a["ad_reason"],
						 "sentiment": a["sentiment"], "cats": a["cats"]})
			if not a["relevant"] or a["ad"] or not a["cats"]:
				continue
			dec = scoring.recency_decay(p.get("date"), today=today)
			for c in a["cats"]:
				slot = agg[sid].setdefault(c, {"n": 0, "rec": 0.0, "sen": 0.0, "recent": 0})
				slot["n"] += 1
				slot["rec"] += dec
				slot["sen"] += a["senti_mult"]
				if dec >= 0.5 ** (90 / scoring.HALFLIFE_DAYS):
					slot["recent"] += 1
		posts_dump[str(sid)] = dump

	# ── 카테고리별 합성 점수 ──
	categories = {}
	cat_rank_metric = {}
	for c in cats:
		cand = {sid: agg[sid][c] for sid in agg if c in agg[sid] and agg[sid][c]["n"] >= MIN_MENTIONS}
		# 카테고리 순위 메트릭(후보 전체 기준): Σ log1p(리뷰) × 언급수
		cat_rank_metric[c] = sum(math.log1p(review_eff(sid)) * agg[sid][c]["n"]
								 for sid in agg if c in agg[sid])
		if not cand:
			categories[c] = []
			continue
		pop = {sid: math.log1p(review_eff(sid)) for sid in cand}
		vol = {sid: math.log1p(cand[sid]["n"]) for sid in cand}
		rec = {sid: cand[sid]["rec"] / cand[sid]["n"] for sid in cand}
		sen = {sid: cand[sid]["sen"] / cand[sid]["n"] for sid in cand}
		zp, zv, zt, zs = _zscores(pop), _zscores(vol), _zscores(rec), _zscores(sen)
		rows = []
		for sid in cand:
			comp = W_POP * zp[sid] + W_VOL * zv[sid] + W_REC * zt[sid] + W_SEN * zs[sid]
			n = cand[sid]["n"]
			shrunk = (n / (n + SHRINK_K)) * comp     # 베이지안 수축
			score = round(100 * _phi(shrunk), 1)     # 정규분포 CDF
			rows.append({"store_id": sid, "name": meta[sid]["name"],
						 "address": meta[sid].get("address", ""),
						 "normalized": score, "final_score": round(comp, 3),
						 "mention_count": n, "recent_count": cand[sid]["recent"],
						 "review_count": reviews.get(sid)})
		rows.sort(key=lambda r: r["normalized"], reverse=True)
		categories[c] = rows

	# 카테고리 순위로 정렬(핫한 카테고리 먼저)
	ordered = sorted(cats, key=lambda c: cat_rank_metric[c], reverse=True)
	categories = {c: categories[c] for c in ordered}
	category_ranking = [{"cat": c, "metric": round(cat_rank_metric[c], 1),
						 "store_count": len(categories[c])} for c in ordered]

	run = datetime.now().strftime("%Y%m%dT%H%M%S")
	total_posts = sum(len(v) for v in cache.values())
	ad_excluded = sum(1 for d in posts_dump.values() for p in d if p["is_ad"])
	results = {"run": run + "-v2", "snapshot_at": snap.get("snapshot_at"),
			   "store_count": len(cache), "total_posts": total_posts,
			   "ad_excluded": ad_excluded, "categories": categories,
			   "category_ranking": category_ranking, "posts_by_store": posts_dump}

	OUT_DIR.mkdir(parents=True, exist_ok=True)
	html_path = OUT_DIR / f"mangwon_blog_score_{run}_v2.html"
	html_path.write_text(report.build_html(results), encoding="utf-8")
	json_path = OUT_DIR / f"mangwon_blog_score_{run}_v2.json"
	json_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
	print(f"\n완료: 글 {total_posts} (광고제외 {ad_excluded})")
	print(f"  HTML: {html_path}\n  JSON: {json_path}")
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
