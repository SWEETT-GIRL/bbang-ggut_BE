# -*- coding: utf-8 -*-
"""사진 태깅 워커 — 가게당 Gemini 1호출(사진 ≤8장 + 소개글 + 블로그 3건) → 시각 태그·가게 태그·종류 추론·축 힌트 (인계 노트 §4·§5).

지킬 것:
  - 사진은 점주 등록분만: ldb-phinf.pstatic.net 이고, 가게 상단 사진(topPhotos)은 id 에 business 가 있는 것만.
    방문자·리뷰·기록 사진(pup-review-phinf 등)은 절대 보내지 않는다 (무료 등급 = 사람 검토·제품 개선에 쓰일 수 있음).
  - 네이버 원본 URL 을 외부 API 에 넘기지 않는다 — 여기서 받아 640px JPEG 로 줄여 base64 inline 으로만 보낸다.
  - 키는 환경변수 GEMINI_API_KEY 로만. 모델은 GEMINI_MODEL (없으면 ListModels 에서 flash-lite 계열을 고른다, --list-models 로 확인).
  - 무료 등급 한도: 15 RPM → 호출 간격 4.5s, 하루 --max-calls (기본 900). 429/5xx 지수 백오프. 재개는 store_photo_tag 에 DONE 인 가게를 건너뛰어서.
  - 결과는 로컬 store_photo_tag (Flyway V20260906100100). 운영 DB 에는 쓰지 않는다.

사용:
  python scripts/photo_tag_worker.py --env ../bbang-ggut_dataops_BE-localdata/.env --testset matching/course_testset_20260904.tsv --dry-run --dump-dir <폴더>
  python scripts/photo_tag_worker.py --env ... --list-models
  python scripts/photo_tag_worker.py --env ... --testset ... --out matching/photo_tag_pilot_YYYYMMDD.tsv     # 실호출 파일럿 (GEMINI_API_KEY 필요)
  python scripts/photo_tag_worker.py --env ... --spot 성수동 --max-calls 900                                   # 상권 단위
"""
import argparse
import base64
import csv
import datetime as dt
import hashlib
import html
import io
import json
import os
import re
import sys
import time
from urllib.parse import urlparse

import requests
from PIL import Image, ImageDraw, ImageFont, ImageOps

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from store_tags import TAGS, local_csv, prod_csv  # noqa: E402

PROMPT_VERSION = 1
ALLOWED_HOST = "ldb-phinf.pstatic.net"
MAX_PHOTOS = 8
MAX_PX = 640
JPEG_QUALITY = 82
RPM_GAP_SECONDS = 4.5
MIN_TAG_CONF, MIN_KIND_CONF = 0.6, 0.7
GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
FETCH_HEADERS = {"User-Agent": "Mozilla/5.0", "Referer": "https://m.place.naver.com/"}
AXES = ["taste", "texture", "filling", "vibe"]
STORE_TAGS = TAGS + ["수제"]
VISUAL_TAGS = {  # 인계 노트 §5 시각 태그 사전 초안
    "겉바삭": "결·크러스트·광택, 단면의 층 (② 바삭 +)",
    "속촉촉": "단면의 결이 촘촘하고 윤기 (② 부드 +)",
    "속꽉참": "단면에 크림·필링·재료가 두껍게 (③ 풍부 +)",
    "담백비주얼": "토핑·필링 없는 단색 빵 (③ 심플 +, ① 담백 약하게)",
    "화려한토핑": "과일·크림·장식이 위에 많음 (④ 트렌디 +, ① 달콤 약하게)",
    "미니멀플레이팅": "단색 접시·여백·한 개만 (④ 트렌디 +)",
    "브라운클래식": "갈색 톤·나무·종이 포장·전통 진열 (④ 클래식 +)",
    "카페공간": "테이블·좌석·음료가 같이 보임 (유형 태그 카페형 +)",
}
STORE_TAG_HINTS = {  # 2026-09-19 새 태그 사전(dataops #11, store_tags.py 머리말). 건강빵은 건강한맛으로 합쳤다
    "웨이팅": "줄·대기 언급", "머물기": "좌석·테이블·음료가 같이 보이는 머물기 좋은 공간", "공간사진": "인테리어·뷰·컨셉이 사진으로 드러남",
    "가성비": "부담 없는 가격·푸짐한 진열", "건강한맛": "통밀·호밀·비건·저당·천연발효·무설탕 등 건강 지향",
    "특별한메뉴": "다른 곳에서 보기 힘든 메뉴", "대형": "넓은 매장·많은 진열·큰 규모", "디저트": "빵보다 케이크·구움과자 등 디저트 위주",
    "수제": "직접 만드는 과정·수제 강조",
}
SOURCE_LABEL = {"recommend": "대표 메뉴", "review_menu": "리뷰 언급 메뉴", "bucket_menu": "빵·기타 메뉴 — 종류 추론 대상", "top_business": "가게 상단 사진(점주 등록)"}
SESSION = requests.Session()


