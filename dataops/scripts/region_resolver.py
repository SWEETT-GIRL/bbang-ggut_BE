#!/usr/bin/env python3
"""공통 법정동 resolver (v3 P4-4 · FR-11) — 좌표 → 카카오 역산 → 버전 있는 기준표로 검증 → 앱 3필드 투영.

이전(region_recompute.py)과 다른 점
- "해소"를 (구, 동) 이름쌍이 운영 search_region 에 있는지로 보지 않는다. 카카오 B 응답의 10자리 법정동 코드를
  공식 기준표(reference/bjd_code_20260813.csv)에서 찾고, 코드로 상위 계층(시도·시군구·읍면동)을 따라 올라가
  전부 존재하는지·이름이 응답과 같은지 대조한다. 폐지 코드(예: 강원도 42 → 강원특별자치도 51 개편 전)는 해소가 아니다.
- 행정동(H)은 따로 기록하고 법정동 대신 쓰지 않는다 — H 만 있으면 UNRESOLVED 다.
- 근거를 덮어쓰지 않는다. 한 번의 역산이 region_resolution 한 행이고, 조회 좌표·기준표·투영 버전을 함께 남긴다.
- BE Region3rd 로 풀리는지는 소비자 준비도로만 기록한다. 풀리지 않는다고 법정동 이름을 잘라 맞추지 않는다
  (스펙: "앱 축약명·enum 때문에 법정동 원형을 잘라내지 않는다").

앱 3필드 투영 v2 — 2026-09-26 결정: "앱 지역은 ~동으로 묶는다"
  region_1st = 시도 약칭 (전남광주통합특별시는 광주 5개 구면 광주, 아니면 전남)
  region_2nd = 시군구 이름의 첫 토큰 (성남시 분당구 → 성남시). 세종은 시군구가 없어 읍면동 이름
  region_3rd = 법정동에서 끝의 N가만 접는다 (성수동1가·2가 → 성수동, 동인동1가~4가 → 동인동).
               거리형 법정동은 접은 이름 그대로다 (을지로1가 → 을지로). "동"을 덧붙이지 않는다 —
               을지로동 같은 이름은 행정동이라 붙이면 H 를 B 자리에 쓰는 셈이 된다. 리는 투영에서 뺀다
  원형 이름·법정동 코드는 region_resolution.hierarchy·bjd_code 에 그대로 남는다 — 접기는 앱 표시 단위의 선택이지
  정보 손실이 아니다.

왜 N가 접기인가 (가게 좌표 52,288곳 실측): 깔끔한 "~동" 비율이 법정동 그대로 77.1% → N가 접기 81.3%.
행정동으로 바꾸면 36%가 숫자동(성내1동·동탄5동)이라 60.8%로 오히려 떨어진다. BE Region3rd 도 이미 접은 이름
(성동구_성수동, 대구_중구_동인동)이라 키 일치가 71.7% → 75.4%, N가 가게 2,826곳 중 2,177곳이 풀린다.
v1(원형 그대로, 09-08 결정)으로 만든 근거 행은 projection_version 이 달라 게이트가 인정하지 않는다.

사용
  DATAOPS_DB_PORT=15434 python scripts/region_resolver.py address-proposals [--limit N] [--dry-run]
  신규 등록 좌표는 localdata_register_worker.py 가 PointResolver 로 남긴다 (CANDIDATE, 관리번호, REGISTRATION)
  DATAOPS_DB_PORT=15434 python scripts/region_resolver.py rejudge --purpose ADDRESS_PROPOSAL   # 기준표 교체 후
키: KAKAO_REST_API_KEY (.env). 값은 어디에도 찍지 않는다.
"""
import argparse
import datetime as dt
import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bjd_reference  # noqa: E402

PROJECTION_VERSION = "app-3field-v2"
SOURCE = "KAKAO_COORD2REGION"
ROOT = Path(__file__).resolve().parents[1]
BE_REGION3RD = ROOT.parent / "core/src/main/java/sweetgirl/croissant/store/enums/Region3rd.java"

