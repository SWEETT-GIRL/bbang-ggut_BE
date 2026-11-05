#!/usr/bin/env python3
"""역방향 탐색 결과 ↔ 인허가(mgmt_no) 대조 — 등록 가능한 신규 빵집 명단을 만든다.

naver_bakery_sweep 은 "우리 DB 에 없는 네이버 가게" 까지만 만든다. 등록하려면 인허가가 필요하다
(우리 유입은 인허가에서 시작한다 — 가게 하나에 mgmt_no 가 붙어야 폐업·업종 변경을 따라갈 수 있다).
여기서 그 대조를 한다. 네이버를 안 건드린다 — 탐색 결과 파일과 인허가 원본, 운영 읽기 전용만 쓴다.

판정 순서
  1. 이미 우리 가게인가  — 탐색의 'known' 은 naver_place_id 기준이라, place id 가 안 붙은 채 이름·주소로
     이미 있는 가게를 "없다" 고 말한다. 운영 store 와 (이름, 도로명+건물번호) 로 먼저 대조한다.
  2. 인허가가 있는가     — 영업중 레코드와 (이름, 건물) 로 대조. 이름이 조금 달라도
     같은 건물이면 유사도 0.85 이상에서 붙인다(지점명·수식어 차이).
     건물은 building_key(09-26) — 도로명·본번·부번이 같고 시군구가 맞아야 한다(find_license). 이전의
     (도로명, 본번) 키는 다른 시의 같은 도로명·본번과 한 건물의 다른 부번을 한 건물로 모았다
     (예: 하동군 중앙로 20 ↔ 화천군 중앙로 20). 1번(이미 우리 가게)은 넓게 걸려도 안전한 쪽이라
     느슨한 road_key 를 그대로 쓴다 — 놓치면 중복 등록이다.

  3. 프랜차이즈인가      — 유입(localdata_new_confirmed)이 쓰는 브랜드 목록으로 뺀다. 우리 DB 는 대형
     프랜차이즈를 안 담는다(운영 ACTIVE 에 파리바게뜨 8곳뿐) — 없는 게 누락이 아니라 정책이다.
     이걸 안 빼면 "빵집 수천 곳을 놓치고 있다" 는 틀린 결론이 난다(09-22 실측: 등록 후보의 68.7%).

출력: matching/naver_sweep_new_candidates.tsv  등록 후보 (프랜차이즈 뺀 것) ← 이것이 결과물
      matching/naver_sweep_matched.tsv         인허가가 붙은 것 전부 (프랜차이즈 포함)
      matching/naver_sweep_unmatched.tsv       인허가를 못 찾은 것 (주소 이상·미신고·폐업 뒤 재개업 등)

사용
    python scripts/naver_sweep_match.py                    # 카테고리 잎이 확실한 빵집만
    python scripts/naver_sweep_match.py --stores lab       # 운영 터널이 없을 때
    python scripts/naver_sweep_match.py --include-unknown  # 하위분류 없는 것까지 (메뉴 확인 전이라 잡음이 많다)
"""
import argparse
import collections
import csv
import glob
import io
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bakery_gate import ALLOWED_LEAVES, BAKERY_LEAVES, GENERIC_LEAVES, leaf_of  # noqa: E402
from localdata_match import METRO_SIDOS, building_key, city_of, name_sim, norm_name, road_key, same_city  # noqa: E402
from localdata_new_confirmed import is_franchise  # noqa: E402  — 유입이 쓰는 것과 같은 브랜드 목록
from store_tags import read_env  # noqa: E402

from cloud_runtime import matching_dir, raw_dir

