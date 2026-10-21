#!/usr/bin/env python3
"""공공데이터포털(data.go.kr) 인허가 조회서비스 수집기 — LOCALDATA 파일럿.

3개 업종을 JSONL로 내려받는다 (matching/localdata_raw/{dataset}.jsonl):
  bakeries            제과점영업   — 전량 (data.go.kr 15155252)
  rest_cafes          휴게음식점   — 전량, 단 totalCount가 일일 쿼터를 넘으면 키워드 모드 (15154921)
  general_restaurants 일반음식점   — 상호 키워드 필터 모드 (15154916, 전량은 ~2만 콜이라 쿼터 초과)

- 인증키: .env의 DATA_GO_KR_SERVICE_KEY (Decoding 키 권장 — '%'가 들어 있으면 자동 unquote)
- 페이지당 최대 100건 (numOfRows 상한), 병렬 8워커 ≈ 6~7 req/s (data.go.kr TPS 제한 30 내)
- 재개 가능: matching/localdata_raw/{dataset}.state.json에 완료 페이지 집합 저장
  (구버전 순차 수집의 watermark("page") 상태도 읽어서 이어받는다)
- 쿼터 소진(에러코드 22) 감지 시 상태 저장 후 종료 — 다음 날 재실행하면 이어받는다
"""
import json
import sys
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "matching" / "localdata_raw"
BASE = "https://apis.data.go.kr/1741000"
PAGE_SIZE = 100
SLEEP = 0.1  # 워커당 콜 간 간격
WORKERS = 8
# 오프셋 페이지네이션이라 뒤로 갈수록 서버가 느려진다 (2026-09-25 실측: 1페이지 2.6초 · 20,000페이지 15.6초).
# 30초로는 깊은 구간에서 줄줄이 타임아웃 난다 — 일반음식점 230만 건 재개분이 7시간에 100페이지밖에 못 갔다.
READ_TIMEOUT = 120

_tls = threading.local()


def tls_session() -> requests.Session:
    if not hasattr(_tls, "session"):
        _tls.session = requests.Session()
    return _tls.session

# 일반음식점(그리고 필요 시 휴게음식점)용 상호 키워드 — 카페형 빵집 보조 포착
NAME_KEYWORDS = [
    "베이커리", "빵", "제과", "베이글", "도넛", "도너츠", "케이크", "케익",
    "마카롱", "타르트", "크로플", "크루아상", "크라상", "와플", "스콘",
    "쿠키", "디저트", "브런치", "카스테라", "카스텔라", "꽈배기", "샌드위치",
    # 보강 (08-11): 키워드 부재로 놓친 실사례(시월호두 — 호두과자집이 일반음식점 '기타' 인허가)
    "호두", "파이", "마들렌", "휘낭시에", "츄러스", "약과", "카눌레",
    "젤라또", "젤라토", "크레페", "티라미수", "호떡", "붕어빵", "브라우니",
    "베이킹",  # 무드베이킹 사례 — '베이커리'만으론 '베이킹' 상호를 못 잡는다
]

DATASETS = {
    "bakeries": {"mode": "full"},
    "rest_cafes": {"mode": "full_or_keyword"},  # totalCount 보고 결정
    # 2026-09-24 keyword → full. 키워드 수집은 상호에 빵집 말이 없는 가게를 구조적으로 못 잡는다 —
    # 역방향 탐색이 찾은 가게 6,160곳이 "인허가 못 찾음" 으로 막혔는데, 지번주소로 재시도해도 0.2% 만
    # 붙었다(= 주소 표기 문제가 아니라 애초에 원본에 없다). 전량은 230만 건 ≈ 23,000콜 ≈ 1.2일이고
    # pages_done 상태로 이어받는다. full_or_keyword 로 두면 쿼터 초과 판정에 걸려 키워드로 강등된다.
    "general_restaurants": {"mode": "full"},
    # 포장 판매형 빵집의 주 인허가 업종 (15155245) — NOT_FOUND 원인 분석에서 추가
    "instant_food_processors": {"mode": "full_or_keyword"},
}

# 하루 쿼터(개발계정 10,000콜/API). 전량 수집이 이 콜 수를 넘으면 키워드 모드로 강등.
DAILY_CALL_BUDGET = 9500


_KEYS: list[str] = []
_key_idx = 0
_key_lock = threading.Lock()


def load_service_key() -> str:
    """`.env`의 DATA_GO_KR_SERVICE_KEY(+_2, _3…) 전부를 키링에 적재하고 1번 키를 반환.
    쿼터 소진 시 call()이 rotate_key()로 다음 계정 키로 자동 전환한다."""
    global _KEYS
    keys = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("DATA_GO_KR_SERVICE_KEY"):
            name, val = line.split("=", 1)
            key = val.strip().strip('"')
            if "%" in key:  # Encoding 키가 들어온 경우 원복
                key = urllib.parse.unquote(key)
            if key:
                keys[name.strip()] = key
    _KEYS = [keys[k] for k in sorted(keys)]
    if not _KEYS:
        sys.exit("DATA_GO_KR_SERVICE_KEY가 .env에 없습니다")
    return _KEYS[0]


