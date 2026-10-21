#!/usr/bin/env python3
"""네이버 교차 중재 (P1-1) — REVIEW 매핑을 3자 대조로 자동 승격/기각.

우리 DB ↔ 인허가 매핑이 애매(REVIEW)할 때, 네이버 매칭 트랙이 이미 검증한
네이버 플레이스를 심판으로 세워 인허가 레코드와 대조한다:

  승격  인허가 전화 = 네이버 전화 (우연 일치 불가한 강신호)
  승격  인허가 상호 ≈ 네이버 상호 (우리 DB만 다름 = 개명·법인명 등록)
  기각  전화 둘 다 있는데 다름 ∧ 상호도 다름 (같은 건물의 다른 가게)

심판 자격: 네이버 트랙 판정이 AUTO인 후보만. REVIEW 후보를 심판으로 쓰면
같은 건물 다른 가게(백화점·상가)로 오승격하는 역효과가 난다.

입력:
  matching/localdata_mapping.tsv       매칭 판정 (REVIEW 행만 사용)
  matching/match_results.jsonl         네이버 매칭 트랙 결과 (후보·판정)
  matching/localdata_raw/{ds}.jsonl    인허가 원본 (전화·상호 — 매핑 TSV에 없음)
출력:
  matching/localdata_arbitration.tsv   가게별 중재 판정 (PROMOTE/REJECT/UNDECIDED)

매핑 TSV는 재생성 가능한 파이프라인 산출물이므로 직접 수정하지 않는다 —
이 파일은 오버레이로, 다운스트림(버킷 게이트·폐업 확정)이 둘을 조인해 쓴다.
"""
import csv
import json
from collections import Counter
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from localdata_match import DATASET_LABEL, name_sim, norm_name, norm_phone

ROOT = Path(__file__).resolve().parent.parent
MAPPING = ROOT / "matching" / "localdata_mapping.tsv"
NAVER = ROOT / "matching" / "match_results.jsonl"
RAW_DIR = ROOT / "matching" / "localdata_raw"
OUT = ROOT / "matching" / "localdata_arbitration.tsv"

PROMOTE_NAME_SIM = 0.85  # 인허가↔네이버 상호 일치로 볼 하한
REJECT_NAME_SIM = 0.40   # 전화 불일치와 결합 시 기각으로 볼 상호 상한


def load_naver() -> dict[str, dict]:
    """storeId → 네이버 트랙 최종 레코드. pretty-print 혼재를 견디도록 스트림 파싱."""
    dec = json.JSONDecoder()
    text = NAVER.read_text(encoding="utf-8-sig")
    recs, i = {}, 0
    while i < len(text):
        while i < len(text) and text[i] in " \r\n\t":
            i += 1
        if i >= len(text):
            break
        obj, i = dec.raw_decode(text, i)
        recs[str(obj.get("storeId"))] = obj  # 재시도 시 뒤 레코드가 최신
    return recs


def load_license(needed: set[str]) -> dict[str, dict]:
    """필요한 관리번호만 원본에서 추출 (전화·상호는 매핑 TSV에 없다)."""
    out = {}
    for ds in DATASET_LABEL:
        p = RAW_DIR / f"{ds}.jsonl"
        if not p.exists():
            continue
        with open(p, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                r = json.loads(line)
                mgmt = r.get("MNG_NO") or ""
                if mgmt in needed and mgmt not in out:
                    out[mgmt] = {
                        "name": (r.get("BPLC_NM") or "").strip(),
                        "phone": norm_phone(r.get("TELNO") or ""),
                    }
    return out


def arbitrate(lic: dict, cand: dict) -> tuple[str, str]:
    lic_tel = lic["phone"]
    nav_tel = norm_phone(cand.get("tel") or "")
    sim = name_sim(norm_name(lic["name"]), norm_name(cand.get("name") or ""))
    if lic_tel and nav_tel and lic_tel == nav_tel:
        return "PROMOTE", f"전화일치({lic_tel[-4:]})"
    if sim >= PROMOTE_NAME_SIM:
        return "PROMOTE", f"상호일치(sim={sim})"
    if lic_tel and nav_tel and lic_tel != nav_tel and sim < REJECT_NAME_SIM:
        return "REJECT", f"전화불일치·상호불일치(sim={sim})"
    return "UNDECIDED", f"sim={sim}, 전화={'유' if lic_tel and nav_tel else '무'}"


def main():
    with open(MAPPING, encoding="utf-8") as f:
        review = [r for r in csv.DictReader(f, delimiter="\t") if r["judgment"] == "REVIEW"]
    naver = load_naver()
    lic_map = load_license({r["mgmt_no"] for r in review if r["mgmt_no"]})
    print(f"REVIEW {len(review):,} / 네이버 레코드 {len(naver):,} / 인허가 원본 조회 {len(lic_map):,}")

    rows = []
    counts = Counter()
    for r in review:
        nav = naver.get(r["store_id"])
        cand = (nav or {}).get("candidate")
        if not nav or not cand:
            counts["후보없음"] += 1
            continue
        if nav.get("judgment") != "AUTO":
            counts["심판부적격"] += 1  # 네이버 후보 자체가 미검증(REVIEW 등)
            continue
        lic = lic_map.get(r["mgmt_no"])
        if not lic:
            counts["원본누락"] += 1
            continue
        verdict, reason = arbitrate(lic, cand)
        counts[verdict] += 1
        rows.append({
            "store_id": r["store_id"],
            "store_name": r["store_name"],
            "mgmt_no": r["mgmt_no"],
            "licensed_name": lic["name"],
            "naver_place_id": cand.get("placeId", ""),
            "naver_name": cand.get("name", ""),
            "lic_naver_sim": name_sim(norm_name(lic["name"]), norm_name(cand.get("name") or "")),
            "license_status": r["license_status"],
            "verdict": verdict,
            "reason": reason,
        })

    with open(OUT, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()), delimiter="\t")
        w.writeheader()
        w.writerows(rows)

    print(f"중재 결과: {dict(counts)} → {OUT.name}")
    for status in ("영업", "폐업"):
        sub = Counter(x["verdict"] for x in rows if status in x["license_status"])
        print(f"  인허가 {status}: {dict(sub)}")


if __name__ == "__main__":
    main()