ROOT = Path(__file__).resolve().parent.parent
MATCHING = matching_dir()
RAW_DIR = raw_dir()
SWEEP_GLOB = str(MATCHING / "naver_bakery_sweep_2*.jsonl")
OUT_MATCHED = MATCHING / "naver_sweep_matched.tsv"
OUT_UNMATCHED = MATCHING / "naver_sweep_unmatched.tsv"
OUT_CANDIDATES = MATCHING / "naver_sweep_new_candidates.tsv"
NAME_SIM_MIN = 0.85
# 인허가 상호가 '시설' 이고 그 이름이 네이버 상호 안에 통째로 들어 있으면, 시설 인허가에 잘못 붙은 것이다.
#   예: '베이크 롯데백화점 잠실점' → '롯데백화점 잠실점'(백화점 자신의 인허가)
# name_sim 의 포함 보정이 바닥을 0.85 로 올리는데 하한도 0.85 라, 이런 포함 관계는 전부 통과한다.
# 반대 방향(네이버 상호 ⊂ 인허가 상호)은 정상이다 — '바삭붕요' ⊂ '(주)케이알산업 청도(하)휴게소 바삭붕요'.
VENUE_WORDS = ("백화점", "마트", "아울렛", "몰", "터미널", "공항", "병원", "대학교", "호텔", "리조트")


def load_sweep(include_unknown):
    """탐색 결과 → place id 로 중복 제거한 가게. 잎이 확실한 것만(또는 --include-unknown 이면 모름까지)

    같은 가게가 여러 검색에 나오면 주소에 시도가 적힌 쪽을 남긴다. 검색 지역(_region)은 진단용으로만 싣는다 —
    이웃 구·이웃 시도 검색에서 나온 가게가 많아 시군구를 채우는 데 쓰지 않는다(09-26 실측).
    """
    want = set(BAKERY_LEAVES) | set(ALLOWED_LEAVES)
    if include_unknown:
        want |= set(GENERIC_LEAVES)
    out = {}
    for path in sorted(glob.glob(SWEEP_GLOB)):
        for line in io.open(path, encoding="utf-8"):
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            for it in r["items"]:
                pid = it.get("placeId")
                if not it.get("known") and pid and leaf_of(it.get("category")) in want:
                    cur = out.get(pid)
                    if cur is None or (not _has_sido(cur.get("address")) and _has_sido(it.get("address"))):
                        out[pid] = {**it, "_region": r.get("region")}
    return out


def _has_sido(address):
    key = building_key(address or "")
    return key is not None and key.sido is not None


def find_license(nname, bkey, licenses, by_main=None):
    """역검색 가게의 건물 키 → (인허가, 이름 유사도, 사유). 붙이지 않으면 인허가가 None.

    1) 주소에 시군구가 있으면 같은 시군구(개편 옛·새 이름 포함)의 인허가 중 이름이 가장 가까운 것
    2) 거기서 못 찾았고 특별시·광역시면, 같은 시도의 다른 구 — 도로명+번호가 구를 넘어 사실상 유일해서다
       (서울역 = 네이버 중구 · 인허가 용산구). 도는 시군이 다르면 다른 건물이다
    3) 주소에 시도·시군구가 없으면 도로명+번호가 같은 곳 전부에서 찾는다
    2)·3)은 이름이 맞는 인허가가 한 시군구에만 있을 때만 붙인다 — 여러 곳이면 어느 건물인지 모른다
    4) 부번까지 같은 곳에서 못 찾았으면, 같은 시군구·같은 본번에서 정규화한 상호가 완전히 같은 인허가가 딱 하나일 때만
       (by_main) — 같은 번지의 부번 표기 차이(해안로 32 ↔ 32-8). 상호가 조금이라도 다르면 붙이지 않는다
       (신라명가 424-33 ↔ 신라명가한식당 424-11 은 다른 가게였다)
    """
    lic, score, how = _find_same_building(nname, bkey, licenses)
    if lic is None and by_main is not None and bkey.sido is not None and bkey.city is not None and how != "AMBIGUOUS_CITY":
        exact = [c for c in by_main.get((bkey.road, bkey.main), [])
                 if c["nname"] == nname and same_city(bkey, c["bkey"])]
        if len({c["mgmt_no"] for c in exact}) == 1:
            return exact[0], 1.0, "SAME_MAIN_EXACT_NAME"
    return lic, score, how


