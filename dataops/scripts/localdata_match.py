#!/usr/bin/env python3
"""LOCALDATA 인허가 레코드 ↔ 운영 DB 가게 매칭 — 파일럿.

입력:
  matching/localdata_raw/stores_active.tsv       운영 가게 (id/name/address/phone/lat/lon)
  matching/localdata_raw/{dataset}.jsonl          인허가 레코드 (localdata_fetch.py 산출)
출력:
  matching/localdata_mapping.tsv                  가게별 매칭 판정
  matching/localdata_pilot_summary.md             커버리지·폐업 교차검증·갱신 지연 요약

매칭 신호 우선순위(네이버 매칭 트랙 검증 체계 재사용): 전화 > 좌표 거리 > 주소 건물 일치 > 이름 유사도(보조).
오매칭 비용 비대칭 원칙 — 애매하면 REVIEW. 전화일치라도 거리>50m ∧ 유사도<0.8이면 AUTO 금지(대표번호 함정).
"""
import csv
import json
import math
import re
import unicodedata
from collections import defaultdict
from difflib import SequenceMatcher
from pathlib import Path
from typing import NamedTuple

from pyproj import Transformer

ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "matching" / "localdata_raw"
OUT_TSV = ROOT / "matching" / "localdata_mapping.tsv"
OUT_NEW = ROOT / "matching" / "localdata_new_candidates.tsv"
OUT_MD = ROOT / "matching" / "localdata_pilot_summary.md"

# 역방향(신규 후보) 판별용 상호 키워드 — 수집기와 동일 목록
import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from localdata_fetch import NAME_KEYWORDS

# 우리 DB 벌크 임포트 시점 (2026-05-27~30) — 이 이후 인허가가 곧 "신규 유입"
IMPORT_CUTOFF_YMD = "20260501"

# 신규 후보에서 제외할 프랜차이즈 (서비스 정책: 프랜차이즈 미등록).
# 상호 또는 법인 운영사명 기준 — 정규화(공백·기호 제거) 후 부분 일치.
# 주의: 지역 명물 브랜드(성심당 등)는 프랜차이즈가 아니므로 넣지 않는다.
FRANCHISE_BRANDS = [
    # 베이커리·도넛
    "파리바게뜨", "파리바게트", "파리크라상", "뚜레쥬르", "던킨", "크리스피크림", "앤티앤스",
    # 아이스크림·디저트 체인
    "배스킨라빈스", "베스킨라빈스", "설빙", "와플대학", "스트릿츄러스", "홍루이젠",
    # 커피 프랜차이즈
    "스타벅스", "투썸플레이스", "이디야", "메가엠지씨", "메가커피", "메가mgc",  # 네이버 표기 "메가MGC커피" — 09-26 카페 잎 표본에서 37곳이 새었다
    "컴포즈", "빽다방",
    "커피빈", "폴바셋", "할리스", "탐앤탐스", "엔제리너스", "파스쿠찌", "공차",
    "스무디킹", "요거프레소", "더벤티", "매머드커피", "매머드익스프레스", "커피베이",
    "셀렉토커피", "감성커피", "하삼동커피", "커피에반하다", "만랩커피", "토프레소",
    "드롭탑", "카페베네", "요거트아이스크림의정석", "배러댄와플",
    # 운영 법인 (인허가 상호가 법인명으로 등록되는 경우)
    "씨제이푸드빌", "비알코리아", "에스피씨",
]

DATASET_LABEL = {
    "bakeries": "제과점영업",
    "rest_cafes": "휴게음식점",
    "general_restaurants": "일반음식점",
    "instant_food_processors": "즉석판매제조가공업",
}

GRID = 0.005  # ≈ 500m 격자
CAND_RADIUS_M = 500

TR_5174 = Transformer.from_crs("EPSG:5174", "EPSG:4326", always_xy=True)

SIDO_SHORT = {
    "서울특별시": "서울", "부산광역시": "부산", "대구광역시": "대구", "인천광역시": "인천",
    "광주광역시": "광주", "대전광역시": "대전", "울산광역시": "울산", "세종특별자치시": "세종",
    "경기도": "경기", "강원특별자치도": "강원", "강원도": "강원", "충청북도": "충북",
    "충청남도": "충남", "전라북도": "전북", "전북특별자치도": "전북", "전라남도": "전남",
    "경상북도": "경북", "경상남도": "경남", "제주특별자치도": "제주", "제주도": "제주",
}