# 시도 약칭 — region_recompute.py 의 SIDO_SHORT 와 같은 표(승인된 값과 같은 규칙이어야 한다)
SIDO_SHORT = {"서울특별시": "서울", "부산광역시": "부산", "대구광역시": "대구", "인천광역시": "인천", "광주광역시": "광주",
              "대전광역시": "대전", "울산광역시": "울산", "세종특별자치시": "세종", "경기도": "경기",
              "강원특별자치도": "강원", "강원도": "강원", "충청북도": "충북", "충청남도": "충남", "전북특별자치도": "전북",
              "전라북도": "전북", "전라남도": "전남", "경상북도": "경북", "경상남도": "경남", "제주특별자치도": "제주",
              "전남광주통합특별시": "전남광주", "전남광주": "전남광주"}
GWANGJU_GU = {"동구", "서구", "남구", "북구", "광산구"}


def fingerprint(lon, lat):
    """조회 좌표 식별 — 소수 6자리(약 11cm). ContentHashService 의 좌표 정규화와 같은 자리수."""
    return f"{round(float(lon), 6):.6f},{round(float(lat), 6):.6f}"


_TRAILING_GA = re.compile(r"\d+가$")


def fold_ga(dong):
    """끝의 N가만 접는다 — 성수동2가 → 성수동, 을지로1가 → 을지로. 이름 중간의 '가'(가양동)는 건드리지 않는다."""
    return _TRAILING_GA.sub("", dong) or dong


def short_sido(full, gu):
    short = SIDO_SHORT.get(full, full)
    if short == "전남광주":
        return "광주" if gu in GWANGJU_GU else "전남"
    return short


def load_be_keys(path=BE_REGION3RD):
    if not Path(path).exists():
        return None
    body = Path(path).read_text(encoding="utf-8").split("public enum Region3rd {", 1)[1].split(";", 1)[0]
    return set(re.findall(r"([가-힣0-9_]+)\s*[,\n]", body))


def be_key(keys, sido, gu, dong):
    """BE Region3rd.of(시도, 구, 동) 와 같은 순서로 찾는다 — 시도_구_동 먼저, 없으면 구_동."""
    if keys is None or not gu or not dong:
        return None
    for key in (f"{sido}_{gu}_{dong}", f"{gu}_{dong}"):
        if key in keys:
            return key
    return None


def _norm(text):
    return " ".join((text or "").split())