def _find_same_building(nname, bkey, licenses):
    pool = licenses.get(bkey.index(), [])
    if not pool:
        return None, 0.0, "NO_BUILDING"
    if bkey.sido is not None:
        pool = [c for c in pool if c["bkey"].sido == bkey.sido]
        if bkey.city is not None:
            lic, score = best_match(nname, [c for c in pool if same_city(bkey, c["bkey"])], lambda c: c["nname"])
            if lic:
                return lic, score, "SAME_CITY"
            if bkey.sido not in METRO_SIDOS:
                return None, score, "NO_LICENSE_IN_CITY" if pool else "NO_BUILDING"
    named = [c for c in pool if name_sim(nname, c["nname"]) >= NAME_SIM_MIN]
    cities = {city_of(c["bkey"]) for c in named}
    if len(cities) > 1:
        return None, 0.0, "AMBIGUOUS_CITY"
    lic, score = best_match(nname, named, lambda c: c["nname"])
    if lic:
        return lic, score, "UNIQUE_IN_SIDO" if bkey.sido is not None else "UNIQUE_NO_SIDO"
    return None, score, "NO_LICENSE" if pool else "NO_BUILDING"


def load_licenses():
    """영업중 인허가 → (도로명 건물키) 별 목록. 폐업은 등록 대상이 아니라 뺀다.

    mgmt_no 로 중복을 뗀다 — 같은 대장을 키워드로 한 번, 전량으로 또 한 번 받으면 파일에 두 번 들어온다
    (general_restaurants 가 2026-09-24 에 키워드 → 전량으로 바뀌었다).
    """
    by_building = collections.defaultdict(list)
    by_main = collections.defaultdict(list)
    seen_mgmt = set()
    total = alive = no_key = 0
    for path in sorted(RAW_DIR.glob("*.jsonl")):
        for line in io.open(path, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            total += 1
            status = (r.get("DTL_SALS_STTS_NM") or r.get("SALS_STTS_NM") or "").strip()
            if "폐업" in status:
                continue
            mgmt = r.get("MNG_NO") or ""
            if not mgmt or mgmt in seen_mgmt:
                continue
            bkey = building_key(r.get("ROAD_NM_ADDR") or "")
            if not bkey:
                no_key += 1
                continue
            seen_mgmt.add(mgmt)
            alive += 1
            rec = {"mgmt_no": r.get("MNG_NO") or "", "name": r.get("BPLC_NM") or "",
                   "nname": norm_name(r.get("BPLC_NM") or ""), "addr": r.get("ROAD_NM_ADDR") or "", "bkey": bkey,
                   "licensed": r.get("LCPMT_YMD") or "", "dataset": path.stem}
            by_building[bkey.index()].append(rec)
            by_main[(bkey.road, bkey.main)].append(rec)
    print(f"인허가 {total:,}건 중 영업중·건물 키 있는 것 {alive:,}건 · 건물 {len(by_building):,}개 "
          f"(도로명주소로 건물 키를 못 세운 영업중 {no_key:,}건)", flush=True)
    return by_building, by_main


def load_prod_stores(env_path, source):
    """우리 가게 → 건물키별 (정규화 이름). 'known' 이 place id 기준이라 놓치는 것을 여기서 잡는다.

    store_tags.prod_csv 는 도커 안 psql 로 host.docker.internal 을 거치는데, SSH 터널이 127.0.0.1 에만
    묶여 있으면 컨테이너에서 못 닿는다. 여기서는 호스트에서 psycopg2 로 바로 붙는다 — 읽기 전용이다.

    --stores lab 은 터널이 없을 때 쓴다. 랩은 운영 덤프 복제본이라 덤프 뒤에 등록된 가게가 빠진다 —
    그만큼 "이미 우리 가게" 를 놓치고 신규로 셀 수 있다. 등록 직전에는 운영으로 다시 확인한다.
    """
    import psycopg2
    if source == "lab":
        conn = psycopg2.connect(host="127.0.0.1", port=int(os.environ.get("LAB_DB_PORT", "5432")),
                                user="sweetgirl", password="soboro-lab", dbname="soboro",
                                connect_timeout=10, application_name="naver_sweep_match")
    else:
        from cloud_runtime import serving_connect
        conn = serving_connect(env_path)
    cur = conn.cursor()
    cur.execute("SELECT id, name, address FROM store WHERE status = 'ACTIVE'")
    rows = cur.fetchall()
    cur.close()
    conn.close()
    if source == "lab":
        print("⚠ 가게 목록을 랩(운영 덤프 복제본)에서 읽었다 — 덤프 뒤 등록분이 빠진다", flush=True)
    by_road = collections.defaultdict(list)
    for sid, name, address in rows:
        key = road_key(address)
        if key:
            by_road[key].append((norm_name(name), sid))
    print(f"운영 ACTIVE 가게 {len(rows):,}곳 · 건물 {len(by_road):,}개", flush=True)
    return by_road


def best_match(nname, candidates, get_name):
    """같은 건물 후보 중 이름이 가장 가까운 것 — 완전일치 우선, 아니면 유사도 하한 이상"""
    best, score = None, 0.0
    for c in candidates:
        s = name_sim(nname, get_name(c))
        if s > score:
            best, score = c, s
    return (best, score) if score >= NAME_SIM_MIN else (None, score)


def dedupe_by_license(rows, counts):
    """인허가 하나에 네이버 가게가 둘 이상 붙으면 — 그 인허가가 가게가 아니라 건물·법인일 때가 많다.

    VENUE_WORDS 로는 '(주)신세계' 를 못 거른다('백화점' 이 이름에 없다). 그런데 같은 인허가에 서로 다른
    가게가 둘 붙었다는 사실 자체가 신호다 — 09-22 실측 5건 중 '(주)신세계' 는 '그래인스쿠키 신세계 면세점'
    과 '화미가 신세계백화점 본점' 을 동시에 끌었다(둘 다 오탐).

    이름이 완전히 같은 것이 딱 하나면 그것만 남기고(지점명 차이), 아니면 전부 뗀다.
    후보표가 mgmt_no UNIQUE 라 어차피 하나만 들어가는데, 그 하나가 먼저 읽힌 것이어서는 안 된다.
    """
    by_mgmt = collections.defaultdict(list)
    for r in rows:
        by_mgmt[r["mgmt_no"]].append(r)
    out = []
    for mgmt, group in by_mgmt.items():
        if len(group) == 1:
            out.append(group[0])
            continue
        exact = [r for r in group if float(r["nameSim"]) >= 0.999]
        if len(exact) == 1:
            out.append(exact[0])
            counts["인허가 중복 — 완전일치 하나만 남김"] += len(group) - 1
        else:
            counts["인허가 하나에 여러 가게 — 건물·법인 인허가로 보고 전부 뗌"] += len(group)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sweep", help="이번 실행의 검색 JSONL만 대조")
    ap.add_argument("--env", default=str(ROOT / ".env"))
    ap.add_argument("--include-unknown", action="store_true", help="하위분류 없는 잎까지 대조한다")
    ap.add_argument("--stores", choices=("prod", "lab"), default="prod",
                    help="우리 가게 목록을 어디서 읽나 (터널이 없으면 lab — 덤프 뒤 등록분이 빠진다)")
    ap.add_argument("--out-dir", default=None, help="산출 TSV 를 둘 곳 (기본 matching/ — 측정할 때는 다른 곳으로)")
    args = ap.parse_args()
    global SWEEP_GLOB
    if args.sweep: SWEEP_GLOB = glob.escape(args.sweep)
    out_dir = Path(args.out_dir) if args.out_dir else OUT_CANDIDATES.parent
    out_candidates, out_matched, out_unmatched = (out_dir / p.name for p in (OUT_CANDIDATES, OUT_MATCHED, OUT_UNMATCHED))

    sweep = load_sweep(args.include_unknown)
    print(f"탐색 결과 중 대조 대상 {len(sweep):,}곳", flush=True)
    licenses, licenses_by_main = load_licenses()
    prod = load_prod_stores(args.env, args.stores)

    matched, unmatched = [], []
    counts = collections.Counter()
    for pid, it in sweep.items():
        nname = norm_name(it.get("name") or "")
        key = road_key(it.get("address") or "")
        if not key:
            counts["주소에서 도로명을 못 읽음"] += 1
            unmatched.append({**it, "reason": "NO_ROAD", "mgmt_no": "", "storeId": ""})
            continue

        # 1) 이미 우리 가게인가 (place id 가 안 붙었을 뿐)
        hit, _ = best_match(nname, prod.get(key, []), lambda c: c[0])
        if hit:
            counts["이미 우리 가게 (place id 미연결)"] += 1
            unmatched.append({**it, "reason": "ALREADY_OURS", "mgmt_no": "", "storeId": hit[1]})
            continue

        # 2) 인허가가 있는가 — 부번까지 같은 건물, 시군구가 맞는 것만
        bkey = building_key(it.get("address") or "")
        lic, score, how = find_license(nname, bkey, licenses, licenses_by_main)
        if how == "AMBIGUOUS_CITY":
            counts["같은 도로명·번호의 이름 맞는 인허가가 여러 시군구에 있음"] += 1
            unmatched.append({**it, "reason": "AMBIGUOUS_CITY", "mgmt_no": "", "storeId": ""})
            continue
        if lic and lic["nname"] != nname and lic["nname"] in nname \
                and any(v in lic["name"] for v in VENUE_WORDS):
            counts["시설 인허가에 잘못 붙음 (백화점·마트 안 가게)"] += 1
            unmatched.append({**it, "reason": "VENUE_LICENSE", "mgmt_no": lic["mgmt_no"], "storeId": ""})
            continue
        if lic:
            counts["인허가 붙음 — 등록 후보"] += 1
            counts[f"  (붙은 근거: {how})"] += 1
            matched.append({"placeId": pid, "naverName": it.get("name"), "category": it.get("category"),
                            "address": it.get("address"), "mgmt_no": lic["mgmt_no"], "licenseName": lic["name"],
                            "licensed_ymd": lic["licensed"], "dataset": lic["dataset"], "nameSim": score,
                            "licenseAddress": lic["addr"], "matchedBy": how})
        else:
            no_building = how == "NO_BUILDING"
            counts["건물 자체가 인허가에 없음" if no_building else "같은 건물에 영업중 인허가 없음"] += 1
            unmatched.append({**it, "reason": "NO_BUILDING" if no_building else "NO_LICENSE",
                              "mgmt_no": "", "storeId": ""})

    print(f"\n■ 판정 ({len(sweep):,}곳)")
    for k, n in counts.most_common():
        print(f"  {n:>7,} ({n / len(sweep):>5.1%})  {k}")

    keep = [r for r in matched if not (is_franchise(r["naverName"]) or is_franchise(r["licenseName"]))]
    keep = dedupe_by_license(keep, counts)
    print(f"\n■ 프랜차이즈 {len(matched) - len(keep):,}곳 제외 → **등록 후보 {len(keep):,}곳**")
    if keep:
        by_ds = collections.Counter(r["dataset"] for r in keep)
        print("   인허가 대장:", " · ".join(f"{k} {n:,}" for k, n in by_ds.most_common()))
        with io.open(out_candidates, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(keep[0]), delimiter="\t")
            w.writeheader()
            w.writerows(keep)
        print(f"→ {out_candidates.name} {len(keep):,}행  ← 등록 파이프라인 입력")

    if matched:
        with io.open(out_matched, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(matched[0]), delimiter="\t")
            w.writeheader()
            w.writerows(matched)
        print(f"\n→ {out_matched.name} {len(matched):,}행")
    if unmatched:
        with io.open(out_unmatched, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(unmatched[0]), delimiter="\t")
            w.writeheader()
            w.writerows(unmatched)
        print(f"→ {out_unmatched.name} {len(unmatched):,}행")
    return 0


if __name__ == "__main__":
    sys.exit(main())
