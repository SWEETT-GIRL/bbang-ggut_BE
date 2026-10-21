#!/usr/bin/env python3
"""P1-2/P1-4 층화 표본 게이트 — 검수 표본 추출과 증거 카드 생성.

버킷(신호 패턴)별로 표본을 뽑아 증거 카드를 만든다. 판정자는 카드를 보고
"인허가 레코드가 우리 가게와 같은 가게인가"를 O/X로 답하고, 버킷 표본 정밀도가
~100%면 일괄 승격, ~0%면 일괄 기각한다 (수입검사 로트 게이트와 같은 원리).

모드 (argv[1], 기본 sample):
  sample     P1-2: REVIEW+폐업 ∩ 네이버부재 291건 → 버킷 A(유사≥0.5) 25 /
             B(주소O·유사<0.5) 25 / C(주소X·유사<0.5) 전수 → bucket_gate_samples.tsv
  remainder  P1-3: 표본 판정 제외 A+B 전량 → bucket_gate_remainder.tsv
  auto       P1-4: AUTO 게이트 — 전화일치 면제. 핵심(폐업∩네이버부재 470)은
             약(sim<0.8) 전수 / 중(0.8~0.95) 20 / 강(≥0.95) 10, 일반 AUTO 약셀 10
             → auto_gate_samples.tsv

증거 카드: DB·인허가 양측 상호/주소/전화/폐업일.
판정 결과는 별도 JSONL에 기록(눈검사 파일들과 동일 관례).
"""
import csv
import json
import random
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from localdata_match import DATASET_LABEL

ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "matching" / "localdata_raw"

SEED = 42
SAMPLE_N = {"A": 25, "B": 25, "C": None}  # None = 전수
AUTO_SAMPLE_N = {"핵심-약": None, "핵심-중": 20, "핵심-강": 10, "일반-약": 10}


def bucket_of(r: dict) -> str:
    sim = float(r["name_sim"]) if r["name_sim"] else -1
    if sim >= 0.5:
        return "A"
    return "B" if r["addr_match"] == "1" else "C"


def load_absent() -> set[str]:
    dec = json.JSONDecoder()
    text = (ROOT / "matching" / "match_results.jsonl").read_text(encoding="utf-8-sig")
    absent, i = set(), 0
    while i < len(text):
        while i < len(text) and text[i] in " \r\n\t":
            i += 1
        if i >= len(text):
            break
        obj, i = dec.raw_decode(text, i)
        if obj.get("candidateCount") == 0:
            absent.add(str(obj.get("storeId")))
    return absent


def load_stores() -> dict[str, dict]:
    stores = {}
    with open(RAW_DIR / "stores_active.tsv", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            stores[row["id"]] = row
    return stores


def load_license(needed: set[str]) -> dict[str, dict]:
    lic = {}
    for ds in DATASET_LABEL:
        p = RAW_DIR / f"{ds}.jsonl"
        if not p.exists():
            continue
        with open(p, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                rec = json.loads(line)
                m = rec.get("MNG_NO") or ""
                if m in needed and m not in lic:
                    lic[m] = rec
    return lic


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "sample"
    absent = load_absent()
    with open(ROOT / "matching" / "localdata_mapping.tsv", encoding="utf-8") as f:
        mapping = list(csv.DictReader(f, delimiter="\t"))

    if mode == "auto":
        # 전화일치 AUTO는 강신호로 면제 — 비전화 AUTO만 셀 층화
        pool = [r for r in mapping if r["judgment"] == "AUTO" and r["phone_match"] != "1"]
        def cell(r):
            sim = float(r["name_sim"]) if r["name_sim"] else -1
            core = "폐업" in r["license_status"] and r["store_id"] in absent
            band = "약" if sim < 0.8 else ("중" if sim < 0.95 else "강")
            return ("핵심-" if core else "일반-") + band
        buckets = {}
        for r in pool:
            buckets.setdefault(cell(r), []).append(r)
        print("셀 분포:", {k: len(v) for k, v in sorted(buckets.items())})
        rng = random.Random(SEED)
        selection = {}
        for b, n in AUTO_SAMPLE_N.items():
            members = buckets.get(b, [])
            selection[b] = members if n is None or n >= len(members) else rng.sample(members, n)
        out_path = ROOT / "matching" / "auto_gate_samples.tsv"
    else:
        target = [r for r in mapping
                  if r["judgment"] == "REVIEW" and "폐업" in r["license_status"]
                  and r["store_id"] in absent]
        print(f"게이트 대상: {len(target)}")
        buckets = {"A": [], "B": [], "C": []}
        for r in target:
            buckets[bucket_of(r)].append(r)
        print("버킷 분포:", {k: len(v) for k, v in buckets.items()})
        if mode == "remainder":
            judged = set()
            with open(ROOT / "matching" / "bucket_gate_judgments.jsonl", encoding="utf-8") as f:
                for line in f:
                    if line.strip():
                        judged.add(str(json.loads(line)["storeId"]))
            selection = {"A": [r for r in buckets["A"] if r["store_id"] not in judged],
                         "B": [r for r in buckets["B"] if r["store_id"] not in judged]}
            out_path = ROOT / "matching" / "bucket_gate_remainder.tsv"
        else:
            rng = random.Random(SEED)
            selection = {}
            for b, members in buckets.items():
                n = SAMPLE_N[b]
                selection[b] = members if n is None or n >= len(members) else rng.sample(members, n)
            out_path = ROOT / "matching" / "bucket_gate_samples.tsv"

    stores = load_stores()
    needed = {r["mgmt_no"] for picked in selection.values() for r in picked if r["mgmt_no"]}
    lic = load_license(needed)

    rows = []
    for b, picked in selection.items():
        for r in sorted(picked, key=lambda x: int(x["store_id"])):
            s = stores.get(r["store_id"], {})
            L = lic.get(r["mgmt_no"], {})
            rows.append({
                "bucket": b,
                "store_id": r["store_id"],
                "db_name": s.get("name", ""),
                "db_addr": s.get("address", ""),
                "db_phone": s.get("phone", ""),
                "lic_name": L.get("BPLC_NM", ""),
                "lic_addr": L.get("ROAD_NM_ADDR", "") or L.get("LOTNO_ADDR", ""),
                "lic_phone": L.get("TELNO", ""),
                "lic_category": (L.get("BZSTAT_SE_NM") or L.get("SNTTN_BZSTAT_NM") or "").strip(),
                "dataset": r["dataset"],
                "closed_ymd": r["closed_ymd"],
                "dist_m": r["dist_m"],
                "name_sim": r["name_sim"],
            })

    with open(out_path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()), delimiter="\t")
        w.writeheader()
        w.writerows(rows)
    print(f"카드 {len(rows)}건 → {out_path.name}")


if __name__ == "__main__":
    main()