def resolve(documents, ref, be_keys=None):
    """카카오 coord2regioncode 문서들 → 해소 결과 dict (DB 행의 판정 부분). 네트워크·DB 없음(순수 함수)."""
    b = next((d for d in documents if d.get("region_type") == "B"), None)
    h = next((d for d in documents if d.get("region_type") == "H"), None)
    h_code = (h or {}).get("code")
    h_part = {"code": h_code, "name": _norm((h or {}).get("address_name"))} if h else None
    result = {"region_type": "UNKNOWN", "status": "UNRESOLVED", "reason": None, "bjd_code": None,
              "h_code": h_code, "hierarchy": {"h": h_part} if h_part else None,
              "region_1st": None, "region_2nd": None, "region_3rd": None, "be_region3rd_key": None}

    def unresolved(reason, region_type="UNKNOWN"):
        result.update(reason=reason, region_type=region_type)
        return result

    if b is None:
        # H 만 있으면 법정동의 대체값으로 쓰지 않는다
        return unresolved("NO_B_DOCUMENT", "H" if h else "UNKNOWN")
    code = str(b.get("code") or "")
    if not re.fullmatch(r"[0-9]{10}", code):
        return unresolved("BAD_B_CODE")
    result.update(region_type="B", bjd_code=code)

    entry = ref.get(code)
    if entry is None:
        # 현존 기준표에 없다 — 이력에 폐지로 남아 있으면 개편 전 옛 코드다(예: 강원도 42 → 강원특별자치도 51)
        history = ref.history_status(code)
        if history == "폐지":
            return unresolved("ABOLISHED_CODE", "B")
        if history == "존재":
            return unresolved("CODE_NOT_CURRENT", "B")   # 08-13 파일엔 있었으나 지금 현존 목록엔 없다
        return unresolved("CODE_NOT_IN_REFERENCE", "B")
    if not entry.exists:
        return unresolved("ABOLISHED_CODE", "B")

    sg_code, emd_code = ref.sigungu_code(code), ref.eupmyeondong_code(code)
    sido = ref.root_of(code)
    sigungu, emd = ref.get(sg_code), ref.get(emd_code)
    if sigungu is None and sido is not None and sido.code == sg_code:
        sigungu = sido                            # 세종 — 최상위 행이 곧 시군구 자리다
    for level, e in (("SIDO", sido), ("SIGUNGU", sigungu), ("EUPMYEONDONG", emd)):
        if e is None:
            return unresolved(f"MISSING_{level}", "B")
        if not e.exists:
            return unresolved(f"ABOLISHED_{level}", "B")
    sd_code = sido.code

    # 응답 이름과 기준표 이름이 코드 단위로 같아야 한다 — (구, 동) 이름쌍만의 일치는 해소가 아니다
    kakao_name = _norm(b.get("address_name")) or _norm(" ".join(
        b.get(f"region_{i}depth_name") or "" for i in range(1, 5)))
    if _norm(entry.full_name) != kakao_name:
        return unresolved("HIERARCHY_MISMATCH", "B")

    sigungu_leaf = ref.leaf(sg_code, sd_code) if sg_code != sd_code else ""
    emd_leaf = ref.leaf(emd_code, sg_code)
    ri_leaf = ref.leaf(code, emd_code) if ref.has_ri(code) else None
    result["hierarchy"] = {
        "sido": {"code": sd_code, "name": sido.full_name},
        "sigungu": {"code": sg_code, "name": sigungu_leaf},
        "eupmyeondong": {"code": emd_code, "name": emd_leaf},
        "ri": {"code": code, "name": ri_leaf} if ri_leaf else None,
        "h": h_part,
    }
    if not emd_leaf:
        return unresolved("NO_EUPMYEONDONG", "B")

    region_3rd = fold_ga(emd_leaf)       # 투영 v2 — 원형은 hierarchy.eupmyeondong.name 에 남는다
    gu = sigungu_leaf.split()[0] if sigungu_leaf else region_3rd   # 세종 관행: region_2nd == region_3rd
    region_1st = short_sido(sido.full_name, gu)
    result.update(status="RESOLVED", reason=None, region_1st=region_1st, region_2nd=gu, region_3rd=region_3rd,
                  be_region3rd_key=be_key(be_keys, region_1st, gu, region_3rd))
    return result


def kakao_documents(session, key, lon, lat):
    """→ (documents, error). 4회까지 재시도. 인증 실패는 즉시 멈춘다."""
    last = None
    for attempt in range(4):
        try:
            r = session.get("https://dapi.kakao.com/v2/local/geo/coord2regioncode.json",
                            params={"x": lon, "y": lat}, headers={"Authorization": f"KakaoAK {key}"}, timeout=15)
        except Exception as e:  # noqa: BLE001 — 네트워크 오류는 재시도
            last = str(e)[:120]
            time.sleep(2)
            continue
        if r.status_code == 200:
            return r.json().get("documents", []), None
        if r.status_code in (401, 403):
            raise SystemExit(f"카카오 인증 실패 {r.status_code} — 키 확인")
        if r.status_code == 429 or r.status_code >= 500:
            time.sleep(5 * (attempt + 1))
            last = f"HTTP {r.status_code}"
            continue
        return None, f"HTTP {r.status_code} {r.text[:80]}"
    return None, last


def insert(cur, subject_type, subject_id, purpose, lon, lat, documents, result, observed_at):
    cur.execute("""
        INSERT INTO region_resolution (subject_type, subject_id, purpose, source, query_lon, query_lat,
            location_fingerprint, region_type, status, reason, bjd_code, h_code, hierarchy, raw_response,
            reference_version, projection_version, region_1st, region_2nd, region_3rd, be_region3rd_key, observed_at)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING id""",
        (subject_type, str(subject_id), purpose, SOURCE, lon, lat, fingerprint(lon, lat),
         result["region_type"], result["status"], result["reason"], result["bjd_code"], result["h_code"],
         json.dumps(result["hierarchy"], ensure_ascii=False) if result["hierarchy"] else None,
         json.dumps(documents, ensure_ascii=False), bjd_reference.REFERENCE_VERSION, PROJECTION_VERSION,
         result["region_1st"], result["region_2nd"], result["region_3rd"], result["be_region3rd_key"], observed_at))
    return cur.fetchone()[0]