def current_key(fallback: str) -> str:
    return _KEYS[_key_idx] if _KEYS else fallback


def rotate_key(used: str) -> bool:
    """쿼터 소진된 키(used)에서 다음 키로 전환. 이미 다른 워커가 전환했으면 그대로 성공."""
    global _key_idx
    with _key_lock:
        if _KEYS and _KEYS[_key_idx] != used:
            return True  # 다른 스레드가 먼저 전환함
        if _key_idx + 1 < len(_KEYS):
            _key_idx += 1
            print(f"  ! 쿼터 소진 — 예비 인증키 #{_key_idx + 1}로 전환", flush=True)
            return True
        return False


class QuotaExceeded(Exception):
    pass


def call(session: requests.Session, dataset: str, key: str, page: int, cond: dict | None = None) -> dict:
    for attempt in range(5):
        use_key = current_key(key)
        params = {
            "serviceKey": use_key,
            "pageNo": str(page),
            "numOfRows": str(PAGE_SIZE),
            "returnType": "json",
        }
        if cond:
            params.update(cond)
        try:
            resp = session.get(f"{BASE}/{dataset}/info", params=params, timeout=(10, READ_TIMEOUT))
            text = resp.text
            if resp.status_code >= 500:
                raise requests.RequestException(f"HTTP {resp.status_code}")
            # 게이트웨이 에러 (쿼터 초과=22, 미등록 키=30, 미신청=20 등) — XML 또는 JSON으로 온다
            if "OpenAPI_ServiceResponse" in text[:200] or text.lstrip().startswith("<"):
                if "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS" in text or '"returnReasonCode": "22"' in text:
                    if rotate_key(use_key):  # 예비 키로 전환 후 같은 페이지 재시도
                        continue
                    raise QuotaExceeded(dataset)
                raise RuntimeError(f"게이트웨이 응답: {text[:300]}")
            data = resp.json()
            header = data["response"]["header"]
            if header.get("resultCode") not in ("00", "0"):
                raise RuntimeError(f"resultCode={header}")
            return data["response"]["body"]
        except (requests.RequestException, json.JSONDecodeError) as e:
            wait = 2 ** attempt
            print(f"  재시도 {attempt + 1}/5 ({e}) — {wait}s 대기", flush=True)
            time.sleep(wait)
    raise RuntimeError(f"{dataset} p{page}: 5회 재시도 실패")


def items_of(body: dict) -> list[dict]:
    items = body.get("items") or {}
    if isinstance(items, list):  # 빈 결과가 []로 오는 경우
        return []
    item = items.get("item") or []
    return item if isinstance(item, list) else [item]


def fetch_pages(session, dataset, key, out, state, cond=None, label="full"):
    """한 (dataset, cond) 조합을 병렬 페이지 수집. state[label].pages_done에 완료 집합 저장."""
    st = state.setdefault(label, {"page": 0, "done": False, "total": None})
    if st["done"]:
        return 0
    done_pages = set(st.get("pages_done") or [])
    if st.get("page", 0) > 1:  # 구버전 순차 watermark 흡수
        done_pages |= set(range(1, st["page"]))
    body = call(session, dataset, key, 1, cond)
    total = int(body.get("totalCount", 0))
    st["total"] = total
    pages = (total + PAGE_SIZE - 1) // PAGE_SIZE
    todo = [p for p in range(1, pages + 1) if p not in done_pages]
    n = 0
    lock = threading.Lock()
    quota_hit = threading.Event()

    def fetch_one(page: int):
        if quota_hit.is_set():
            return page, None
        try:
            rows = items_of(call(tls_session(), dataset, key, page, cond))
        except RuntimeError as e:
            # 5회 재시도 실패(대량 수집 후반 서버 불안정 실측) — 전체 런을 죽이지 않고
            # 해당 페이지만 미완으로 남긴다. pages_done 미기록이라 재실행이 자동 재시도.
            print(f"  ! {dataset} p{page} 실패 격리: {str(e)[:80]}", flush=True)
            return page, None
        time.sleep(SLEEP)
        return page, rows

    completed = 0
    try:
        with ThreadPoolExecutor(max_workers=WORKERS) as pool:
            futures = [pool.submit(fetch_one, p) for p in todo]
            for fut in as_completed(futures):
                try:
                    page, rows = fut.result()
                except QuotaExceeded:
                    quota_hit.set()
                    continue
                if rows is None:
                    continue
                with lock:
                    for r in rows:
                        out.write(json.dumps(r, ensure_ascii=False) + "\n")
                    n += len(rows)
                    done_pages.add(page)
                    completed += 1
                    if completed % 100 == 0:
                        out.flush()
                        st["pages_done"] = sorted(done_pages)
                        save_state(dataset, state)
                        print(f"  [{dataset}/{label}] {len(done_pages)}/{pages} 페이지 ({n}건+)", flush=True)
    finally:
        out.flush()
        st["pages_done"] = sorted(done_pages)
        save_state(dataset, state)
    if quota_hit.is_set():
        raise QuotaExceeded(dataset)
    st["done"] = len(done_pages) >= pages
    st["pages_done"] = sorted(done_pages)
    save_state(dataset, state)
    return n