CORP_PAT = re.compile(r"\(주\)|주식회사|\(유\)|유한회사|㈜|농업회사법인|영농조합법인")
NONWORD = re.compile(r"[^0-9a-z가-힣]")
ROAD_PAT = re.compile(r"([가-힣0-9·.]+(?:로|길))\s*(\d+(?:-\d+)?)")

_FRANCHISE_NORM = [NONWORD.sub("", b.lower()) for b in FRANCHISE_BRANDS]


def is_franchise(nname: str) -> bool:
    # '커피베이'가 '커피베이커리'(복합어) 안에서 오탐하지 않도록 복합어를 먼저 제거
    nname = nname.replace("커피베이커리", "")
    return any(b in nname for b in _FRANCHISE_NORM)


def norm_name(s: str) -> str:
    s = unicodedata.normalize("NFKC", s or "").lower()
    s = CORP_PAT.sub("", s)
    return NONWORD.sub("", s)


def name_sim(a: str, b: str) -> float:
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    # 포함 관계(지점명·수식어 차이)는 유사도 바닥 보정
    ratio = SequenceMatcher(None, a, b).ratio()
    if (a in b or b in a) and min(len(a), len(b)) >= 3:
        ratio = max(ratio, 0.85)
    return round(ratio, 3)


def norm_phone(s: str) -> str:
    d = re.sub(r"\D", "", s or "")
    return d if len(d) >= 8 else ""


def road_key(addr: str) -> tuple[str, str] | None:
    """도로명주소 → (도로명, 건물본번) — 느슨한 건물 키.

    시군구와 부번을 버린다 — 다른 시의 같은 도로명·본번이 한 키로 모인다. 제외 판정("이미 우리 가게")처럼
    넓게 걸려도 안전한 쪽에만 쓴다. 인허가를 붙이는 쪽은 building_key 를 쓴다. localdata_addr_sweep 은
    이 튜플을 그대로 API 조회 키로 쓰므로 모양을 바꾸지 않는다.
    """
    if not addr:
        return None
    a = addr.strip()
    for full, short in SIDO_SHORT.items():
        a = a.replace(full, short, 1)
    m = ROAD_PAT.search(a)
    if not m:
        return None
    road, bldg = m.group(1), m.group(2).split("-")[0]
    return (road, bldg)


class BuildingKey(NamedTuple):
    """시도(약칭) · 시군구 첫 토큰 · 도로명 · 본번 · 부번 — 인허가 연결용 건물 키.

    시군구는 첫 토큰만 쓴다(수원시 영통구 → 수원시). 도로명은 시 단위로 부여돼 일반구 표기가 빠지거나
    새로 생겨도(화성시 → 화성시 동탄구) 같은 건물로 읽힌다. 주소에 시도·시군구가 없으면 None 이다 —
    채워 넣지 않는다(역검색의 검색 지역은 이웃 구·이웃 시도인 경우가 많았다, 09-26 실측).
    """
    sido: str | None
    city: str | None
    road: str
    main: str
    sub: str

    def index(self):
        """색인 키 — 시도·시군구는 색인에서 빼고 호출자가 가린다(개편 호환·광역시 구 경계·주소 누락 때문에)"""
        return (self.road, self.main, self.sub)


GWANGJU_GU = {"동구", "서구", "남구", "북구", "광산구"}
# 특별시·광역시 — 도로명+건물번호가 구를 넘어 사실상 유일하다(인허가 원본 실측 09-26: 310,111 건물 중 둘 이상의
# 구에 걸친 것 117건 0.04%, 대부분 서울역 같은 구 경계 건물). 도는 0.58%(중앙로 223 = 논산·서산·천안)라 시군까지 본다
METRO_SIDOS = {"서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종"}
# 2026-07-01 행정개편 — 인천 새 구 ← 옛 구. 법정동 기준표 두 스냅샷(08-13 파일 · 09-26 API)의 동 이름 대응으로 확인했다.
# 한쪽 주소만 새 이름일 수 있어 같은 시군구로 본다. 전남광주통합특별시는 시군구 이름이 그대로라 시도 정규화로 푼다
SIGUNGU_SUCCESSORS = {
    ("인천", "검단구"): {"서구"}, ("인천", "서해구"): {"서구"},
    ("인천", "영종구"): {"중구"}, ("인천", "제물포구"): {"중구", "동구"},
}
_SIDO_TOKENS = {**SIDO_SHORT, **{v: v for v in SIDO_SHORT.values()},
                "전남광주통합특별시": "전남광주", "전남광주": "전남광주", "세종시": "세종"}
