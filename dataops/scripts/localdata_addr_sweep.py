#!/usr/bin/env python3
"""NOT_FOUND 가게 주소 타깃 스윕 — 일반음식점 API를 도로명주소로 직접 조회.

일반음식점은 전량 수집이 쿼터 초과라 키워드로만 받았는데, 키워드가 안 걸리는 상호
(실사례: '시월호두')는 레코드 자체가 없어 NOT_FOUND가 된다. 이 스크립트는
matching/localdata_mapping.tsv의 NOT_FOUND 가게들의 (도로명, 건물번호)로
cond[ROAD_NM_ADDR::LIKE] 조회를 때려 해당 건물의 인허가 레코드를 수집한다.

- 결과는 general_restaurants.jsonl에 append (매칭 로더가 MNG_NO로 dedup)
- 재개 가능: addr_sweep.state.json에 완료 주소키 저장, 쿼터 소진 시 저장 후 종료
- 페이지네이션: 건물당 최대 3페이지(300건) — 초과는 로그만 (대형 몰 등)
"""
import csv
import json
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from localdata_fetch import QuotaExceeded, call, load_service_key, tls_session
from localdata_match import road_key

ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "matching" / "localdata_raw"
# 사용법: python localdata_addr_sweep.py [dataset] [판정필터]
#   판정필터: 쉼표 구분 (기본 "NOT_FOUND,REVIEW,AUTO_CLOSED" — AUTO_CLOSED는 폐업·전화불일치 AUTO)
#   예: python localdata_addr_sweep.py instant_food_processors NOT_FOUND
DATASET = sys.argv[1] if len(sys.argv) > 1 else "general_restaurants"
JUDGMENT_FILTER = set((sys.argv[2] if len(sys.argv) > 2 else "NOT_FOUND,REVIEW,AUTO_CLOSED").split(","))
OUT_PATH = RAW_DIR / f"{DATASET}.jsonl"
# 기존 general_restaurants 상태 파일명 호환 유지
STATE_PATH = RAW_DIR / ("addr_sweep.state.json" if DATASET == "general_restaurants"
                        else f"addr_sweep_{DATASET}.state.json")
WORKERS = 8
MAX_PAGES = 3


def load_targets() -> list[tuple[str, str]]:
    """스윕 대상 = 일반음식점 레코드 부재가 의심되는 모든 가게의 주소.

    - NOT_FOUND: 후보 자체가 없음
    - REVIEW: 약한 후보뿐 — 진짜 레코드가 미수집이면 이웃 가게가 차선으로 붙는다
      (실사례: 무드베이킹↔케익타운베이커리 0.308, 스윗레시피↔베스타피씨방 0.364)
    - AUTO인데 폐업·전화불일치: 진짜 영업 레코드가 미수집이면 선행 폐업 레코드가 남는다
    """
    nf_ids = set()
    with open(ROOT / "matching" / "localdata_mapping.tsv", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            if row["judgment"] in JUDGMENT_FILTER:
                nf_ids.add(row["store_id"])
            elif ("AUTO_CLOSED" in JUDGMENT_FILTER and row["judgment"] == "AUTO"
                  and "폐업" in row["license_status"] and row["phone_match"] != "1"):
                nf_ids.add(row["store_id"])
    keys = {}
    with open(RAW_DIR / "stores_active.tsv", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            if row["id"] in nf_ids:
                rk = road_key(row["address"])
                if rk:
                    keys[rk] = True
    return list(keys.keys())


def main():
    key = load_service_key()
    targets = load_targets()
    state = json.loads(STATE_PATH.read_text(encoding="utf-8")) if STATE_PATH.exists() else {"done": []}
    done = set(map(tuple, state["done"]))
    todo = [t for t in targets if t not in done]
    print(f"NOT_FOUND 주소키 {len(targets):,} / 완료 {len(done):,} / 잔여 {len(todo):,}")

    lock = threading.Lock()
    quota_hit = threading.Event()
    n_records = 0

    def sweep_one(rk):
        if quota_hit.is_set():
            return rk, None
        road, bldg = rk
        cond = {"cond[ROAD_NM_ADDR::LIKE]": f"{road} {bldg}"}
        rows = []
        page = 1
        while page <= MAX_PAGES:
            body = call(tls_session(), DATASET, key, page, cond)
            items = body.get("items") or {}
            batch = items.get("item") or [] if isinstance(items, dict) else []
            if isinstance(batch, dict):
                batch = [batch]
            rows.extend(batch)
            total = int(body.get("totalCount", 0))
            if page * 100 >= total:
                break
            page += 1
        time.sleep(0.1)
        return rk, rows

    with open(OUT_PATH, "a", encoding="utf-8") as out:
        with ThreadPoolExecutor(max_workers=WORKERS) as pool:
            futures = [pool.submit(sweep_one, rk) for rk in todo]
            completed = 0
            try:
                for fut in as_completed(futures):
                    try:
                        rk, rows = fut.result()
                    except QuotaExceeded:
                        quota_hit.set()
                        continue
                    if rows is None:
                        continue
                    with lock:
                        for r in rows:
                            out.write(json.dumps(r, ensure_ascii=False) + "\n")
                        n_records += len(rows)
                        done.add(rk)
                        completed += 1
                        if completed % 200 == 0:
                            out.flush()
                            state["done"] = sorted(done)
                            STATE_PATH.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")
                            print(f"  {len(done):,}/{len(targets):,} 주소 ({n_records:,}건 수집)", flush=True)
            finally:
                out.flush()
                state["done"] = sorted(done)
                STATE_PATH.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")

    if quota_hit.is_set():
        print(f"쿼터 소진 — {len(done):,}/{len(targets):,} 완료, 내일 재실행 시 이어받음")
    else:
        print(f"스윕 완료 — 주소 {len(done):,}곳, 레코드 {n_records:,}건 추가")


if __name__ == "__main__":
    main()
