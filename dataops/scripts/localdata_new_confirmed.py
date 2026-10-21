#!/usr/bin/env python3
"""신규 등록 후보 확정 리스트 조립 — 검증·검수·메뉴 확인 산출물을 G1 관문(bakery_gate)으로 가른다.

대상: 네이버에 있는 후보 — EXIST, 또는 REVIEW 검수 INCLUDE(주소일치 검수)
판정 (2026-09-20 부터 G1 — 08-21/28 등록에서 빵집 아닌 곳이 대량으로 새어 든 두 규칙을 대신한다):
  PASS       네이버 카테고리 잎이 빵집 잎(또는 하위 분류 없는 '카페,디저트', 아이스크림·빙수)이거나,
             메뉴에서 제품 빵이 몫 조건을 채움 → 확정 리스트
  HOLD       비빵 잎인데 제품 빵이 많음 · 메뉴 없음 · 빵 조금 · 샌드위치/샐러드 → <out>_hold.tsv (사람 검수)
  BLOCK      비빵 잎이거나 메뉴에 제품 빵이 없음 → <out>_blocked.tsv (기록만)
  NEED_MENU  카테고리로 못 가르는데 메뉴를 아직 안 긁음 → <out>_need_menu.tsv (localdata_bread_check 를 먼저 돌릴 것)
  (09-20 전 규칙: A. EXIST ∩ 경로 부분일치 BAKERY · B. REVIEW INCLUDE ∩ BAKERY · C. 메뉴 '빵류' 1개 이상 — 여기서 새었다)
  D. 1차 경계 검수 눈확정 명단(박뚜기·카프리쵸사 등 11곳)은 사람이 본 것이라 그대로 편입
제외:
  - 프랜차이즈: FRANCHISE_BRANDS + 실증 추가 브랜드 (닥터로빈·런던베이글뮤지엄·
    팀홀튼·디저트39·코코호도) + 비빵 체인(bakery_gate.NON_BAKERY_CHAINS — 파파이스·롯데리아·피자스쿨…)
  - placeId 중복은 인허가일 최신 레코드만 (이중 인허가·중복 등재)

출력: matching/localdata_new_confirmed.tsv (+ _hold · _blocked · _need_menu)
사용: python scripts/localdata_new_confirmed.py [--verify … --bread … --out …]
"""
import argparse
import csv
import json
from collections import Counter
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from localdata_match import FRANCHISE_BRANDS, NONWORD
from bakery_gate import NON_BAKERY_CHAINS, gate

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "matching" / "localdata_new_confirmed.tsv"

EXTRA_FRANCHISE = ["닥터로빈", "런던베이글뮤지엄", "팀홀튼", "디저트39", "코코호도"]
_FR = [NONWORD.sub("", b.lower()) for b in FRANCHISE_BRANDS + EXTRA_FRANCHISE + NON_BAKERY_CHAINS]

# 1차 경계 검수(08-14) 눈확정 — 메뉴/텍스트/상호 증거로 개별 판정한 11곳
CURATED = {
    "샌디(SANDY)", "사천향수당(산책&브런치)", "군산찰진찹쌀꽈배기", "아이짱 붕어빵",
    "꽃&케이크충주점", "헤리브 플라워 케이크 스튜디오(Herive flower cake studio)",
    "보헤브(bohev)", "오로드 커피앤샌드위치",
    "박뚜기 소금빵", "카프리쵸사 CCS", "명품만두찹쌀꽈배기",
}


def is_franchise(name: str) -> bool:
    n = NONWORD.sub("", (name or "").lower())
    n = n.replace("커피베이커리", "")  # 복합어 오탐 가드('커피베이' 프랜차이즈와 충돌)
    return any(b in n for b in _FR)


