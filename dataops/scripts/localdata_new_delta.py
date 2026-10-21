#!/usr/bin/env python3
"""신규 델타 → 후보 적재 — 운영 주기(설계 §3.6)의 유입 루프 입구.

입력: matching/localdata_raw/delta/{ds}_new.jsonl (localdata_fetch --delta 산출)
필터(설계 확정안): 영업 중 + 프랜차이즈 제외 + 명백 비대상 업태(편의점·키즈카페·극장) 제외
  + 기존 관리번호(매핑·후보) 제외. **상호 키워드 제한 없음** — 빵집 여부는 네이버
  카테고리 검증(localdata_new_verify)이 판별한다. 푸드트럭은 등록 실선례가 있어 제외하지 않는다.
출력: localdata_new_candidates.tsv 에 append → 기존 검증·등록 파이프라인이 그대로 이어받음.

사용: python scripts/localdata_new_delta.py
후속: python scripts/localdata_new_verify.py (신규 오픈 기본 모드가 델타분을 집음)
"""
import csv
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from localdata_match import DATASET_LABEL, TR_5174, is_franchise, norm_name

ROOT = Path(__file__).resolve().parent.parent
DELTA_DIR = ROOT / "matching" / "localdata_raw" / "delta"
CANDIDATES = ROOT / "matching" / "localdata_new_candidates.tsv"

EXCLUDE_CATEGORY = ("편의점", "키즈카페", "극장", "PC방", "노래", "당구")


def main() -> int:
    known = set()
    with open(CANDIDATES, encoding="utf-8") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            known.add(r["mgmt_no"])
    with open(ROOT / "matching" / "localdata_mapping.tsv", encoding="utf-8") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            if r["mgmt_no"]:
                known.add(r["mgmt_no"])
    print(f"기존 관리번호 {len(known):,}")

    added = skipped_fr = skipped_cat = 0
    with open(CANDIDATES, "a", encoding="utf-8", newline="") as out:
        w = csv.writer(out, delimiter="\t")
        for ds in DATASET_LABEL:
            p = DELTA_DIR / f"{ds}_new.jsonl"
            if not p.exists():
                continue
            seen = set()
            for line in open(p, encoding="utf-8"):
                line = line.strip()
                if not line:
                    continue
                r = json.loads(line)
                m = r.get("MNG_NO") or ""
                if not m or m in known or m in seen:
                    continue
                seen.add(m)
                status = (r.get("DTL_SALS_STTS_NM") or r.get("SALS_STTS_NM") or "")
                if "폐업" in status:
                    continue
                name = (r.get("BPLC_NM") or "").strip()
                category = (r.get("BZSTAT_SE_NM") or r.get("SNTTN_BZSTAT_NM") or "").strip()
                if any(x in category for x in EXCLUDE_CATEGORY):
                    skipped_cat += 1
                    continue
                if is_franchise(norm_name(name)):
                    skipped_fr += 1
                    continue
                lat = lon = ""
                try:
                    x, y = r.get("CRD_INFO_X"), r.get("CRD_INFO_Y")
                    if x and y:
                        lon_f, lat_f = TR_5174.transform(float(x), float(y))
                        if 33.0 < lat_f < 39.5 and 124.0 < lon_f < 132.0:
                            lat, lon = lat_f, lon_f
                except (ValueError, TypeError):
                    pass
                w.writerow([m, DATASET_LABEL[ds], category, name,
                            (r.get("LCPMT_YMD") or "").strip(), "", lat, lon])
                added += 1
    print(f"후보 추가 {added} (프랜차이즈 제외 {skipped_fr} · 비대상 업태 제외 {skipped_cat})")
    print("후속: python scripts/localdata_new_verify.py — 델타분 검증 크롤")
    return 0


if __name__ == "__main__":
    main()