_CITY_TOKEN = re.compile(r"^[가-힣]+(?:시|군|구)$")
# ROAD_PAT 과 같되 부번 앞뒤 공백을 허용한다 — 네이버 주소에 '덕산로46번길 31 -1' 이 있다. ROAD_PAT 은 road_key 가
# 쓰는 모양 그대로 둔다(localdata_addr_sweep 의 조회 키)
_BUILDING_PAT = re.compile(r"([가-힣0-9·.]+(?:로|길))\s*(\d+)(?:\s*-\s*(\d+))?")


def building_key(addr: str) -> BuildingKey | None:
    """도로명주소 → BuildingKey. 도로명·건물번호를 못 읽으면 None. 시도·시군구는 주소에 있을 때만 채운다"""
    a = unicodedata.normalize("NFKC", addr or "").strip()
    m = _BUILDING_PAT.search(a)
    if not m:
        return None
    tokens = a[:m.start()].split()
    sido = _SIDO_TOKENS.get(tokens[0]) if tokens else None
    rest = tokens[1:] if sido else tokens
    city = next((t for t in rest if _CITY_TOKEN.match(t)), None)
    if sido == "전남광주":
        sido = "광주" if city in GWANGJU_GU else ("전남" if city else None)
    if sido == "세종":
        city = ""
    return BuildingKey(sido, city, m.group(1), m.group(2), m.group(3) or "")


def same_city(a: BuildingKey, b: BuildingKey) -> bool:
    """같은 시군구인가 — 둘 다 알려져 있고, 이름이 같거나 2026-07 개편의 옛 이름·새 이름 관계"""
    if a.sido is None or b.sido is None or a.sido != b.sido or a.city is None or b.city is None:
        return False
    return (a.city == b.city
            or b.city in SIGUNGU_SUCCESSORS.get((a.sido, a.city), ())
            or a.city in SIGUNGU_SUCCESSORS.get((b.sido, b.city), ()))


def _successor_groups():
    """개편 옛·새 이름을 이어진 묶음으로 — 인천: {서구, 검단구, 서해구}, {중구, 동구, 영종구, 제물포구}"""
    parent = {}
    def find(x):
        parent.setdefault(x, x)
        while parent[x] != x:
            x = parent[x]
        return x
    for (sido, new), olds in SIGUNGU_SUCCESSORS.items():
        for old in olds:
            parent[find((sido, old))] = find((sido, new))
    groups = {}
    for x in list(parent):
        groups.setdefault(find(x), set()).add(x[1])
    return {x: (x[0], "|".join(sorted(groups[find(x)]))) for x in parent}


_CITY_GROUP = _successor_groups()


def city_of(key: BuildingKey):
    """시군구 식별 — 개편으로 이어진 옛·새 이름은 한 묶음으로 본다(유일성 판정용)"""
    return _CITY_GROUP.get((key.sido, key.city), (key.sido, key.city))