def allowed(url):
    try:
        return bool(url) and urlparse(url).hostname == ALLOWED_HOST
    except Exception:  # noqa: BLE001
        return False


def norm(name):
    return re.sub(r"[\s()\[\]·,./-]", "", (name or "")).lower()


def clean_text(s, limit):
    s = html.unescape(re.sub(r"<[^>]+>", " ", s or ""))
    s = re.sub(r"\s+", " ", s).strip()
    return s[:limit]


def load_kinds():
    """종류 목록 = 로컬 menu_kind_axis(빵·기타·요거트 제외) + enum 후보 문서 1열."""
    kinds = [r["kind"] for r in local_csv("SELECT kind FROM menu_kind_axis WHERE kind NOT IN ('빵','기타','요거트') ORDER BY kind;")]
    doc = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "course-reco", "menu_kind_enum_candidates.md")
    if os.path.exists(doc):
        for line in open(doc, encoding="utf-8"):
            m = re.match(r"^\| ([가-힣A-Za-z]+) \| [\d,]+ \| [\d,]+ \|", line)
            if m and m.group(1) not in kinds:
                kinds.append(m.group(1))
    return kinds


# ---------- 사진 선택·다운로드 ----------
def select_photos(signal, snapshot_menus, bucket_menu_names, cap=MAX_PHOTOS):
    """인계 노트 §4 순서: 대표 메뉴 → 리뷰 언급 상위 메뉴 → 빵·기타 메뉴 사진 → 가게 상단(점주 등록) 사진. 기록(사용자) 사진은 제외."""
    picks, seen = [], set()

    def add(source, url, label):
        if not allowed(url) or url in seen:
            return
        seen.add(url)
        picks.append({"source": source, "url": url, "label": label})

    for r in signal.get("recommendMenus") or []:
        add("recommend", r.get("imageUrl"), r.get("name"))
    for rm in sorted(signal.get("reviewMenus") or [], key=lambda x: -(x.get("count") or 0)):
        label = (rm.get("label") or "").strip()
        if not label:
            continue
        for m in snapshot_menus:
            if label in m["name"] and m["images"]:
                add("review_menu", m["images"][0], m["name"])
                break
    for m in snapshot_menus:
        if norm(m["name"]) in bucket_menu_names and m["images"]:
            add("bucket_menu", m["images"][0], m["name"])
    for p in signal.get("topPhotos") or []:
        if "business" in (p.get("id") or ""):
            add("top_business", p.get("url"), None)
    return picks[:cap]


def fetch_resized(url):
    r = SESSION.get(url, headers=FETCH_HEADERS, timeout=20)
    r.raise_for_status()
    sha1 = hashlib.sha1(r.content).hexdigest()
    im = ImageOps.exif_transpose(Image.open(io.BytesIO(r.content))).convert("RGB")
    im.thumbnail((MAX_PX, MAX_PX))
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=JPEG_QUALITY, optimize=True)
    return buf.getvalue(), sha1, im.size


def download_photos(picks, log):
    out, hashes = [], set()
    for p in picks:
        try:
            data, sha1, size = fetch_resized(p["url"])
        except Exception as e:  # noqa: BLE001
            log(f"    사진 실패 {p['source']} {p['url'][:60]}... {type(e).__name__}: {str(e)[:80]}")
            continue
        if sha1 in hashes:
            continue
        hashes.add(sha1)
        out.append({**p, "idx": len(out) + 1, "sha1": sha1, "w": size[0], "h": size[1], "bytes": len(data), "jpeg": data})
    return out


