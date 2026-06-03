#!/usr/bin/env python3
"""score_v3 — IDF 가중 + 가게 시그니처 카테고리 + 카테고리 순위(전문점 강도).

v2 대비 개선(재크롤 없이 캐시 재계산):
  ① IDF 가중   : 흔한 키워드('케이크')는 약하게, 희소 키워드('베이글·휘낭시에')는 강하게.
                 idf(kw)=log(1 + N글 / (1+df)). catch-all 카테고리 부풀림 제거.
  ② 시그니처   : 가게를 '대표 카테고리(catmix 상위/임계)'에서만 랭크. 스치는 언급으로
                 10개 카테고리에 다 등장하는 것 차단 → 다이닝코드식 '딱' 추천.
  ③ 전문점순위 : 카테고리 순위 = 그 카테고리를 시그니처로 가진 강한 가게들의 점수 합.

합성점수(다신호): log1p→z표준화→가중→베이지안수축→정규분포CDF. 전문성(catmix) 신호 추가.

사용: .venv/bin/python -m blog_score.score_v3 [--region 공덕동] [--refetch-reviews]
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
from blog_score.regions import slug_for  # noqa: E402

BASE = Path(__file__).resolve().parent
OUT_DIR = BASE.parent.parent / "docs" / "reports"
SNAP_DIR = BASE / "snapshots"
CACHE_DIR = BASE / "cache"

MIN_MENTIONS = 2
SIG_SHARE = 0.35    # 시그니처 최소 점유율 — 전문점만 노출(비전문 카테고리 침범 방지). 대표 카테고리는 무조건 포함
SHRINK_K = 3.0
# 카테고리 순위 = '간판 전문점 명성' 중심(peak-dominant). 생성 카테고리(케이크 등) 부풀림 방지.
CAT_PEAK_W = [1.0, 0.3, 0.15]
TOP_N_CATS = 5      # 리포트에 표시할 대분류 수
# 가게 점수 신호 가중치 (합 1.0). 감성은 별도 비중이 아니라 각 글 가중에 ×0.5~1.5로 반영됨.
# 평점(별점) 추가 + 최신성 비중↑ ("요즘 뜨는 집").
# 양대 신호: 리뷰(방문객 인기) + 블로그 총언급(화제성, 오픈API total = 다이닝코드식)
W_POP, W_BLOG, W_REC, W_SPEC = 0.40, 0.30, 0.18, 0.12


def _latest(pat: str, root: Path) -> Path:
	fs = sorted(root.glob(pat))
	if not fs:
		sys.exit(f"파일 없음: {pat}")
	return fs[-1]


def _phi(x):
	return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def _z(d):
	if len(d) < 2:
		return {k: 0.0 for k in d}
	mu = statistics.mean(d.values())
	sd = statistics.pstdev(d.values())
	return {k: 0.0 for k in d} if sd == 0 else {k: (v - mu) / sd for k, v in d.items()}


IDF_MIN, IDF_MAX = 1.0, 3.0  # 전국 IDF 정규화 밴드(흔함→MIN, 희소→MAX). 곱가중 폭주 방지·동간 일관.


def build_idf(_cache=None) -> dict:
	"""전국 코퍼스 고정 IDF — 네이버 오픈API 전국 블로그 total을 df로 사용.

	동(local) 코퍼스가 아니라 전국 total(cache/keyword_total_nat.json, fetch_keyword_totals)을
	근거로 하여 동마다 변하지 않고(고정) 소표본 동에서도 안정적이다. 전국 total은 폭(4~8자릿수)이
	넓어 log10 후 [IDF_MIN, IDF_MAX]로 역정규화(흔할수록 낮음)해 곱가중 폭주를 막는다.
	캐시 없으면 1.0(무효과) 폴백.
	"""
	all_kws = {kw.replace(" ", "") for kws in CATEGORY_KEYWORDS.values() for kw in kws}
	f = CACHE_DIR / "keyword_total_nat.json"
	if not f.exists():
		print("⚠ keyword_total_nat.json 없음 → fetch_keyword_totals 먼저 실행. IDF=1.0 폴백")
		return {kw: 1.0 for kw in all_kws}
	nat = json.loads(f.read_text(encoding="utf-8"))
	lg = {kw: math.log10(1 + float(nat.get(kw, 0))) for kw in all_kws}
	lo, hi = min(lg.values()), max(lg.values())
	if hi <= lo:
		return {kw: 1.0 for kw in all_kws}
	return {kw: IDF_MAX - (IDF_MAX - IDF_MIN) * (lg[kw] - lo) / (hi - lo) for kw in all_kws}


def main() -> int:
	ap = argparse.ArgumentParser()
	ap.add_argument("--region", default="망원동", help="region_3rd 동명")
	ap.add_argument("--refetch-reviews", action="store_true")
	args = ap.parse_args()
	region = args.region
	slug = slug_for(region)
	today = datetime.now().date()

	snap = json.loads(_latest(f"{slug}_stores_*.json", SNAP_DIR).read_text(encoding="utf-8"))
	meta = {s["store_id"]: s for s in snap["stores"]}

	# 캐시 로드
	cpath = _latest(f"{slug}_bodies_*.jsonl", CACHE_DIR)
	cache = {}
	for line in cpath.read_text(encoding="utf-8").splitlines():
		if line.strip():
			r = json.loads(line)
			cache.setdefault(r["store_id"], []).append(r)
	print(f"본문 캐시 {cpath.name} · {len(cache)}곳 · 글 {sum(len(v) for v in cache.values())}")

	# 리뷰수
	reviews, ratings = {}, {}
	if args.refetch_reviews:
		for i, sid in enumerate(cache, 1):
			pid = meta[sid].get("naver_place_id")
			rc, rt = ns.fetch_place_stats(pid) if pid else (None, None)
			if rc is None:
				rid = ns.resolve_place_id(meta[sid]["name"], region=region)
				rc, rt = ns.fetch_place_stats(rid) if rid else (None, None)
			reviews[sid], ratings[sid] = rc, rt
			if i % 25 == 0:
				print(f"  플레이스 {i}/{len(cache)}")
	else:
		# 최신 v3(있으면) 또는 v2에서 리뷰·평점 재사용
		prevs = sorted(OUT_DIR.glob(f"{slug}_blog_score_*_v3.json")) or \
			sorted(OUT_DIR.glob(f"{slug}_blog_score_*_v2.json"))
		pj = json.loads(prevs[-1].read_text(encoding="utf-8"))
		for rows in pj["categories"].values():
			for r in rows:
				reviews.setdefault(r["store_id"], r.get("review_count"))
				ratings.setdefault(r["store_id"], r.get("rating"))
	known = [v for v in reviews.values() if v]
	median_rev = statistics.median(known) if known else 100
	known_rt = [v for v in ratings.values() if v]
	mean_rt = statistics.mean(known_rt) if known_rt else 4.5
	print(f"리뷰 보유 {len(known)}/{len(cache)} · 평점 보유 {len(known_rt)} (평균 {mean_rt:.2f})")

	def review_eff(sid):
		return float(reviews.get(sid) or median_rev)

	def rating_eff(sid):
		return float(ratings.get(sid) or mean_rt)

	# 블로그 총언급량 (오픈API total) — fetch_blog_totals.py 캐시
	bt_raw = {}
	btf = CACHE_DIR / f"blog_total_{slug}.json"
	if btf.exists():
		bt_raw = {int(k): v for k, v in json.loads(btf.read_text(encoding="utf-8")).items()}
	bt_known = [v for v in bt_raw.values() if v]
	median_bt = statistics.median(bt_known) if bt_known else 100
	print(f"블로그 총언급 보유 {len(bt_known)} (중앙값 {int(median_bt)})")

	def bt_eff(sid):
		return float(bt_raw.get(sid) or median_bt)

	# 대분류 특화도(전국 대비 망원 비중) — fetch_distinctiveness.py 캐시. 없으면 1배(무효과)
	distf = CACHE_DIR / f"distinctiveness_{slug}.json"
	distinct = {}
	if distf.exists():
		distinct = {g: v["ratio_permille"] for g, v in
					json.loads(distf.read_text(encoding="utf-8")).items()}
	print(f"특화도 로드: {len(distinct)}개 대분류")

	idf = build_idf()  # 전국 고정 IDF (cache/keyword_total_nat.json)
	print(f"IDF(전국고정) 예시 — 케이크 {idf.get('케이크',0):.2f} / 베이글 {idf.get('베이글',0):.2f} / 휘낭시에 {idf.get('휘낭시에',0):.2f}")

	# 최근접 귀속용 — 전체 가게 별칭/정규식
	alias_of = {sid: set(aw.name_aliases(meta[sid]["name"])) for sid in cache}
	all_aliases = [a for s in alias_of.values() for a in s]
	name_re = aw.build_name_regex(all_aliases)
	cat_res = aw.build_cat_regexes()

	# ── 가게×카테고리 집계 (IDF 가중) ──
	cats = list(CATEGORY_KEYWORDS)
	cw = {sid: {} for sid in cache}     # catweight (idf·recency·senti)
	nc = {sid: {} for sid in cache}     # 언급 글 수
	rec = {sid: {} for sid in cache}    # 최근 글 수
	posts_dump = {}
	recent_cut = 0.5 ** (90 / scoring.HALFLIFE_DAYS)
	for sid, posts in cache.items():
		tgt_set = alias_of[sid]
		dump = []
		for p in posts:
			a = aw.attribute_nearest(p.get("title", "") + " " + p["body"], tgt_set, name_re, cat_res)
			dump.append({"url": p["url"], "title": p.get("title", ""), "date": p.get("date"),
						 "is_ad": a["ad"], "ad_reason": a["ad_reason"],
						 "sentiment": a["sentiment"], "cats": a["cats"]})
			if not a["relevant"] or a["ad"] or not a["cats"]:
				continue
			dec = scoring.recency_decay(p.get("date"), today=today)
			for c, h in a["cats"].items():
				# IDF 가중: 카테고리 키워드별 idf 평균을 적중수에 곱 (간이)
				kw_idf = statistics.mean([idf.get(kw.replace(" ", ""), 1.0)
										  for kw in CATEGORY_KEYWORDS[c]]) if CATEGORY_KEYWORDS[c] else 1.0
				cw[sid][c] = cw[sid].get(c, 0.0) + h * kw_idf * dec * a["senti_mult"]
				nc[sid][c] = nc[sid].get(c, 0) + 1
				if dec >= recent_cut:
					rec[sid][c] = rec[sid].get(c, 0) + 1
		posts_dump[str(sid)] = dump

	# ── 시그니처 카테고리 ──
	signature = {}
	catmix = {sid: {} for sid in cache}
	for sid in cache:
		tot = sum(cw[sid].values())
		if tot <= 0:
			signature[sid] = set()
			continue
		mix = {c: cw[sid][c] / tot for c in cw[sid]}
		catmix[sid] = mix
		top = max(mix, key=mix.get)
		sig = {c for c, v in mix.items() if v >= SIG_SHARE and nc[sid].get(c, 0) >= MIN_MENTIONS}
		if nc[sid].get(top, 0) >= MIN_MENTIONS:
			sig.add(top)
		signature[sid] = sig

	# ── 카테고리별 합성 점수 (시그니처 가게만) ──
	categories = {}
	cat_strength = {}
	for c in cats:
		cand = [sid for sid in cache if c in signature.get(sid, set())]
		if not cand:
			categories[c] = []
			cat_strength[c] = 0.0
			continue
		pop = {s: math.sqrt(review_eff(s)) for s in cand}      # √리뷰: 방문객 인기
		blog = {s: math.log1p(bt_eff(s)) for s in cand}        # 블로그 총언급: 화제성
		rc = {s: rec[s].get(c, 0) / nc[s][c] for s in cand}
		sp = {s: catmix[s].get(c, 0) for s in cand}
		zp, zbl, zr, zs = _z(pop), _z(blog), _z(rc), _z(sp)
		rows = []
		for s in cand:
			comp = W_POP * zp[s] + W_BLOG * zbl[s] + W_REC * zr[s] + W_SPEC * zs[s]
			n = nc[s][c]
			shrunk = (n / (n + SHRINK_K)) * comp
			score = round(100 * _phi(shrunk), 1)
			rows.append({"store_id": s, "name": meta[s]["name"], "address": meta[s].get("address", ""),
						 "normalized": score, "final_score": round(comp, 3),
						 "mention_count": n, "recent_count": rec[s].get(c, 0),
						 "review_count": reviews.get(s), "blog_total": bt_raw.get(s),
						 "rating": ratings.get(s), "specialty": round(catmix[s].get(c, 0), 2)})
		rows.sort(key=lambda r: r["normalized"], reverse=True)
		categories[c] = rows
		# 대분류 순위 = 화제성 × 망원특화도
		#   화제성(buzz) = 블로그총언급(오픈API) × 전문성^2 × 최신성, 간판전문점 peak 합
		#   특화도 = 전국 대비 망원 비중(흔한 케이크 강등, 망원쏠림 베이글·크림빵 우대)
		buzz = sorted(
			(math.log1p(bt_eff(r["store_id"])) * (r["specialty"] ** 2)
			 * (0.5 + 0.5 * (r["recent_count"] / r["mention_count"] if r["mention_count"] else 0))
			 for r in rows), reverse=True)
		peak = sum(buzz[i] * w for i, w in enumerate(CAT_PEAK_W) if i < len(buzz))
		# 특화도 지배 + 화제성은 √로 약하게 → '망원다운'(특화) 순서가 주도
		cat_strength[c] = round(distinct.get(c, 1.0) * (peak ** 0.5), 2)

	ordered = sorted(cats, key=lambda c: cat_strength[c], reverse=True)
	full_ranking = [{"cat": c, "metric": cat_strength[c], "store_count": len(categories[c])}
					for c in ordered]
	# 대분류 전부 표시 (순위순)
	categories = {c: categories[c] for c in ordered}
	category_ranking = full_ranking

	run = datetime.now().strftime("%Y%m%dT%H%M%S")
	total_posts = sum(len(v) for v in cache.values())
	ad_excluded = sum(1 for d in posts_dump.values() for p in d if p["is_ad"])
	results = {"run": run + "-v3", "region": region, "snapshot_at": snap.get("snapshot_at"),
			   "store_count": len(cache), "total_posts": total_posts,
			   "ad_excluded": ad_excluded, "categories": categories,
			   "category_ranking": category_ranking, "posts_by_store": posts_dump}

	OUT_DIR.mkdir(parents=True, exist_ok=True)
	html_path = OUT_DIR / f"{slug}_blog_score_{run}_v3.html"
	html_path.write_text(report.build_html(results), encoding="utf-8")
	json_path = OUT_DIR / f"{slug}_blog_score_{run}_v3.json"
	json_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
	print(f"\n완료: 글 {total_posts} (광고제외 {ad_excluded})")
	print(f"  HTML: {html_path}\n  JSON: {json_path}")
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