def state_path(dataset: str) -> Path:
    return RAW_DIR / f"{dataset}.state.json"


def save_state(dataset: str, state: dict):
    state_path(dataset).write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")


def load_state(dataset: str) -> dict:
    p = state_path(dataset)
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


def run_delta(month_tag: str):
    """증분 재수집 — 서버측 GTE cond 는 값이 무시됨(2026-08-28 실측: 어떤 날짜든 동일
    totalCount). 따라서 델타는 **재수집 + 클라이언트 diff** 로 얻는다:
      - bakeries·rest_cafes·instant_food_processors: 전량 재수집 (~4.5천 콜)
      - general_restaurants: 키워드 재수집 (원 수집과 동일 한계 — 비키워드 카페형은
        사용자 제보(MANUAL)·주소 스윕이 보완)
    출력: delta/{ds}_pull.jsonl. diff 는 localdata_delta_diff.py 가 수행.
    month_tag(예: 202608)별 재개 상태를 가져 월간 재실행이 이어받기 된다.
    """
    key = load_service_key()
    session = tls_session()
    delta_dir = RAW_DIR / "delta"
    delta_dir.mkdir(exist_ok=True)
    for dataset, cfg in DATASETS.items():
        # 상태는 기본 {dataset}.state.json 하나에 delta:{month_tag} 라벨로 산다 —
        # fetch_pages 내부 주기 저장과 같은 파일이어야 크래시 후 재개가 이어진다.
        state = load_state(dataset)
        out_path = delta_dir / f"{dataset}_pull.jsonl"
        keyword_mode = dataset == "general_restaurants"
        fresh = not any(k == f"delta:{month_tag}" or k.startswith(f"delta:{month_tag}:")
                        for k in state)
        try:
            with open(out_path, "w" if fresh else "a", encoding="utf-8") as out:
                if not keyword_mode:
                    n = fetch_pages(session, dataset, key, out, state, label=f"delta:{month_tag}")
                    print(f"[{dataset}] 델타 전량 재수집 +{n}건", flush=True)
                else:
                    for kw in NAME_KEYWORDS:
                        n = fetch_pages(session, dataset, key, out, state,
                                        cond={"cond[BPLC_NM::LIKE]": kw},
                                        label=f"delta:{month_tag}:kw:{kw}")
                    print(f"[{dataset}] 델타 키워드 재수집 완료", flush=True)
        except QuotaExceeded:
            print(f"[{dataset}] 쿼터 소진 — 재실행 시 이어받음", flush=True)
            save_state(dataset, state)
            return
        save_state(dataset, state)
    print("델타 재수집 종료", flush=True)


def main():
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    key = load_service_key()
    session = requests.Session()
    if len(sys.argv) > 2 and sys.argv[1] == "--delta":
        run_delta(sys.argv[2])  # 인자: 월 태그 (예: 202608)
        return
    only = sys.argv[1] if len(sys.argv) > 1 else None

    for dataset, cfg in DATASETS.items():
        if only and dataset != only:
            continue
        state = load_state(dataset)
        out_path = RAW_DIR / f"{dataset}.jsonl"
        mode = cfg["mode"]
        try:
            with open(out_path, "a", encoding="utf-8") as out:
                if mode in ("full", "full_or_keyword"):
                    body = call(session, dataset, key, 1)
                    total = int(body.get("totalCount", 0))
                    calls_needed = (total + PAGE_SIZE - 1) // PAGE_SIZE
                    print(f"[{dataset}] totalCount={total:,} (~{calls_needed:,}콜)", flush=True)
                    if mode == "full_or_keyword" and calls_needed > DAILY_CALL_BUDGET:
                        print(f"[{dataset}] 쿼터 초과 예상 → 키워드 모드로 전환", flush=True)
                        mode = "keyword"
                    else:
                        n = fetch_pages(session, dataset, key, out, state, label="full")
                        print(f"[{dataset}] 전량 수집 완료 +{n}건", flush=True)
                if mode == "keyword":
                    for kw in NAME_KEYWORDS:
                        n = fetch_pages(
                            session, dataset, key, out, state,
                            cond={"cond[BPLC_NM::LIKE]": kw}, label=f"kw:{kw}",
                        )
                        if n:
                            print(f"[{dataset}] 키워드 '{kw}' +{n}건", flush=True)
                    print(f"[{dataset}] 키워드 수집 완료", flush=True)
        except QuotaExceeded:
            print(f"[{dataset}] 일일 쿼터 소진 — 상태 저장됨, 내일 재실행 시 이어받음", flush=True)
            save_state(dataset, state)
            continue

    print("수집 종료")


if __name__ == "__main__":
    main()
