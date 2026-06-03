#!/usr/bin/env python3
"""인기도 중심 재점수화 — 재크롤 없이 캐시된 글 + 새 리뷰수로 재계산.

모델: Score(s,c) = catmix(s,c) · Pop(s)
  - catmix(s,c) : 그 가게 블로그 글이 카테고리 c에 대한 비율 (= 무슨 빵집인지)
                  = catweight(s,c) / Σ_c catweight   (catweight = Σ decay·senti·focus·share)
  - Pop(s)      : 인기도 = √(리뷰수)  (없으면 known 중앙값으로 대체)
그 후 베이지안 수축(증거 글수 n_c) → 카테고리내 0~100 정규화.

사용: .venv/bin/python -m blog_score.rescore [--refetch-reviews]
"""
import argparse
import json
import math
import statistics
import sys
from datetime import date, datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from blog_score import naver_scrape as ns, report, scoring  # noqa: E402
from blog_score.dictionaries import CATEGORY_KEYWORDS  # noqa: E402

BASE = Path(__file__).resolve().parent
OUT_DIR = BASE.parent.parent / "docs" / "reports"
SNAP_DIR = BASE / "snapshots"

MIN_MENTIONS = 2   # 카테고리 등장 최소 블로그 언급수(스치는 1회 언급 차단)


def latest(glob_pat: str, root: Path) -> Path:
	files = sorted(root.glob(glob_pat))
	if not files:
		sys.exit(f"파일 없음: {glob_pat}")
	return files[-1]


def refetch_reviews(stores_by_id: dict, snap_stores: list[dict]) -> dict:
	"""snapshot의 naver_place_id로 리뷰 재수집(타입 다중). 없으면 이름검색 추정."""
	pid_by_id = {s["store_id"]: s.get("naver_place_id") for s in snap_stores}
	name_by_id = {s["store_id"]: s["name"] for s in snap_stores}
	out = {}
	ids = list(stores_by_id)
	for i, sid in enumerate(ids, 1):
		pid = pid_by_id.get(sid)
		rc = ns.fetch_review_count(pid) if pid else None
		if rc is None:
			rid = ns.resolve_place_id(name_by_id.get(sid, ""))
			rc = ns.fetch_review_count(rid) if rid else None
		out[sid] = rc
		if i % 20 == 0:
			print(f"  리뷰 재수집 {i}/{len(ids)}")
	return out


def main() -> int:
	ap = argparse.ArgumentParser()
	ap.add_argument("--refetch-reviews", action="store_true",
					help="리뷰수 다시 긁기(권장). 생략 시 기존 run의 리뷰수 재사용")
	args = ap.parse_args()

	today = datetime.now().date()
	run = json.loads(latest("mangwon_blog_score_*.json", OUT_DIR).read_text(encoding="utf-8"))
	snap = json.loads(latest("mangwon_stores_*.json", SNAP_DIR).read_text(encoding="utf-8"))

	# 가게 메타(이름/주소) + store_id 목록 — 기존 categories에서 복원
	meta = {}
	for rows in run["categories"].values():
		for r in rows:
			meta.setdefault(r["store_id"], {"name": r["name"], "address": r.get("address", "")})
	posts_by = {int(k): v for k, v in run["posts_by_store"].items()}

	# ── 리뷰수 ──
	if args.refetch_reviews:
		print(f"리뷰수 재수집 시작 ({len(meta)}곳)...")
		reviews = refetch_reviews(meta, snap["stores"])
	else:
		reviews = {}
		for rows in run["categories"].values():
			for r in rows:
				reviews.setdefault(r["store_id"], r.get("review_count"))
	known = [v for v in reviews.values() if v]
	median_rev = statistics.median(known) if known else 100
	print(f"리뷰 보유 {len(known)}/{len(meta)} · 중앙값 {int(median_rev)}")

	def review_eff(sid: int) -> float:
		rv = reviews.get(sid)
		return float(rv) if rv else float(median_rev)

	# ── 점수: Score(s,c) = 리뷰수 × ln(1 + 블로그 언급수) ──
	# 인기도(리뷰수) 주력 + 블로그 언급수 보조. 최소 언급 MIN_MENTIONS 이상만 등장.
	cats = list(CATEGORY_KEYWORDS)
	raw = {c: {} for c in cats}
	mention = {c: {} for c in cats}
	recent = {c: {} for c in cats}
	for sid, posts in posts_by.items():
		clean = [p for p in posts if not p.get("is_ad") and p.get("focus", 0) > 0]
		for c in cats:
			cposts = [p for p in clean if c in p["cats"]]
			m = len(cposts)
			if m < MIN_MENTIONS:
				continue
			mention[c][sid] = m
			raw[c][sid] = review_eff(sid) * math.log(1 + m)
			rc = 0
			for p in cposts:
				if p.get("date"):
					try:
						if (today - date(*map(int, p["date"].split("-")))).days <= 90:
							rc += 1
					except ValueError:
						pass
			recent[c][sid] = rc

	categories = {}
	for c in cats:
		norm = scoring.normalize_log(raw[c])
		rows = [{"store_id": sid, "name": meta[sid]["name"], "address": meta[sid]["address"],
				 "final_score": round(raw[c][sid], 1), "normalized": norm[sid],
				 "mention_count": mention[c][sid], "recent_count": recent[c].get(sid, 0),
				 "review_count": reviews.get(sid)}
				for sid in norm]
		rows.sort(key=lambda r: r["normalized"], reverse=True)
		categories[c] = rows

	run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
	results = {"run": run_id + "-rescored", "snapshot_at": run.get("snapshot_at"),
			   "store_count": len(meta), "total_posts": run.get("total_posts"),
			   "ad_excluded": run.get("ad_excluded"), "categories": categories,
			   "posts_by_store": {str(k): v for k, v in posts_by.items()}}

	OUT_DIR.mkdir(parents=True, exist_ok=True)
	html_path = OUT_DIR / f"mangwon_blog_score_{run_id}_pop.html"
	html_path.write_text(report.build_html(results), encoding="utf-8")
	json_path = OUT_DIR / f"mangwon_blog_score_{run_id}_pop.json"
	json_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
	print(f"\n재점수화 완료\n  HTML: {html_path}\n  JSON: {json_path}")
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