def latest_evidence(cur, subject_type, subject_id, lon, lat):
    """이 대상·이 좌표·지금 기준표/투영 버전의 최신 근거 행 — 서버 RegionEvidenceFinder 와 같은 키. 없으면 None."""
    cur.execute("""
        SELECT id, status, reason, bjd_code, region_1st, region_2nd, region_3rd, be_region3rd_key
        FROM region_resolution
        WHERE subject_type = %s AND subject_id = %s AND location_fingerprint = %s
          AND reference_version = %s AND projection_version = %s
        ORDER BY created_at DESC, id DESC LIMIT 1""",
        (subject_type, str(subject_id), fingerprint(lon, lat), bjd_reference.REFERENCE_VERSION, PROJECTION_VERSION))
    row = cur.fetchone()
    if row is None:
        return None
    keys = ("id", "status", "reason", "bjd_code", "region_1st", "region_2nd", "region_3rd", "be_region3rd_key")
    return dict(zip(keys, row))


class PointResolver:
    """한 좌표의 근거를 남긴다 — 등록 워커처럼 좌표를 그때그때 얻는 호출자용.

    같은 대상·같은 좌표·지금 버전의 근거가 이미 있으면 카카오를 다시 부르지 않는다. 단, 직전 호출이
    카카오 오류(KAKAO_ERROR)였으면 다시 부른다 — 관측 실패는 판정이 아니다. 좌표가 소수 6자리에서 달라지면
    새 근거다(가게 ID·후보 번호만으로 재사용하지 않는다).
    """

    def __init__(self, conn, key=None, session=None, ref=None, be_keys=None):
        import requests
        self.conn = conn
        self.key = key or read_kakao_key()
        self.session = session or requests.Session()
        self.ref = ref or bjd_reference.load()
        self.be_keys = be_keys if be_keys is not None else load_be_keys()

    def resolve(self, subject_type, subject_id, purpose, lon, lat):
        """→ dict(id, status, reason, bjd_code, region_1st/2nd/3rd, be_region3rd_key, reused). 새 행은 즉시 커밋한다."""
        cur = self.conn.cursor()
        existing = latest_evidence(cur, subject_type, subject_id, lon, lat)
        if existing is not None and existing["reason"] != "KAKAO_ERROR":
            return {**existing, "reused": True}
        documents, error = kakao_documents(self.session, self.key, lon, lat)
        observed_at = dt.datetime.now()
        if documents is None:
            result = {**resolve([], self.ref, self.be_keys), "reason": "KAKAO_ERROR"}
            documents = {"error": error}
        else:
            result = resolve(documents, self.ref, self.be_keys)
        row_id = insert(cur, subject_type, subject_id, purpose, lon, lat, documents, result, observed_at)
        self.conn.commit()   # 근거는 등록 결과와 무관하게 남는다(append-only)
        keys = ("status", "reason", "bjd_code", "region_1st", "region_2nd", "region_3rd", "be_region3rd_key")
        return {"id": row_id, **{k: result[k] for k in keys}, "reused": False}