def haversine_m(lat1, lon1, lat2, lon2) -> float:
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def load_stores() -> list[dict]:
    stores = []
    with open(RAW_DIR / "stores_active.tsv", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            stores.append({
                "id": int(row["id"]),
                "name": row["name"],
                "addr": row["address"],
                "phone": norm_phone(row["phone"]),
                "lat": float(row["lat"]) if row["lat"] else None,
                "lon": float(row["lon"]) if row["lon"] else None,
                "nname": norm_name(row["name"]),
                "rkey": road_key(row["address"]),
            })
    return stores


def load_records() -> dict[str, dict]:
    """관리번호 → 레코드. 키워드 수집 중복은 dedup, 좌표는 WGS84로 변환."""
    recs = {}
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
                if not mgmt or mgmt in recs:
                    continue
                x, y = r.get("CRD_INFO_X") or "", r.get("CRD_INFO_Y") or ""
                lat = lon = None
                try:
                    if x and y:
                        lon, lat = TR_5174.transform(float(x), float(y))
                        if not (33.0 < lat < 39.5 and 124.0 < lon < 132.0):
                            lat = lon = None  # 변환 실패/이상 좌표 배제
                except (ValueError, TypeError):
                    pass
                recs[mgmt] = {
                    "mgmt": mgmt,
                    "ds": ds,
                    # 업태구분명(커피숍·까페·패스트푸드 등 세부) — 카페형 빵집이 어느 카테고리로
                    # 인허가받는지 파일럿에서 실측하기 위한 필드. 없으면 위생업태명으로 대체.
                    "category": (r.get("BZSTAT_SE_NM") or r.get("SNTTN_BZSTAT_NM") or "").strip(),
                    "name": (r.get("BPLC_NM") or "").strip(),
                    "nname": norm_name(r.get("BPLC_NM") or ""),
                    "status": (r.get("SALS_STTS_NM") or "").strip(),
                    "dtl_status": (r.get("DTL_SALS_STTS_NM") or "").strip(),
                    "closed_ymd": (r.get("CLSBIZ_YMD") or "").strip(),
                    "phone": norm_phone(r.get("TELNO") or ""),
                    "rkey": road_key(r.get("ROAD_NM_ADDR") or ""),
                    "lat": lat,
                    "lon": lon,
                    "upd": (r.get("DAT_UPDT_PNT") or r.get("LAST_MDFCN_PNT") or "").strip(),
                    "licensed": (r.get("LCPMT_YMD") or "").strip(),
                }
    return recs


def is_open(rec: dict) -> bool:
    return "폐업" not in rec["status"] and "폐업" not in rec["dtl_status"]


def build_indexes(recs: dict):
    by_phone = defaultdict(list)
    by_cell = defaultdict(list)
    by_rkey = defaultdict(list)
    for rec in recs.values():
        if rec["phone"]:
            by_phone[rec["phone"]].append(rec)
        if rec["lat"] is not None:
            cell = (int(rec["lat"] / GRID), int(rec["lon"] / GRID))
            by_cell[cell].append(rec)
        if rec["rkey"]:
            by_rkey[rec["rkey"]].append(rec)
    return by_phone, by_cell, by_rkey


def candidates_for(store, by_phone, by_cell, by_rkey):
    cands = {}
    if store["phone"]:
        for rec in by_phone.get(store["phone"], []):
            cands[rec["mgmt"]] = rec
    if store["lat"] is not None:
        ci, cj = int(store["lat"] / GRID), int(store["lon"] / GRID)
        for di in (-1, 0, 1):
            for dj in (-1, 0, 1):
                for rec in by_cell.get((ci + di, cj + dj), []):
                    cands[rec["mgmt"]] = rec
    if store["rkey"]:
        for rec in by_rkey.get(store["rkey"], []):
            cands[rec["mgmt"]] = rec
    return list(cands.values())


def score(store, rec) -> dict:
    dist = None
    if store["lat"] is not None and rec["lat"] is not None:
        dist = round(haversine_m(store["lat"], store["lon"], rec["lat"], rec["lon"]))
    sim = name_sim(store["nname"], rec["nname"])
    addr = bool(store["rkey"] and rec["rkey"] and store["rkey"] == rec["rkey"])
    phone = bool(store["phone"] and rec["phone"] and store["phone"] == rec["phone"])
    return {"dist": dist, "sim": sim, "addr": addr, "phone": phone}


def judge_one(sc: dict) -> str:
    """단일 후보에 대한 판정 (AUTO / REVIEW / 무효)."""
    dist, sim, addr, phone = sc["dist"], sc["sim"], sc["addr"], sc["phone"]
    near = dist is not None and dist <= 50
    if phone:
        # 대표번호 함정: 전화일치 + 멀고 + 이름도 다르면 AUTO 금지
        if near or sim >= 0.8 or addr:
            return "AUTO"
        return "REVIEW"
    if dist is not None and dist <= 30 and sim >= 0.8:
        return "AUTO"
    if near and addr and sim >= 0.7:
        return "AUTO"
    if sim >= 0.95 and dist is not None and dist <= 100:
        return "AUTO"
    # 근접·주소 단독은 이웃 가게 노이즈가 대부분(실측: 근접 전용 9,209건 중 sim<0.3이 5,550건)
    # — 최소한의 이름 신호(0.3)를 요구해야 검수 가치가 있는 후보만 남는다
    if (near or addr) and sim >= 0.3:
        return "REVIEW"
    if (dist is not None and dist <= 150 and sim >= 0.5) or sim >= 0.8:
        return "REVIEW"
    return None


def pick_best(store, scored: list[tuple[dict, dict, str]]):
    """AUTO 후보 중 최선 선택. 동일 건물 승계(폐업+영업 공존)는 영업 우선.
    구분 불가한 서로 다른 영업 후보 2개 이상이면 REVIEW로 강등."""
    autos = [(r, s) for r, s, j in scored if j == "AUTO"]
    if autos:
        opens = [(r, s) for r, s in autos if is_open(r)]
        pool = opens or autos
        pool.sort(key=lambda t: (not t[1]["phone"], -t[1]["sim"], t[1]["dist"] if t[1]["dist"] is not None else 9999))
        best_r, best_s = pool[0]
        # 모호성 가드: 영업 상태 AUTO 후보가 2개 이상이고 상호 정규화가 서로 다르면 강등
        if len(opens) >= 2:
            names = {r["nname"] for r, _ in opens}
            if len(names) > 1 and not best_s["phone"]:
                return "REVIEW", best_r, best_s
        return "AUTO", best_r, best_s
    reviews = [(r, s) for r, s, j in scored if j == "REVIEW"]
    if reviews:
        reviews.sort(key=lambda t: (-t[1]["sim"], t[1]["dist"] if t[1]["dist"] is not None else 9999))
        return "REVIEW", reviews[0][0], reviews[0][1]
    return "NOT_FOUND", None, None


def main():
    stores = load_stores()
    recs = load_records()
    print(f"가게 {len(stores):,} / 인허가 레코드 {len(recs):,}")
    by_phone, by_cell, by_rkey = build_indexes(recs)

    rows = []
    counts = defaultdict(int)
    for store in stores:
        cands = candidates_for(store, by_phone, by_cell, by_rkey)
        scored = []
        for rec in cands:
            sc = score(store, rec)
            if sc["dist"] is not None and sc["dist"] > CAND_RADIUS_M and not sc["phone"] and not sc["addr"]:
                continue
            j = judge_one(sc)
            if j:
                scored.append((rec, sc, j))
        judgment, rec, sc = pick_best(store, scored)
        counts[judgment] += 1
        rows.append({
            "store_id": store["id"],
            "store_name": store["name"],
            "mgmt_no": rec["mgmt"] if rec else "",
            "dataset": DATASET_LABEL.get(rec["ds"], "") if rec else "",
            "category": rec["category"] if rec else "",
            "licensed_name": rec["name"] if rec else "",
            "judgment": judgment,
            "phone_match": int(sc["phone"]) if sc else "",
            "dist_m": sc["dist"] if sc and sc["dist"] is not None else "",
            "name_sim": sc["sim"] if sc else "",
            "addr_match": int(sc["addr"]) if sc else "",
            "license_status": (rec["dtl_status"] or rec["status"]) if rec else "",
            "closed_ymd": rec["closed_ymd"] if rec else "",
            "data_updated": rec["upd"] if rec else "",
        })

    with open(OUT_TSV, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()), delimiter="\t")
        w.writeheader()
        w.writerows(rows)
    print(f"판정: {dict(counts)} → {OUT_TSV.name}")

    matched_mgmts = {r["mgmt_no"] for r in rows if r["mgmt_no"]}
    new_stats = find_new_candidates(stores, recs, matched_mgmts)
    write_summary(stores, recs, rows, counts, new_stats)