def contact_sheet(photos, path, title):
    cell, cols = 260, 4
    rows = max(1, (len(photos) + cols - 1) // cols)
    sheet = Image.new("RGB", (cols * cell, rows * (cell + 34) + 30), "white")
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/malgun.ttf", 14)
    except Exception:  # noqa: BLE001
        font = ImageFont.load_default()
    draw.text((8, 6), title, fill="black", font=font)
    for i, p in enumerate(photos):
        im = Image.open(io.BytesIO(p["jpeg"]))
        im.thumbnail((cell - 8, cell - 8))
        x, y = (i % cols) * cell + 4, 30 + (i // cols) * (cell + 34) + 4
        sheet.paste(im, (x, y))
        cap = f"{p['idx']} {SOURCE_LABEL[p['source']].split(' —')[0]}" + (f" · {p['label']}" if p.get("label") else "")
        draw.text((x, y + cell - 6), cap[:34], fill="black", font=font)
    sheet.save(path, "JPEG", quality=80)


# ---------- 프롬프트·스키마 ----------
def build_prompt(store, signal, photos, kinds):
    blogs = []
    for i, b in enumerate((signal.get("blogReviews") or [])[:3], 1):
        blogs.append(f"{i}) {clean_text(b.get('title'), 80)} — {clean_text(b.get('contents'), 300)}")
    photo_lines = [f"{p['idx']}: {SOURCE_LABEL[p['source']]}" + (f" — {p['label']}" if p.get("label") else "") for p in photos]
    vis = "\n".join(f"   - {k}: {v}" for k, v in VISUAL_TAGS.items())
    st = "\n".join(f"   - {k}: {v}" for k, v in STORE_TAG_HINTS.items())
    return (
        "당신은 빵집 사진과 소개글을 보고 구조화된 태그를 붙이는 분류기다. 사진·글에 보이는 근거로만 판단하고, 근거가 약하면 confidence 를 낮게 매긴다. "
        "목록에 없는 태그·종류는 쓰지 않는다. 사람 얼굴·개인정보는 무시한다.\n\n"
        f"[가게] {store['name']} · 네이버 카테고리: {signal.get('category') or '-'} · 상권: {store.get('spot') or '-'}\n"
        f"[소개글] {clean_text(signal.get('description'), 600) or '(없음)'}\n"
        "[블로그]\n" + ("\n".join(blogs) if blogs else "(없음)") + "\n"
        "[사진] 번호는 1부터, 첨부 순서와 같다.\n" + "\n".join(photo_lines) + "\n\n"
        "판단할 것\n"
        "1. isBakeryPhotoSet: 이 사진 묶음이 빵·디저트를 파는 가게의 것인가.\n"
        "2. visualTags: 사진에서 실제로 보이는 것만. 사전(태그: 보는 것):\n" + vis + "\n"
        "3. storeTags: 사진과 글에서 드러나는 가게 성격. 사전:\n" + st + "\n"
        "4. menuKinds: '종류 추론 대상' 표시가 있는 사진에 대해서만, 사진 속 빵의 종류를 아래 목록에서 하나 고른다. 목록에 없거나 불확실하면 넣지 않는다.\n"
        "   목록: " + ", ".join(kinds) + "\n"
        "5. axisHint: 사진 전체에서 받은 인상을 -1~+1 로. taste: 달콤(-1)↔담백(+1), texture: 부드러움(-1)↔바삭(+1), "
        "filling: 속 풍부(-1)↔심플(+1), vibe: 클래식(-1)↔트렌디(+1). 근거가 없으면 0.\n"
        "6. note: 판단 근거 한 줄(한국어, 80자 이내).\n"
    )


RESPONSE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "isBakeryPhotoSet": {"type": "BOOLEAN"},
        "visualTags": {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {"tag": {"type": "STRING"}, "confidence": {"type": "NUMBER"}}, "required": ["tag", "confidence"]}},
        "storeTags": {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {"tag": {"type": "STRING"}, "confidence": {"type": "NUMBER"}}, "required": ["tag", "confidence"]}},
        "menuKinds": {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {"photoIndex": {"type": "INTEGER"}, "kind": {"type": "STRING"}, "confidence": {"type": "NUMBER"}}, "required": ["photoIndex", "kind", "confidence"]}},
        "axisHint": {"type": "OBJECT", "properties": {a: {"type": "NUMBER"} for a in AXES}, "required": AXES},
        "note": {"type": "STRING"},
    },
    "required": ["isBakeryPhotoSet", "visualTags", "storeTags", "menuKinds", "axisHint", "note"],
}


