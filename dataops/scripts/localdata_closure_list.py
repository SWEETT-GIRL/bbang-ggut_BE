#!/usr/bin/env python3
"""P2 — 고신뢰 폐업 확정 리스트 산출 (파일 산출만, 운영 DB 무접촉).

P1 검수 산출물을 조인해 change_proposal(target=store.status, source=LOCALDATA)
수납 대기 리스트를 만든다. 구성(플랜 §P2):

  AUTO 경로   AUTO ∩ 인허가 폐업 ∩ 네이버 부재(470) − P1-4 게이트 X 3 = 467
              (전화일치 면제 / 중·강셀 표본 100% 일괄 승격 / 약셀 전수 O 8)
  REVIEW 경로 P1-2 표본 O 19 + P1-3 LLM 분류 O 103 = 122
  직접 경로   사람행 4 — 네이버 검색 폐업 확인(매핑 미확정, 근거=눈검사 기록)

출력: matching/localdata_closure_confirmed.tsv (폐업일 내림차순)
"""
import csv
import json
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from localdata_bucket_gate import load_absent

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "matching" / "localdata_closure_confirmed.tsv"


def jsonl(name):
    with open(ROOT / "matching" / name, encoding="utf-8-sig") as f:
        return [json.loads(l) for l in f if l.strip()]


def main():
    absent = load_absent()
    with open(ROOT / "matching" / "localdata_mapping.tsv", encoding="utf-8") as f:
        mapping = {r["store_id"]: r for r in csv.DictReader(f, delimiter="\t")}

    rows = []

    def add(sid, path, evidence, confidence, note=""):
        m = mapping.get(str(sid), {})
        rows.append({
            "store_id": sid,
            "store_name": m.get("store_name", ""),
            "path": path,
            "evidence": evidence,
            "confidence": confidence,
            "mgmt_no": m.get("mgmt_no", ""),
            "licensed_name": m.get("licensed_name", ""),
            "closed_ymd": m.get("closed_ymd", ""),
            "license_status": m.get("license_status", ""),
            "name_sim": m.get("name_sim", ""),
            "note": note,
        })

    # AUTO 경로 — 게이트 X 3건 제외
    auto_x = {str(r["storeId"]) for r in jsonl("auto_gate_judgments.jsonl") if r["verdict"] == "X"}
    n_auto = 0
    for sid, r in mapping.items():
        if r["judgment"] != "AUTO" or "폐업" not in r["license_status"] or sid not in absent:
            continue
        if sid in auto_x:
            continue
        sim = float(r["name_sim"]) if r["name_sim"] else -1
        if r["phone_match"] == "1":
            ev = "전화일치(면제)"
        elif sim < 0.8:
            ev = "약셀 전수 판정 O"
        else:
            ev = "중·강셀 표본 게이트 통과"
        add(sid, "AUTO", ev, "high")
        n_auto += 1

    # REVIEW 경로 — P1-2 표본 O + P1-3 LLM O
    n_rev = 0
    for src, label in (("bucket_gate_judgments.jsonl", "P1-2 표본 판정 O"),
                       ("llm_classification.jsonl", "P1-3 LLM 분류 O")):
        for r in jsonl(src):
            if r["verdict"] == "O" and r.get("bucket") in ("A", "B"):
                add(r["storeId"], "REVIEW", label, r.get("confidence", ""), r.get("reason", ""))
                n_rev += 1

    # 직접 경로 — 사람행 4건 (매핑 미확정 → 인허가 필드 비움)
    n_direct = 0
    for r in jsonl("eyeball_judgments.jsonl"):
        if r.get("section") == "localdata-사람행" and "폐업" in (r.get("tags") or []):
            m = mapping.get(str(r["storeId"]), {})
            rows.append({
                "store_id": r["storeId"], "store_name": m.get("store_name", r.get("name", "")),
                "path": "DIRECT", "evidence": "네이버 검색 폐업 확인(사용자, 2026-08-14)",
                "confidence": "high", "mgmt_no": "", "licensed_name": "", "closed_ymd": "",
                "license_status": "", "name_sim": "",
                "note": "인허가 매핑 미확정 — 근거는 눈검사 기록",
            })
            n_direct += 1

    ids = [r["store_id"] for r in rows]
    assert len(ids) == len(set(ids)), "store_id 중복"
    rows.sort(key=lambda r: (r["closed_ymd"] or "0000"), reverse=True)

    with open(OUT, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()), delimiter="\t")
        w.writeheader()
        w.writerows(rows)

    print(f"AUTO {n_auto} + REVIEW {n_rev} + DIRECT {n_direct} = {len(rows)} → {OUT.name}")
    from collections import Counter
    yr = Counter((r["closed_ymd"] or "?")[:4] for r in rows)
    print("폐업 연도:", dict(sorted(yr.items(), reverse=True)))


if __name__ == "__main__":
    main()