def find_new_candidates(stores, recs, matched_mgmts):
    """역방향: 영업 중 인허가 레코드인데 우리 DB에 없는 곳 = 신규 가게 후보.

    제과점영업은 전량, 휴게·일반음식점은 상호 키워드 해당분만 대상으로 한다
    (키워드 없는 카페형 빵집은 이 역방향으로는 못 잡는다 — 파일럿 한계로 보고).
    기존 가게로 추정되는 레코드(근접+이름/전화/건물 신호)는 후보에서 제외."""
    s_phone = defaultdict(list)
    s_cell = defaultdict(list)
    s_rkey = defaultdict(list)
    for s in stores:
        if s["phone"]:
            s_phone[s["phone"]].append(s)
        if s["lat"] is not None:
            s_cell[(int(s["lat"] / GRID), int(s["lon"] / GRID))].append(s)
        if s["rkey"]:
            s_rkey[s["rkey"]].append(s)

    def near_existing(rec) -> bool:
        pool = {}
        if rec["phone"]:
            for s in s_phone.get(rec["phone"], []):
                return True  # 전화 일치 → 기존 가게
        if rec["lat"] is not None:
            ci, cj = int(rec["lat"] / GRID), int(rec["lon"] / GRID)
            for di in (-1, 0, 1):
                for dj in (-1, 0, 1):
                    for s in s_cell.get((ci + di, cj + dj), []):
                        pool[s["id"]] = s
        if rec["rkey"]:
            for s in s_rkey.get(rec["rkey"], []):
                pool[s["id"]] = s
        for s in pool.values():
            sim = name_sim(s["nname"], rec["nname"])
            dist = None
            if s["lat"] is not None and rec["lat"] is not None:
                dist = haversine_m(s["lat"], s["lon"], rec["lat"], rec["lon"])
            if dist is not None and dist <= 100 and sim >= 0.6:
                return True
            if rec["rkey"] and s["rkey"] == rec["rkey"] and sim >= 0.5:
                return True
        return False

    new_rows = []
    n_skipped_existing = 0
    n_skipped_franchise = 0
    for rec in recs.values():
        if not is_open(rec):
            continue
        if rec["mgmt"] in matched_mgmts:
            continue
        if rec["ds"] != "bakeries" and not any(kw in rec["name"] for kw in NAME_KEYWORDS):
            continue
        if is_franchise(rec["nname"]):  # 서비스 정책 — 프랜차이즈 미등록
            n_skipped_franchise += 1
            continue
        if near_existing(rec):
            n_skipped_existing += 1
            continue
        new_rows.append({
            "mgmt_no": rec["mgmt"],
            "dataset": DATASET_LABEL[rec["ds"]],
            "category": rec["category"],
            "name": rec["name"],
            "licensed_ymd": rec["licensed"],
            "closed_ymd": "",
            "lat": rec["lat"] if rec["lat"] is not None else "",
            "lon": rec["lon"] if rec["lon"] is not None else "",
        })
    new_rows.sort(key=lambda r: r["licensed_ymd"], reverse=True)
    with open(OUT_NEW, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(new_rows[0].keys()) if new_rows else ["mgmt_no"], delimiter="\t")
        w.writeheader()
        w.writerows(new_rows)
    recent = [r for r in new_rows if re.sub(r"\D", "", r["licensed_ymd"])[:8] >= IMPORT_CUTOFF_YMD]
    print(f"신규 후보 {len(new_rows):,} (임포트 이후 인허가 {len(recent):,}, 프랜차이즈 제외 {n_skipped_franchise:,}) → {OUT_NEW.name}")
    return {"total": len(new_rows), "recent": recent,
            "skipped_existing": n_skipped_existing, "skipped_franchise": n_skipped_franchise}