def build_parts(prompt, photos):
    parts = [{"text": prompt}]
    for p in photos:
        parts.append({"text": f"사진 {p['idx']}:"})
        parts.append({"inline_data": {"mime_type": "image/jpeg", "data": base64.b64encode(p["jpeg"]).decode()}})
    return parts


# ---------- Gemini ----------
def api_key():
    key = os.environ.get("GEMINI_API_KEY")
    if not key:
        raise SystemExit("GEMINI_API_KEY 환경변수가 없다 — 키는 사용자가 환경변수로 넣는다 (코드·노트에 적지 않음)")
    return key


def list_models(key):
    r = SESSION.get(f"{GEMINI_BASE}/models", headers={"x-goog-api-key": key}, params={"pageSize": 200}, timeout=30)
    r.raise_for_status()
    return [m for m in r.json().get("models", []) if "generateContent" in (m.get("supportedGenerationMethods") or [])]


def pick_model(key):
    name = os.environ.get("GEMINI_MODEL")
    if name:
        return name
    lite = sorted((m["name"].split("/", 1)[1] for m in list_models(key) if "flash-lite" in m["name"] and "preview" not in m["name"]), reverse=True)
    if not lite:
        raise SystemExit("flash-lite 계열 모델을 못 찾았다 — --list-models 로 확인하고 GEMINI_MODEL 로 지정")
    return lite[0]


def gemini_generate(key, model, parts, log):
    body = {"contents": [{"role": "user", "parts": parts}],
            "generationConfig": {"temperature": 0.2, "responseMimeType": "application/json", "responseSchema": RESPONSE_SCHEMA}}
    url = f"{GEMINI_BASE}/models/{model}:generateContent"
    for attempt in range(5):
        t0 = time.time()
        r = SESSION.post(url, headers={"x-goog-api-key": key, "Content-Type": "application/json"}, json=body, timeout=180)
        latency = int((time.time() - t0) * 1000)
        if r.status_code == 200:
            data = r.json()
            text = "".join(p.get("text", "") for p in data["candidates"][0]["content"]["parts"])
            usage = data.get("usageMetadata") or {}
            return json.loads(text), usage.get("promptTokenCount"), usage.get("candidatesTokenCount"), latency
        if r.status_code == 429:
            wait = float(r.headers.get("Retry-After") or 30 * (2 ** attempt))
            log(f"    429 — {wait:.0f}s 대기 (시도 {attempt + 1})")
            time.sleep(wait)
            continue
        if r.status_code >= 500:
            wait = 10 * (2 ** attempt)
            log(f"    {r.status_code} — {wait}s 대기 (시도 {attempt + 1})")
            time.sleep(wait)
            continue
        raise RuntimeError(f"HTTP {r.status_code}: {r.text[:300]}")
    raise RuntimeError("재시도 초과 (429/5xx)")


def postprocess(raw, kinds, photos):
    """확신도 0.6 미만 태그 버림, 종류 추론은 0.7 이상 + 목록 안 + 종류 추론 대상 사진만."""
    bucket_idx = {p["idx"] for p in photos if p["source"] == "bucket_menu"}

    def keep(items, allowed_tags, min_conf):
        out = []
        for it in items or []:
            tag, conf = it.get("tag"), it.get("confidence")
            if tag in allowed_tags and isinstance(conf, (int, float)) and conf >= min_conf:
                out.append({"tag": tag, "confidence": round(float(conf), 2)})
        return sorted(out, key=lambda x: -x["confidence"])

    menu_kinds = [{"photoIndex": k.get("photoIndex"), "kind": k.get("kind"), "confidence": round(float(k.get("confidence") or 0), 2)}
                  for k in raw.get("menuKinds") or []
                  if k.get("kind") in kinds and (k.get("confidence") or 0) >= MIN_KIND_CONF and k.get("photoIndex") in bucket_idx]
    axis = {a: max(-1.0, min(1.0, float((raw.get("axisHint") or {}).get(a) or 0))) for a in AXES}
    return {"is_bakery_photo_set": bool(raw.get("isBakeryPhotoSet")), "visual_tags": keep(raw.get("visualTags"), VISUAL_TAGS, MIN_TAG_CONF),
            "store_tags": keep(raw.get("storeTags"), STORE_TAGS, MIN_TAG_CONF), "menu_kinds": menu_kinds, "axis_hint": axis, "note": (raw.get("note") or "")[:200]}


# ---------- 로컬 DB ----------
def local_conn():
    import psycopg2
    return psycopg2.connect(host="localhost", port=15433, dbname="dataops", user="dataops", password="dataops")


