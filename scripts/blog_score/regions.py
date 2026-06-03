#!/usr/bin/env python3
"""지역(region) → 파일 슬러그 매핑. 블로그 점수 파이프라인을 동(region_3rd) 단위로 재사용.

각 스크립트는 `--region 공덕동` 처럼 한글 동명을 받고, 스냅샷/캐시/리포트 파일명은
ASCII 슬러그(mangwon·gongdeok …)로 네임스페이스한다. 미등록 동은 한글 그대로 슬러그가 된다.
"""

REGION_SLUGS = {
	"망원동": "mangwon",
	"공덕동": "gongdeok",
}


def slug_for(region: str) -> str:
	return REGION_SLUGS.get(region, region)
