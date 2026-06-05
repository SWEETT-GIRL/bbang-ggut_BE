#!/usr/bin/env python3
"""한 동(또는 여러 동)의 블로그 점수 파이프라인을 끝까지 한 번에 실행하는 오케스트레이터.

단계: export(스냅샷) → crawl(본문) → blog_total(화제성) → distinctiveness(특화도) → score_v3(점수·리포트).
등록 안 된 동은 한글 파일명으로 떨어진다(regions.slug_for 폴백). 동 단위 재사용 — 홈 피드용.

사용:
    .venv/bin/python -m blog_score.run_region --region 연남동
    .venv/bin/python -m blog_score.run_region --region 연남동 성수동 합정동
    .venv/bin/python -m blog_score.run_region --region 연남동 --max-posts 40 --limit 50
    .venv/bin/python -m blog_score.run_region --region 연남동 --dry-run   # 명령만 출력

리뷰·평점은 그 동의 이전 v3 리포트가 없으면 자동으로 새로 수집(--refetch-reviews),
있으면 재사용한다. --refetch-reviews 로 강제 갱신 가능.
"""
import argparse
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from blog_score.regions import slug_for  # noqa: E402

BASE = Path(__file__).resolve().parent
SCRIPTS_DIR = BASE.parent                       # -m 실행 기준 디렉터리
OUT_DIR = BASE.parent.parent / "docs" / "reports"
NAT_IDF_CACHE = BASE / "cache" / "keyword_total_nat.json"  # 전국 IDF 글로벌 캐시(동 무관)
PKG = "blog_score"


def run(mod: str, *extra: str, dry: bool = False) -> None:
	cmd = [sys.executable, "-m", f"{PKG}.{mod}", *extra]
	print(f"\n$ python -m {PKG}.{mod} {' '.join(extra)}")
	if dry:
		return
	r = subprocess.run(cmd, cwd=str(SCRIPTS_DIR))
	if r.returncode != 0:
		sys.exit(f"✗ 단계 실패: {mod} (exit {r.returncode})")


def has_prior_report(slug: str) -> bool:
	return bool(sorted(OUT_DIR.glob(f"{slug}_blog_score_*_v3.json")))


def run_region(region: str, args) -> None:
	slug = slug_for(region)
	print(f"\n{'='*60}\n▶ {region} (slug={slug}) 파이프라인 시작\n{'='*60}")

	run("export_mangwon_snapshot", "--region", region, dry=args.dry_run)

	crawl_extra = ["--region", region, "--max-posts", str(args.max_posts)]
	if args.limit:
		crawl_extra += ["--limit", str(args.limit)]
	run("crawl_multi", *crawl_extra, dry=args.dry_run)

	run("fetch_blog_totals", "--region", region, dry=args.dry_run)
	run("fetch_distinctiveness", "--region", region, dry=args.dry_run)

	# 첫 실행(이전 리포트 없음)이거나 강제 시에만 리뷰·평점 새로 수집
	refetch = args.refetch_reviews or not has_prior_report(slug)
	score_extra = ["--region", region]
	if refetch:
		score_extra.append("--refetch-reviews")
	run("score_v3", *score_extra, dry=args.dry_run)

	print(f"\n✓ {region} 완료 → docs/reports/{slug}_blog_score_*_v3.html")


def main() -> int:
	ap = argparse.ArgumentParser(description="동 단위 블로그 점수 파이프라인 오케스트레이터")
	ap.add_argument("--region", nargs="+", required=True, help="region_3rd 동명 (여러 개 가능)")
	ap.add_argument("--max-posts", type=int, default=40, help="가게당 본문 수집 상한")
	ap.add_argument("--limit", type=int, default=None, help="크롤 가게 수 상한 (테스트용)")
	ap.add_argument("--refetch-reviews", action="store_true", help="리뷰·평점 강제 재수집")
	ap.add_argument("--refetch-idf", action="store_true", help="전국 IDF 키워드 total 강제 재수집")
	ap.add_argument("--dry-run", action="store_true", help="실행 없이 단계 명령만 출력")
	args = ap.parse_args()

	# 전국 고정 IDF 캐시 — 동 무관 글로벌, 없거나 강제 시 1회만 수집(이후 모든 동 재사용)
	if args.refetch_idf or not NAT_IDF_CACHE.exists():
		run("fetch_keyword_totals", dry=args.dry_run)

	for region in args.region:
		run_region(region, args)

	if len(args.region) > 1:
		print(f"\n{'='*60}\n전체 완료: {len(args.region)}개 동")
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