JSON_COLS = ("source_photos", "visual_tags", "store_tags", "menu_kinds", "axis_hint", "raw_response")


def upsert(conn, row):
    from psycopg2.extras import Json
    cols = ["store_id", "prompt_version", "signal_snapshot_id", "tagger_model", "status", "photo_count", "source_photos", "is_bakery_photo_set",
            "visual_tags", "store_tags", "menu_kinds", "axis_hint", "note", "raw_response", "prompt_tokens", "response_tokens", "latency_ms", "error"]
    vals = [Json(row[c]) if c in JSON_COLS and row.get(c) is not None else row.get(c) for c in cols]
    sets = ", ".join(f"{c} = EXCLUDED.{c}" for c in cols if c not in ("store_id", "prompt_version"))
    with conn.cursor() as cur:
        cur.execute(f"INSERT INTO store_photo_tag ({', '.join(cols)}) VALUES ({', '.join(['%s'] * len(cols))}) "
                    f"ON CONFLICT (store_id, prompt_version) DO UPDATE SET {sets}, updated_at = now()", vals)
    conn.commit()


def done_ids(conn, ids):
    with conn.cursor() as cur:
        cur.execute("SELECT store_id FROM store_photo_tag WHERE prompt_version = %s AND status = 'DONE' AND store_id = ANY(%s)", (PROMPT_VERSION, [int(i) for i in ids]))
        return {str(r[0]) for r in cur.fetchall()}


# ---------- 워크리스트·입력 ----------
def worklist(args):
    if args.testset:
        rows = list(csv.DictReader(open(args.testset, encoding="utf-8"), delimiter="\t"))
        return [{"store_id": r["store_id"], "name": r.get("prod_name") or r["store_id"], "spot": r.get("spot")} for r in rows]
    if args.store_ids:
        ids = args.store_ids.split(",")
        rows = prod_csv(f"SELECT id AS store_id, name, region_3rd AS spot FROM store WHERE id IN ({','.join(ids)});", args.env)
        return [{"store_id": r["store_id"], "name": r["name"], "spot": r["spot"]} for r in rows]
    # 상권: ACTIVE 가게를 리뷰 많은 순 (전환율·담긴 횟수는 아직 없음)
    rows = prod_csv(f"SELECT id AS store_id, name, region_3rd AS spot FROM store WHERE status = 'ACTIVE' AND (region_3rd ~ '^{args.spot}' OR region_2nd = '{args.spot}');", args.env)
    id_list = ",".join(r["store_id"] for r in rows) or "0"
    order = {r["store_id"]: int(r["n"] or 0) for r in local_csv(f"SELECT store_id, visitor_reviews_total AS n FROM store_signal WHERE store_id IN ({id_list});")}
    return sorted(({"store_id": r["store_id"], "name": r["name"], "spot": r["spot"]} for r in rows), key=lambda s: -order.get(s["store_id"], 0))