def load_closure_candidates():
    def lines(fn):
        with open(ROOT / "matching" / fn, encoding="utf-8-sig") as f:
            for line in f:
                line = line.strip()
                if line:
                    yield json.loads(line)
    a = {r["storeId"] for r in lines("match_results.jsonl")
         if r.get("judgment") == "NOT_FOUND" and r.get("candidateCount", 0) == 0}
    b = set()
    for fn in ("eyeball_judgments.jsonl", "parse_check_judgments.jsonl"):
        b |= {r["storeId"] for r in lines(fn)
              if r.get("verdict") == "X" and "폐업" in (r.get("tags") or [])}
    return a, b


def write_summary(stores, recs, rows, counts, new_stats):
    n = len(stores)
    matched = [r for r in rows if r["judgment"] in ("AUTO", "REVIEW")]
    auto = [r for r in rows if r["judgment"] == "AUTO"]
    closed_auto = [r for r in auto if "폐업" in r["license_status"]]
    cand_a, cand_b = load_closure_candidates()
    a_hit = [r for r in closed_auto if r["store_id"] in cand_a]
    b_rows = {r["store_id"]: r for r in rows if r["store_id"] in cand_b}
    b_closed = [r for r in b_rows.values() if "폐업" in r["license_status"] and r["judgment"] == "AUTO"]

    # 갱신 지연 — 매칭 레코드의 DAT_UPDT_PNT 분포 (실응답은 'YYYY-MM-DD HH:MM:SS' 형식)
    upds = sorted(re.sub(r"\D", "", r["data_updated"])[:8] for r in matched if r["data_updated"])
    def pct(x):
        return f"{x / n * 100:.1f}%"

    status_dist = defaultdict(int)
    for r in auto:
        status_dist[r["license_status"] or "?"] += 1
    ds_dist = defaultdict(int)
    for r in auto:
        ds_dist[r["dataset"]] += 1

    md = []
    md.append("# LOCALDATA 파일럿 — 매칭·폐업 교차검증 요약\n")
    md.append(f"- 대상: ACTIVE 가게 {n:,} / 인허가 레코드 {len(recs):,} (dedup 후)\n")
    md.append("## 커버리지\n")
    md.append("| 판정 | 가게 수 | 비율 |\n|---|---:|---:|")
    for k in ("AUTO", "REVIEW", "NOT_FOUND"):
        md.append(f"| {k} | {counts[k]:,} | {pct(counts[k])} |")
    md.append(f"\n업종별 AUTO: {dict(ds_dist)}\n")
    # 제과점영업 외 업종에서 매칭된 가게가 어떤 업태(카테고리)로 인허가받았는지 —
    # 운영 파이프라인의 pull 필터 설계(어떤 카테고리를 받아야 하나)에 쓰는 실측치
    md.append("## 업종 × 업태구분명 (AUTO 매칭)\n")
    md.append("| 업종 | 업태구분명 | 가게 수 |\n|---|---|---:|")
    cat_dist = defaultdict(int)
    for r in auto:
        cat_dist[(r["dataset"], r["category"] or "?")] += 1
    for (ds, cat), v in sorted(cat_dist.items(), key=lambda t: (t[0][0], -t[1])):
        md.append(f"| {ds} | {cat} | {v:,} |")
    md.append("")
    # 운영 pull 업태 권장 — AUTO 기여 상위 업태(≥50곳)만 남기면 얼마나 좁혀지는가.
    # 주의: API에 업태 서버측 필터(cond)가 없어 "받은 뒤 클라이언트측 필터" 규칙이다.
    md.append("## 운영 pull 업태 권장 (실측 기반 — 클라이언트측 필터)\n")
    for ds_key, ds_name in (("rest_cafes", "휴게음식점"), ("general_restaurants", "일반음식점")):
        auto_cat = defaultdict(int)
        for r in auto:
            if r["dataset"] == ds_name:
                auto_cat[r["category"] or "?"] += 1
        raw_cat = defaultdict(int)
        for rec in recs.values():
            if rec["ds"] == ds_key:
                raw_cat[rec["category"] or "?"] += 1
        total_auto = sum(auto_cat.values())
        total_raw = sum(raw_cat.values())
        if not total_auto:
            continue
        # 절대 기여(AUTO≥50)에 효율 하한(레코드 1,000건당 1.5곳)을 결합 —
        # 다방(80k건 중 81곳) 같은 대용량·저효율 업태를 제외하기 위함
        rec_set = [c for c, v in auto_cat.items()
                   if v >= 50 and v / max(raw_cat.get(c, 1), 1) >= 0.0015]
        cover = sum(auto_cat[c] for c in rec_set)
        raw_share = sum(raw_cat.get(c, 0) for c in rec_set)
        dropped = [c for c, v in auto_cat.items() if v >= 50 and c not in rec_set]
        md.append(f"- **{ds_name}**: 권장 업태 {sorted(rec_set, key=lambda c: -auto_cat[c])}")
        md.append(f"  → 매칭 커버 {cover / total_auto * 100:.1f}% / 레코드 {raw_share:,}·{raw_share / total_raw * 100:.1f}% (전체 {total_raw:,})")
        if dropped:
            md.append(f"  (저효율 제외: {', '.join(f'{c} {auto_cat[c]}곳/{raw_cat.get(c, 0):,}건' for c in dropped)})")
    md.append("  ※ 일반음식점 원본 분포는 키워드 수집 조건부라 '기타' 편중 — 카테고리 필터 대신 키워드 유지 권장\n")
    md.append("## AUTO 매칭 가게의 인허가 상태\n")
    md.append("| 상태 | 수 |\n|---|---:|")
    for k, v in sorted(status_dist.items(), key=lambda t: -t[1]):
        md.append(f"| {k} | {v:,} |")
    md.append(f"\n**ACTIVE 가게인데 인허가 폐업: {len(closed_auto):,}곳** (교차검증 대상)\n")
    md.append("## 폐업 교차검증\n")
    md.append(f"- 후보 A (네이버 검색 후보 0건, {len(cand_a):,}곳) ∩ 인허가 폐업(AUTO): **{len(a_hit):,}곳** ← 고신뢰 폐업 리스트")
    md.append(f"- 후보 B (눈검사 폐업 확정, {len(cand_b)}곳) 중 인허가 폐업 확인: **{len(b_closed)}곳** / 매칭 자체 성공 {len([r for r in b_rows.values() if r['judgment'] != 'NOT_FOUND'])}곳")
    md.append("\n## 갱신 지연 (매칭 레코드 DAT_UPDT_PNT)\n")
    if upds:
        md.append(f"- 최소 {upds[0]} / 중앙값 {upds[len(upds)//2]} / 최대 {upds[-1]}")
        md.append(f"  (최대치가 수집 전날 = 피드는 사실상 일 단위 갱신)")
    # 신선도 실증: 최근 폐업(월별)이 이미 피드에 반영돼 있는가
    md.append("- 최근 폐업 레코드 반영 (AUTO 매칭 기준, 폐업일 월별):")
    monthly_closed = defaultdict(int)
    for r in auto:
        if "폐업" in r["license_status"] and r["closed_ymd"]:
            ym = re.sub(r"\D", "", r["closed_ymd"])[:6]
            if ym >= "202605":
                monthly_closed[ym] += 1
    for ym, v in sorted(monthly_closed.items()):
        md.append(f"  - {ym} 폐업: {v:,}건 반영됨")
    md.append("\n## 신규 가게 후보 (역방향 — 4단계 유입량 실측)\n")
    md.append(f"- 영업 중 + 빵집 관련(제과점 전량, 휴게·일반은 상호 키워드) + 우리 DB 미매칭: **{new_stats['total']:,}곳**")
    md.append(f"  (기존 가게 추정 제외 {new_stats['skipped_existing']:,}건, **프랜차이즈 제외 {new_stats['skipped_franchise']:,}건** — 서비스 정책)")
    recent = new_stats["recent"]
    md.append(f"- 그중 인허가일 {IMPORT_CUTOFF_YMD} 이후(임포트 이후 신규 유입): **{len(recent):,}곳**")
    monthly = defaultdict(int)
    for r in recent:
        monthly[re.sub(r"\D", "", r["licensed_ymd"])[:6]] += 1
    for ym, v in sorted(monthly.items()):
        md.append(f"  - {ym}: {v:,}곳")
    md.append("""
## 신규 트랙 운영 설계 권고 (파일럿 결론)

키워드는 판별기가 아니다 — 일반음식점 '기타' 업태의 비키워드 베이커리(실사례: 시월호두·밸런티·그초록)는
키워드 수집으로는 영영 못 찾는다. 구조적 해법은 **인허가일 증분 + 네이버 카테고리 판별**:

1. 월 1회 `cond[LCPMT_YMD::GTE/LT]`로 3개 업종 신규 인허가 **전량** pull (업태·키워드 제한 없음)
   — 08-11 실측: 2026-07 한 달 제과점 306 / 휴게음식점 2,871 / 일반음식점 5,138 = **월 ~8.3천 건, ~85콜**
2. 프랜차이즈 상호·명백한 비대상 업태(편의점·키즈카페·극장·푸드트럭)만 사전 제외
3. 전량 `new_store_candidate(WAITING_NAVER)` 적재 — 키워드 보유분은 우선순위만 상향
4. **빵집 여부 판별은 네이버 매칭 워커가 검색 결과 카테고리(베이커리·디저트·카페)로** 결정
   (월 ~8.3천 건 × ~5초 ≈ 11.5시간/월 — 주간 크롤 예산과 같은 자릿수)
5. 네이버 미등록(갓 오픈)은 `match_attempts` 재시도로 후속 주기에 흡수

기존 가게 폐업 감지는 카테고리 무관 — 매핑된 관리번호를 `cond[DAT_UPDT_PNT::GTE]` 상태 증분에 조인만 하면 된다.
미매핑 잔여 가게의 재매칭은 주소 타깃 조회(`ROAD_NM_ADDR::LIKE`, scripts/localdata_addr_sweep.py)가 담당한다.""")
    OUT_MD.write_text("\n".join(md), encoding="utf-8")
    print(f"요약 → {OUT_MD.name}")


if __name__ == "__main__":
    main()