def read_kakao_key():
    env = ROOT / ".env"
    for line in env.read_text(encoding="utf-8").splitlines():
        if line.startswith("KAKAO_REST_API_KEY="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("KAKAO_REST_API_KEY 가 .env 에 없다")


def address_proposals(args):
    """보류 중인 store.address 제안의 새 좌표를 해소한다 — 승인 게이트가 이 근거를 좌표로 찾는다."""
    import requests
    import local_db
    ref, be_keys = bjd_reference.load(), load_be_keys()
    conn = local_db.connect()
    cur = conn.cursor()
    cur.execute("""SELECT id, store_id, new_value FROM change_proposal
                   WHERE target = 'store.address' AND status = 'PENDING'
                     AND jsonb_typeof(new_value->'lon') = 'number' AND jsonb_typeof(new_value->'lat') = 'number'
                   ORDER BY id""")
    todo = cur.fetchall()
    if args.limit:
        todo = todo[:args.limit]
    key, session = read_kakao_key(), requests.Session()
    counts = {}
    for i, (pid, store_id, new_value) in enumerate(todo, 1):
        lon, lat = float(new_value["lon"]), float(new_value["lat"])
        documents, error = kakao_documents(session, key, lon, lat)
        observed_at = dt.datetime.now()
        if documents is None:
            result = {**resolve([], ref, be_keys), "reason": "KAKAO_ERROR"}
            documents = {"error": error}
        else:
            result = resolve(documents, ref, be_keys)
        label = result["status"] if result["status"] == "RESOLVED" else f"UNRESOLVED:{result['reason']}"
        counts[label] = counts.get(label, 0) + 1
        if not args.dry_run:
            insert(cur, "STORE", store_id, "ADDRESS_PROPOSAL", lon, lat, documents, result, observed_at)
        if i % 20 == 0:
            print(f"  {i}/{len(todo)}", flush=True)
        time.sleep(args.sleep)
    if args.dry_run:
        conn.rollback()
    else:
        conn.commit()
    conn.close()
    print(f"store.address PENDING {len(todo)}건 해소 ({'dry-run' if args.dry_run else '기록'})")
    for k, v in sorted(counts.items(), key=lambda x: -x[1]):
        print(f"  {v:>4}  {k}")


def rejudge(args):
    """저장된 카카오 원 응답을 지금 기준표·투영 버전으로 다시 판정해 새 행을 덧붙인다 — 카카오를 다시 부르지 않는다.

    같은 관측을 새 기준으로 읽는 것이므로 observed_at 은 원래 호출 시각 그대로다. 옛 행은 고치지 않는다(append-only).
    이미 지금 버전으로 판정된 (대상, 좌표) 는 건너뛴다.
    """
    import local_db
    ref, be_keys = bjd_reference.load(), load_be_keys()
    conn = local_db.connect()
    cur = conn.cursor()
    cur.execute("""
        SELECT DISTINCT ON (subject_type, subject_id, location_fingerprint)
               subject_type, subject_id, purpose, query_lon, query_lat, raw_response, observed_at
        FROM region_resolution r
        WHERE purpose = %s AND jsonb_typeof(raw_response) = 'array'
          AND NOT EXISTS (SELECT 1 FROM region_resolution n
                          WHERE n.subject_type = r.subject_type AND n.subject_id = r.subject_id
                            AND n.location_fingerprint = r.location_fingerprint
                            AND n.reference_version = %s AND n.projection_version = %s)
        ORDER BY subject_type, subject_id, location_fingerprint, created_at DESC""",
        (args.purpose, bjd_reference.REFERENCE_VERSION, PROJECTION_VERSION))
    todo = cur.fetchall()
    counts = {}
    for subject_type, subject_id, purpose, lon, lat, documents, observed_at in todo:
        result = resolve(documents, ref, be_keys)
        label = result["status"] if result["status"] == "RESOLVED" else f"UNRESOLVED:{result['reason']}"
        counts[label] = counts.get(label, 0) + 1
        if not args.dry_run:
            insert(cur, subject_type, subject_id, purpose, lon, lat, documents, result, observed_at)
    conn.rollback() if args.dry_run else conn.commit()
    conn.close()
    print(f"{args.purpose} {len(todo)}건 재판정 → {bjd_reference.REFERENCE_VERSION} ({'dry-run' if args.dry_run else '기록'})")
    for k, v in sorted(counts.items(), key=lambda x: -x[1]):
        print(f"  {v:>4}  {k}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("address-proposals")
    a.add_argument("--limit", type=int)
    a.add_argument("--sleep", type=float, default=0.15)
    a.add_argument("--dry-run", action="store_true")
    a.set_defaults(func=address_proposals)
    j = sub.add_parser("rejudge")
    j.add_argument("--purpose", required=True)
    j.add_argument("--dry-run", action="store_true")
    j.set_defaults(func=rejudge)
    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