def load_inputs(stores, env):
    ids = ",".join(s["store_id"] for s in stores)
    signals = {r["store_id"]: (r, json.loads(r["signals"])) for r in local_csv(
        f"SELECT store_id, snapshot_id, category, signals::text AS signals FROM store_signal WHERE store_id IN ({ids});")}
    snap_ids = ",".join(r["snapshot_id"] for r, _ in signals.values()) or "0"
    menus = {}
    for r in local_csv(f"SELECT ps.store_id, v.value->>'name' AS name, v.value->'images' AS images FROM place_snapshot ps, jsonb_each(ps.payload) v "
                       f"WHERE ps.id IN ({snap_ids}) AND v.key LIKE 'Menu:%' ORDER BY ps.store_id, (v.value->>'index')::int NULLS LAST;"):
        menus.setdefault(r["store_id"], []).append({"name": r["name"] or "", "images": json.loads(r["images"]) if r["images"] else []})
    bucket = {}
    for r in prod_csv(f"SELECT m.store_id, m.name FROM menu m JOIN menu_category mc ON mc.id = m.menu_category_id "
                      f"WHERE m.store_id IN ({ids}) AND m.deleted_at IS NULL AND mc.name IN ('빵','기타');", env):
        bucket.setdefault(r["store_id"], set()).add(norm(r["name"]))
    profile = {r["store_id"]: r for r in local_csv(f"SELECT store_id, bbti_type, type_label FROM store_profile_draft WHERE store_id IN ({ids});")}
    return signals, menus, bucket, profile


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--env", required=True, help="localdata .env (PROD_DB_* 읽기 전용)")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--testset", help="테스트셋 TSV (store_id, prod_name, spot)")
    g.add_argument("--spot", help="상권 이름 (운영 region_3rd 접두 / region_2nd)")
    g.add_argument("--store-ids", help="쉼표로 구분한 store_id")
    ap.add_argument("--dry-run", action="store_true", help="API 호출 없이 사진 선택·리사이즈·패킷 크기만 (키 불필요)")
    ap.add_argument("--no-download", action="store_true", help="dry-run 에서 다운로드도 생략 (선택 계획만)")
    ap.add_argument("--dump-dir", help="가게별 콘택트시트(JPEG)·프롬프트(txt)를 저장할 폴더")
    ap.add_argument("--out", help="검수용 TSV 경로 (기본: dry-run 은 dump-dir 또는 현재 폴더, 실호출은 matching/photo_tag_pilot_<날짜>.tsv)")
    ap.add_argument("--max-calls", type=int, default=900, help="이번 실행 최대 호출 수 (무료 등급 1,000 RPD 아래)")
    ap.add_argument("--limit", type=int, default=0, help="워크리스트 앞에서 N곳만")
    ap.add_argument("--force", action="store_true", help="이미 DONE 인 가게도 다시")
    ap.add_argument("--list-models", action="store_true")
    args = ap.parse_args()

    if args.list_models:
        for m in list_models(api_key()):
            print(f"{m['name'].split('/', 1)[1]:40s} in={m.get('inputTokenLimit')} out={m.get('outputTokenLimit')}  {m.get('displayName')}")
        return
    if not (args.testset or args.spot or args.store_ids):
        ap.error("--testset / --spot / --store-ids 중 하나")

    def log(s):
        print(s, flush=True)

    stores = worklist(args)
    if args.limit:
        stores = stores[: args.limit]
    kinds = load_kinds()
    key = model = None
    conn = local_conn()
    if not args.dry_run:
        key = api_key()
        model = pick_model(key)
        if not args.force:
            skip = done_ids(conn, [s["store_id"] for s in stores])
            stores = [s for s in stores if s["store_id"] not in skip]
            log(f"이미 DONE 건너뜀 {len(skip)}곳")
        log(f"모델 {model} · prompt_version {PROMPT_VERSION} · 대상 {len(stores)}곳 · 최대 호출 {args.max_calls}")
    else:
        log(f"DRY-RUN · 대상 {len(stores)}곳 · 종류 목록 {len(kinds)}개 · 사진 다운로드 {'생략' if args.no_download else '수행'}")
    if args.dump_dir:
        os.makedirs(args.dump_dir, exist_ok=True)
    today = dt.date.today().strftime("%Y%m%d")
    out_path = args.out or (os.path.join(args.dump_dir or ".", f"photo_tag_dryrun_{today}.tsv") if args.dry_run else f"matching/photo_tag_pilot_{today}.tsv")

    review = []
    calls = 0
    last_call = 0.0
    stopped = False
    for chunk_start in range(0, len(stores), 100):
        if stopped:
            break
        chunk = stores[chunk_start: chunk_start + 100]
        signals, menus, bucket, profile = load_inputs(chunk, args.env)
        for s in chunk:
            sid = s["store_id"]
            if sid not in signals:
                log(f"{sid} {s['name']}: store_signal 없음 — 건너뜀")
                continue
            sig_row, sig = signals[sid]
            picks = select_photos(sig, menus.get(sid, []), bucket.get(sid, set()))
            src_counts = {k: sum(1 for p in picks if p["source"] == k) for k in SOURCE_LABEL}
            head = f"{sid} {s['name']} ({s.get('spot')}) — 후보 {len(picks)}장 " + " ".join(f"{k}:{v}" for k, v in src_counts.items() if v)
            base = {"store_id": sid, "name": s["name"], "spot": s.get("spot"), "photos": len(picks), **src_counts,
                    "draft_type": (profile.get(sid) or {}).get("bbti_type"), "draft_label": (profile.get(sid) or {}).get("type_label")}
            if not picks:
                log(head + " → SKIPPED(보낼 사진 없음)")
                if not args.dry_run:
                    upsert(conn, {"store_id": int(sid), "prompt_version": PROMPT_VERSION, "signal_snapshot_id": int(sig_row["snapshot_id"]), "status": "SKIPPED", "photo_count": 0, "error": "no allowed photos"})
                review.append({**base, "status": "SKIPPED"})
                continue
            if args.dry_run and args.no_download:
                log(head)
                review.append({**base, "status": "PLAN"})
                continue
            photos = download_photos(picks, log)
            prompt = build_prompt(s, sig, photos, kinds)
            packet_kb = (sum(p["bytes"] for p in photos) * 4 // 3 + len(prompt.encode())) // 1024
            log(head + f" → 받음 {len(photos)}장, 패킷 ≈{packet_kb}KB, 프롬프트 {len(prompt)}자")
            if args.dump_dir:
                contact_sheet(photos, os.path.join(args.dump_dir, f"store_{sid}.jpg"), f"{sid} {s['name']} ({s.get('spot')})")
                with open(os.path.join(args.dump_dir, f"store_{sid}.prompt.txt"), "w", encoding="utf-8") as f:
                    f.write(prompt)
            base.update({"photos": len(photos), "packet_kb": packet_kb})
            source_photos = [{k: p[k] for k in ("idx", "source", "label", "url", "sha1", "w", "h", "bytes")} for p in photos]
            if args.dry_run:
                review.append({**base, "status": "DRY"})
                continue
            if calls >= args.max_calls:
                log("최대 호출 수 도달 — 중단 (내일 이어서: 같은 명령 재실행)")
                stopped = True
                break
            gap = RPM_GAP_SECONDS - (time.time() - last_call)
            if gap > 0:
                time.sleep(gap)
            try:
                raw, ptok, rtok, latency = gemini_generate(key, model, build_parts(prompt, photos), log)
                last_call = time.time()
                calls += 1
                res = postprocess(raw, kinds, photos)
                upsert(conn, {"store_id": int(sid), "prompt_version": PROMPT_VERSION, "signal_snapshot_id": int(sig_row["snapshot_id"]), "tagger_model": model, "status": "DONE",
                              "photo_count": len(photos), "source_photos": source_photos, **res, "raw_response": raw, "prompt_tokens": ptok, "response_tokens": rtok, "latency_ms": latency})
                log(f"    OK {latency}ms tok {ptok}/{rtok} · bakery={res['is_bakery_photo_set']} · 시각 {[t['tag'] for t in res['visual_tags']]} · 가게 {[t['tag'] for t in res['store_tags']]} "
                    f"· 종류 {[(k['photoIndex'], k['kind']) for k in res['menu_kinds']]} · 축 {res['axis_hint']}")
                review.append({**base, "status": "DONE", "is_bakery": res["is_bakery_photo_set"],
                               "visual_tags": " ".join(f"{t['tag']}:{t['confidence']}" for t in res["visual_tags"]),
                               "store_tags": " ".join(f"{t['tag']}:{t['confidence']}" for t in res["store_tags"]),
                               "menu_kinds": " ".join(f"{k['photoIndex']}={k['kind']}:{k['confidence']}" for k in res["menu_kinds"]),
                               "axis_hint": " ".join(f"{a}:{res['axis_hint'][a]:+.2f}" for a in AXES), "note": res["note"], "tokens": f"{ptok}/{rtok}"})
            except Exception as e:  # noqa: BLE001
                last_call = time.time()
                calls += 1
                log(f"    FAIL {type(e).__name__}: {str(e)[:200]}")
                upsert(conn, {"store_id": int(sid), "prompt_version": PROMPT_VERSION, "signal_snapshot_id": int(sig_row["snapshot_id"]), "tagger_model": model, "status": "FAILED",
                              "photo_count": len(photos), "source_photos": source_photos, "error": f"{type(e).__name__}: {str(e)[:500]}"})
                review.append({**base, "status": "FAILED", "note": str(e)[:120]})

    if review:
        fields = ["store_id", "name", "spot", "status", "photos", *SOURCE_LABEL, "packet_kb", "draft_type", "draft_label", "is_bakery", "visual_tags", "store_tags", "menu_kinds", "axis_hint", "note", "tokens"]
        os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
        with open(out_path, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=fields, delimiter="\t", extrasaction="ignore")
            w.writeheader()
            w.writerows(review)
        log(f"\n검수 TSV: {out_path} ({len(review)}행) · 호출 {calls}회")


if __name__ == "__main__":
    main()