def jsonl(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


def write_tsv(path, header, rows):
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t")
        w.writerow(header)
        w.writerows(rows)


HEADER = ["mgmt_no", "lic_name", "naver_place_id", "naver_name", "naver_category",
          "road_address", "tel", "licensed_ymd", "dataset", "dist_m", "name_sim", "evidence"]


def row_of(r, why):
    c = r["candidate"]
    return [r["mgmtNo"], r["name"], c["placeId"], c["name"], c["category"],
            c["roadAddress"], c.get("tel") or "", r["licensedYmd"], r["dataset"],
            c["distanceM"], c["nameSimilarity"], why]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--verify", default=str(ROOT / "matching" / "new_verify_results.jsonl"))
    ap.add_argument("--judgments", default=str(ROOT / "matching" / "new_review_judgments.jsonl"))
    ap.add_argument("--bread", default=str(ROOT / "matching" / "bread_check_results.jsonl"))
    ap.add_argument("--out", default=str(OUT))
    args = ap.parse_args()

    results = jsonl(args.verify)
    # 재시도 재크롤은 같은 mgmtNo 레코드를 덧붙인다 — 최신(마지막) 레코드가 판정의 현재값
    results = list({r["mgmtNo"]: r for r in results}.values())
    judgments = jsonl(args.judgments) if Path(args.judgments).exists() else []
    review_include = {j["mgmtNo"] for j in judgments if j["tier"] == "INCLUDE"}
    # 메뉴 확인도 다시 긁으면 덧붙는다 — 메뉴 전체(menus)가 있는 최신 기록만 쓴다(09-20 전 기록은 빵류 이름만 있다)
    menus_by_place = {}
    if Path(args.bread).exists():
        for b in jsonl(args.bread):
            if "menus" in b:
                menus_by_place[b["placeId"]] = b["menus"]

    final, held, blocked, need_menu, franchise = [], [], [], [], []
    for r in results:
        c = r.get("candidate")
        if not c:
            continue
        if not (r["judgment"] == "EXIST" or (r["judgment"] == "REVIEW" and r["mgmtNo"] in review_include)):
            continue
        if is_franchise(r["name"]) or is_franchise(c["name"]):
            franchise.append(r["name"])
            continue
        if r["judgment"] == "EXIST" and r["name"] in CURATED:
            final.append((r, "1차 경계 눈확정"))
            continue
        verdict, why = gate(c.get("category"), c.get("name") or r["name"], menus_by_place.get(c["placeId"]))
        why = ("검수O · " if r["judgment"] == "REVIEW" else "") + why
        {"PASS": final, "HOLD": held, "BLOCK": blocked, "NEED_MENU": need_menu}[verdict].append((r, why))

    # placeId 중복 — 인허가일 최신만 (이중 인허가·중복 등재)
    best = {}
    for r, why in final:
        pid = r["candidate"]["placeId"]
        if pid not in best or r["licensedYmd"] > best[pid][0]["licensedYmd"]:
            best[pid] = (r, why)
    rows = sorted(best.values(), key=lambda t: t[0]["licensedYmd"], reverse=True)

    out = Path(args.out)
    write_tsv(out, HEADER, [row_of(r, why) for r, why in rows])
    for suffix, items in (("hold", held), ("blocked", blocked), ("need_menu", need_menu)):
        write_tsv(out.with_name(f"{out.stem}_{suffix}.tsv"), HEADER, [row_of(r, why) for r, why in items])

    print(f"확정(PASS) {len(rows)} (편입 {len(final)} − placeId 중복 {len(final) - len(rows)}) → {out}")
    print(f"사람 검수(HOLD) {len(held)} · 차단(BLOCK) {len(blocked)} · 메뉴 확인 필요(NEED_MENU) {len(need_menu)}"
          + (" ← localdata_bread_check 를 먼저 돌릴 것" if need_menu else ""))
    print(f"프랜차이즈·비빵 체인 제외 {len(franchise)}:", sorted(set(franchise))[:12])
    print("확정 근거:", Counter(why.split(" — ")[0].split("'")[0].strip() for _, why in rows).most_common(6))
    recent = sum(1 for r, _ in rows if r["licensedYmd"] >= "20260501")
    print(f"신규 오픈 {recent} / 커버리지 갭 {len(rows) - recent}")


if __name__ == "__main__":
    main()
