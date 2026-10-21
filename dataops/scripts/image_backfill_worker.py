# -*- coding: utf-8 -*-
"""이미지 백필 워커 — 스냅샷의 ldb-phinf 원본 URL 을 본 앱 from-url API 로 공급한다.

경로 (본 앱이 전부 처리): POST {SERVER}/admin/images/{stores|menus}/{id}/from-url [{"url": ...}]
  → 서버가 download → S3 → DB 행 생성 → 람다 리사이즈 → 콜백으로 DONE 전환.
  멱등: UNIQUE(menu_id|store_id, source_url) — 재실행 시 중복 생성 없음.

정책 (docs/naver-image-pipeline.md §3): ldb-phinf.pstatic.net 만 통과 (점주 등록 사진).
가게 사진은 PlaceDetailTopPhotoItem:business_*(mediaSource=business) originalUrl — 방문자 사진 구조 차단.

사용:
  python image_backfill_worker.py --pilot 20          # 파일럿 20곳 (기본 dry-run 아님, 실호출)
  python image_backfill_worker.py --all               # 전량
  python image_backfill_worker.py --all --skip 100    # 이어서
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from urllib.parse import urlparse

# 본 앱(WAS) 주소 — v2 로 옮기면 WAS_SERVER_URL 로 바꾼다
SERVER = os.environ.get("WAS_SERVER_URL", "https://bbang-ggut.cloud").rstrip("/")
# 서버의 어드민 API 는 열쇠(X-Admin-Key)를 요구한다. 서버의 ADMIN_API_KEY 와 같은 값을 환경변수로 준다.
# 없으면 붙이지 않는다 — 열쇠를 코드나 명령줄 인자에 적지 않는다.
ADMIN_HEADERS = {"X-Admin-Key": os.environ["ADMIN_API_KEY"].strip()} if os.environ.get("ADMIN_API_KEY", "").strip() else {}
ALLOWED_HOST = "ldb-phinf.pstatic.net"
MENU_IMAGE_CAP = 2      # 메뉴당 최대 장수
STORE_IMAGE_CAP = 5     # 가게당 최대 장수 (본 앱 크롤러와 동일)
CALL_GAP_SECONDS = 1.0  # 서버 다운로드 부하 배려 — 가게 간 간격
RESIZE_SUFFIX = "?type=w1500"  # 원본이 10MB 를 넘어 서버가 거절할 때만 붙인다 (장변 1500px)

def read_env(key):
    # 워크트리 .env(현행 자격) — RDS 재생성 후 원본 폴더 .env 는 구버전 (2026-08-25)
    import os
    env_path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".env")
    for line in open(env_path, encoding="utf-8"):
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()

PU, PP = read_env("PROD_DB_USERNAME"), read_env("PROD_DB_PASSWORD")

sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
import local_db  # noqa: E402 — dataops DB 는 어디에 있든(로컬 도커·원격) 환경변수로 찾는다

PROD_PORT = int(__import__("os").environ.get("PROD_DB_PORT", "15432"))


def _rows_to_text(rows):
    """psql -At 출력과 같은 모양 — 호출부가 문자열을 줄·탭으로 나눠 쓴다"""
    return "\n".join("\t".join("" if v is None else ("t" if v is True else "f" if v is False else str(v)) for v in r)
                     for r in rows)


def dataops_sql(sql):
    """dataops DB 조회 — 예전에는 로컬 컨테이너(bbanggut-dataops-db) 안의 psql 을 불렀다. DB 가 원격(오라클)으로
    옮겨 가면 그 컨테이너가 없으므로 psycopg2 로 직접 붙는다 (2026-09-27)"""
    conn = local_db.connect()
    try:
        cur = conn.cursor()
        cur.execute(sql)
        return _rows_to_text(cur.fetchall())
    finally:
        conn.close()


def prod_sql(sql, tries=6):
    """운영 조회(읽기 전용) — 터널(127.0.0.1:PROD_DB_PORT)로 직접 붙는다. 터널이 몇 시간짜리 작업 중에 끊기는 일이 있어
    (2026-09-09 실측) 연결 계열 오류만 재시도한다. 한 번 실패에 프로세스가 죽으면 긴 작업이 통째로 날아간다."""
    import psycopg2
    last = ""
    for attempt in range(tries):
        try:
            conn = psycopg2.connect(host="127.0.0.1", port=PROD_PORT, user=PU, password=PP, dbname="soboro",
                                    sslmode="require", connect_timeout=10,
                                    options="-c default_transaction_read_only=on", application_name="image_backfill")
            try:
                cur = conn.cursor()
                cur.execute(sql)
                return _rows_to_text(cur.fetchall())
            finally:
                conn.close()
        except psycopg2.OperationalError as e:
            last = str(e).strip()[:200]
            wait = min(60, 5 * (attempt + 1))
            print(f"  [prod] 조회 실패({attempt + 1}/{tries}) {wait}s 대기 — {last[:90]}", flush=True)
            time.sleep(wait)
    raise SystemExit("prod SQL 실패: " + last)


def normalize(name):
    return "".join(ch for ch in (name or "").lower() if ch.isalnum())

MAX_SOURCE_URL = 500   # 본 앱 menu_image_file.source_url / store_image_file.source_url 이 VARCHAR(500)


def shorten(url):
    """500자를 넘는 URL 의 파일명만 짧게 바꾼다 (2026-09-09).

    네이버는 SEO 로 파일명에 한글 키워드를 잔뜩 붙이는데(EUC-KR 퍼센트 인코딩이라 3배로 부풀어 551자),
    CDN 은 마지막 경로 조각을 무시한다 — .../<id>_JPEG/x.jpg 가 원본과 같은 바이트를 준다(실측).
    본 앱은 컬럼 길이 초과를 DB_FAILED "duplicate source_url" 이라는 엉뚱한 사유로 돌려주므로
    여기서 줄이지 않으면 원인을 알 수 없는 실패로만 쌓인다. 결정적 규칙이라 재실행해도 같은 주소가 나온다."""
    if len(url) <= MAX_SOURCE_URL:
        return url
    head, _, last = url.rpartition("/")
    ext = last.rsplit(".", 1)[-1].lower() if "." in last else "jpg"
    short = f"{head}/i.{ext}"
    return short if len(short) <= MAX_SOURCE_URL else None


def allowed(url):
    """화이트리스트 호스트 + 서버가 처리할 수 있는 형식만.

    GIF 는 본 앱이 처리하지 않는다(팀 확인, 2026-09-09). 서버는 이걸 DB_FAILED "duplicate source_url"
    이라는 엉뚱한 사유로 돌려주므로, 여기서 거르지 않으면 매번 헛호출하고 로그도 오해하게 만든다.
    같은 메뉴에 jpg 를 보내면 SUCCESS 라는 대조군으로 확인했다."""
    try:
        u = urlparse(url)
        return u.hostname == ALLOWED_HOST and not u.path.lower().endswith(".gif")
    except Exception:
        return False

def _call(domain, target_id, urls):
    payload = json.dumps([{"url": u} for u in urls]).encode()
    req = urllib.request.Request(f"{SERVER}/admin/images/{domain}/{target_id}/from-url",
                                 data=payload, method="POST",
                                 headers={"Content-Type": "application/json", **ADMIN_HEADERS})
    with urllib.request.urlopen(req, timeout=180) as resp:
        return json.loads(resp.read()).get("data") or {}


def post_from_urls(domain, target_id, urls):
    """from-url 호출 — (성공 장수, 실패 사유 목록)

    'too large' 는 리사이즈 URL 로 한 번 더 시도한다. 남아 있는 백필 대상은 사실상 전부 이 경우다 —
    작은 사진은 지난 백필에서 이미 들어갔고, 늘 실패하던 큰 사진만 남았다(2026-09-09 실측: 후보 5/5 가 10MB 초과).
    네이버 CDN 은 원본 경로에 ?type=w1500 을 붙이면 장변 1500px 로 준다 (14MB → 0.3~4.8MB).
    원본 URL 로 먼저 시도하는 이유는 멱등 키가 (대상, source_url) 이라, 되는 것은 원본 주소로 남기기 위해서다."""
    data = _call(domain, target_id, urls)
    results = data.get("results") or []
    ok = int(data.get("successCount", 0))
    # SKIPPED 는 같은 source_url 재전송(멱등 방어) — 실패가 아니라 '할 일 없음'이라 세지 않는다 (2026-09-09 실측)
    fails = [r for r in results if r.get("status") not in ("SUCCESS", "SKIPPED")]
    too_big = [r["url"] for r in fails if "too large" in (r.get("reason") or "")]
    if too_big:
        retry = _call(domain, target_id, [u + RESIZE_SUFFIX for u in too_big])
        ok += int(retry.get("successCount", 0))
        fails = [r for r in fails if r.get("url") not in set(too_big)]
        fails += [r for r in (retry.get("results") or []) if r.get("status") not in ("SUCCESS", "SKIPPED")]
    return ok, [f"{r.get('status')}:{(r.get('reason') or '')[:40]}" for r in fails]

def extract_store(store_id):
    """최신 스냅샷에서 (메뉴명→이미지URL들, 가게 business 사진 URL들) 추출 — 화이트리스트 적용"""
    out = dataops_sql(
        "SELECT payload::text FROM place_snapshot WHERE store_id = %d AND source='NAVER' "
        "ORDER BY fetched_at DESC LIMIT 1;" % store_id)
    if not out.strip():
        return {}, []
    payload = json.loads(out)
    menu_images, store_photos = {}, []
    for key, entity in payload.items():
        if not isinstance(entity, dict):
            continue
        # 메뉴: 옛 구조 'Menu:*'(images = 문자열 배열)와 2026-09 개편 뒤 PlaceMenuItem(images = [{url, width, height}]).
        # 개편 뒤 스냅샷에는 'Menu:*' 가 아예 없어, 9/22 이후 등록 가게는 메뉴 사진이 한 장도 추출되지 않았다 (2026-09-27)
        if key.startswith("Menu:") or entity.get("__typename") == "PlaceMenuItem":
            name = entity.get("name")
            images = [im.get("url") if isinstance(im, dict) else im for im in (entity.get("images") or [])]
            good = [su for su in (shorten(u) for u in images
                                  if isinstance(u, str) and allowed(u)) if su]
            if name and good:
                menu_images.setdefault(normalize(name), []).extend(good)
                # 같은 이름의 Menu 엔티티가 여러 개면 URL 이 겹친다. 한 배치에 같은 주소를 두 번 보내면
                # 뒤엣것이 UNIQUE(menu_id, source_url) 에 걸려 DB_FAILED 로 돌아온다 (2026-09-09 실측).
                seen, uniq = set(), []
                for u in menu_images[normalize(name)]:
                    if u not in seen:
                        seen.add(u); uniq.append(u)
                menu_images[normalize(name)] = uniq
        # 키 형식이 2026-09-04 부터 'PlaceDetailTopPhotoItem:business_1' → ':<placeId>_business_1' 로 바뀌었다.
        # 키 문자열 대신 mediaSource 로 거른다 (방문자·블로그 사진은 여기서 탈락) — 다음 형식 변경에도 안전.
        elif key.startswith("PlaceDetailTopPhotoItem:"):
            if entity.get("mediaFormat") == "image" and entity.get("mediaSource") == "business":
                url = entity.get("originalUrl")
                if isinstance(url, str) and allowed(url):
                    su = shorten(url)
                    if su and su not in store_photos:
                        store_photos.append(su)
    return menu_images, store_photos

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pilot", type=int, default=0, help="파일럿 N곳만")
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--skip", type=int, default=0, help="앞에서 N곳 건너뛰기 (이어하기)")
    parser.add_argument("--store-ids-file", default=None, help="이 가게들만 (JSON 배열 또는 {\"store_ids\": [...]})")
    parser.add_argument("--dry-run", action="store_true", help="본 앱을 호출하지 않고 보낼 사진 수만 센다")
    args = parser.parse_args()
    if not args.pilot and not args.all:
        parser.error("--pilot N 또는 --all 필요")
    scope = None
    if args.store_ids_file:
        data = json.load(open(args.store_ids_file, encoding="utf-8"))
        scope = set(int(x) for x in (data["store_ids"] if isinstance(data, dict) else data))

    # 대상: 이미지 없는 활성 메뉴를 가진 가게 ∩ 스냅샷 보유 가게 (원재료가 있는 곳만)
    prod_targets = [int(x) for x in prod_sql(
        "SELECT DISTINCT m.store_id FROM menu m "
        "JOIN store s ON s.id = m.store_id AND s.status='ACTIVE' AND s.naver_place_id IS NOT NULL "
        "WHERE m.deleted_at IS NULL "
        "AND NOT EXISTS (SELECT 1 FROM menu_image_file f WHERE f.menu_id = m.id) "
        "ORDER BY 1;").split()]
    snapshot_ids = set(int(x) for x in dataops_sql(
        "SELECT DISTINCT store_id FROM place_snapshot WHERE source='NAVER';").split())
    # 사진 0장 가게(메뉴 이미지 결손 없어도)도 대상에 포함 — 가게 사진 채움
    photo_zero = [int(x) for x in prod_sql(
        "SELECT s.id FROM store s WHERE s.status='ACTIVE' AND s.naver_place_id IS NOT NULL "
        "AND NOT EXISTS (SELECT 1 FROM store_image_file f WHERE f.store_id = s.id) ORDER BY 1;").split()]
    # 신규 등록 가게(id 큰 쪽)부터 — 구간 뒤쪽은 과거 백필에서 사진 없던 가게 재확인이라 저수확
    targets = sorted(set(t for t in prod_targets if t in snapshot_ids)
                     | set(t for t in photo_zero if t in snapshot_ids), reverse=True)
    if scope is not None:
        targets = [t for t in targets if t in scope]
    if args.skip:
        targets = targets[args.skip:]
    if args.pilot:
        targets = targets[:args.pilot]
    print(f"대상 가게 {len(targets):,}곳 (skip={args.skip})", flush=True)

    import threading
    from concurrent.futures import ThreadPoolExecutor

    stats = {"menu_ok": 0, "menu_fail": 0, "store_ok": 0, "store_fail": 0, "stores": 0, "done": 0}
    lock = threading.Lock()

    def process(store_id):
        local = {"menu_ok": 0, "menu_fail": 0, "store_ok": 0, "store_fail": 0}
        try:
            menu_images, store_photos = extract_store(store_id)
            if menu_images or store_photos:
                rows = prod_sql(
                    "SELECT m.id || E'\\t' || m.normalized_name FROM menu m "
                    "WHERE m.store_id = %d AND m.deleted_at IS NULL "
                    "AND NOT EXISTS (SELECT 1 FROM menu_image_file f WHERE f.menu_id = m.id);" % store_id)
                for line in rows.strip().splitlines():
                    if "\t" not in line:
                        continue
                    menu_id, norm_name = line.split("\t", 1)
                    urls = menu_images.get(norm_name.strip(), [])[:MENU_IMAGE_CAP]
                    if not urls:
                        continue
                    if args.dry_run:
                        local["menu_ok"] += len(urls)
                        continue
                    try:
                        ok, fails = post_from_urls("menus", int(menu_id), urls)
                        local["menu_ok"] += ok
                        local["menu_fail"] += len(fails)
                        # 사유를 안 찍어서 '실패 52' 만 보이고 원인(too large)을 며칠 못 봤다 (2026-09-09)
                        if fails:
                            print(f"  [menu {menu_id}] {fails[0]}", flush=True)
                    except Exception as e:
                        local["menu_fail"] += len(urls)
                        print(f"  [menu {menu_id}] 호출 실패: {str(e)[:80]}", flush=True)

                if store_photos:
                    has_photo = prod_sql(
                        "SELECT EXISTS (SELECT 1 FROM store_image_file f WHERE f.store_id = %d);"
                        % store_id).strip()
                    if has_photo == "f" and args.dry_run:
                        local["store_ok"] += len(store_photos[:STORE_IMAGE_CAP])
                    elif has_photo == "f":
                        try:
                            ok, fails = post_from_urls("stores", store_id, store_photos[:STORE_IMAGE_CAP])
                            local["store_ok"] += ok
                            local["store_fail"] += len(fails)
                            if fails:
                                print(f"  [store {store_id}] {fails[0]}", flush=True)
                        except Exception as e:
                            local["store_fail"] += len(store_photos[:STORE_IMAGE_CAP])
                            print(f"  [store {store_id}] 호출 실패: {str(e)[:80]}", flush=True)
        except Exception as e:
            print(f"  [store {store_id}] 처리 실패: {str(e)[:80]}", flush=True)
        # 서버 부하 배려 간격은 실제 업로드가 있었을 때만 — 재실행 시 무작업 가게는 즉시 통과
        if any(local.values()) and not args.dry_run:
            time.sleep(CALL_GAP_SECONDS)

        with lock:
            for k, v in local.items():
                stats[k] += v
            if local["menu_ok"] or local["store_ok"]:
                stats["stores"] += 1
            stats["done"] += 1
            if stats["done"] % 100 == 0 or stats["done"] == len(targets):
                print(f"진행 {stats['done']:,}/{len(targets):,} — 메뉴사진 {stats['menu_ok']:,} "
                      f"· 가게사진 {stats['store_ok']:,} · 실패 {stats['menu_fail'] + stats['store_fail']}",
                      flush=True)

    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(process, targets))

    if args.dry_run:
        print("(dry-run — 본 앱 호출 없음. 아래 수는 보낼 예정 장수)", flush=True)
    print(f"완료 — 반영 가게 {stats['stores']:,} · 메뉴사진 {stats['menu_ok']:,} · 가게사진 {stats['store_ok']:,} "
          f"· 실패 메뉴 {stats['menu_fail']} 가게 {stats['store_fail']}", flush=True)

if __name__ == "__main__":
    sys.exit(main())
