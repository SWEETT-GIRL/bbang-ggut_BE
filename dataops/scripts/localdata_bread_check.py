#!/usr/bin/env python3
"""카테고리로 못 가르는 신규 후보의 메뉴 확인 — G1 관문(bakery_gate)의 메뉴 입력을 모은다.

new_verify_results.jsonl 에서 EXIST인데 네이버 카테고리 잎이 빵집 잎이 아닌 곳(bakery_gate.category_pass 가 아닌 곳 —
09-20 까지는 경로 부분일치 힌트 OTHER/CAFE 로 골라 '분식,호떡'·'카페,디저트,찐빵' 이 빠졌다)의
네이버 플레이스 상세를 긁어(sync_worker.fetch_place 재사용, Apollo 상태),
메뉴명 전체(menus — G1 이 제품 빵 몫을 센다)와 소개 텍스트에서 빵류 제품을 찾는다:

  SELLS_BREAD  메뉴에 빵류 제품 존재 (또는 소개 텍스트가 빵/베이커리 명시)
  NO_BREAD     메뉴는 있는데 빵류 0
  UNKNOWN      메뉴·소개 텍스트를 못 얻음 (페이지 무메뉴·비식당 타입 등)

요청 간격 1.5초(sync_worker 실측 무차단 하한). 운영 DB 무접촉.
출력: matching/bread_check_results.jsonl (placeId 기준 재개 가능)
"""
import argparse
import io
import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sync_worker import fetch_place
from bakery_gate import category_pass, leaf_of

ROOT = Path(__file__).resolve().parent.parent
INPUT = ROOT / "matching" / "new_verify_results.jsonl"
OUTPUT = ROOT / "matching" / "bread_check_results.jsonl"

# 빵류 제품 키워드 — 메뉴명 판정용 (음료·식사류는 제외)
BREAD_PAT = re.compile(
    r"빵|베이글|크루아상|크로와상|소금빵|스콘|바게트|치아바타|캉파뉴|프레첼|도넛|도너츠"
    r"|꽈배기|케이크|케익|타르트|마카롱|휘낭시에|마들렌|카스테라|머핀|쿠키|브라우니"
    r"|(?<!스)파이(?![어시브널])|페이스트리|식빵|모닝빵|츄러스|와플|크로플|롤케"
)
SUMMARY_PAT = re.compile(r"빵|베이커리|제과|베이킹")
# 메뉴 항목 타입 — 옛 Menu, 09 개편 뒤 PlaceMenuItem, 배민 연동 메뉴. 'Menu' 가 들어간 타입 전부를 읽으면
# PlaceMenuCategory('추천 메뉴')·PlaceDetail_BaeminMenuGroup('대표메뉴') 같은 묶음 이름까지 메뉴로 센다
MENU_TYPENAMES = {"Menu", "PlaceMenuItem", "PlaceDetail_BaeminMenu"}
MAX_MENUS = 300
TEXT_KEYS = ("description", "introduction", "microReview", "summary", "aiBriefing", "greetingMessage")


def collect_menus(apollo: dict) -> list[str]:
    names = []
    for key, value in apollo.items():
        if not isinstance(value, dict):
            continue
        if value.get("__typename") in MENU_TYPENAMES:
            name = value.get("name")
            if isinstance(name, str) and name.strip():
                names.append(name.strip())
    return names


def collect_text(apollo: dict) -> str:
    chunks = []
    def walk(node):
        if isinstance(node, dict):
            for k, v in node.items():
                if k in TEXT_KEYS:
                    if isinstance(v, str):
                        chunks.append(v)
                    elif isinstance(v, list):
                        chunks.extend(x for x in v if isinstance(x, str))
                else:
                    walk(v)
        elif isinstance(node, list):
            for item in node:
                walk(item)
    walk(apollo)
    return " ".join(chunks)[:2000]


def main() -> int:
    global INPUT, OUTPUT
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", default=str(INPUT), help="검증 결과 JSONL (기본: matching/new_verify_results.jsonl)")
    ap.add_argument("--output", default=str(OUTPUT), help="메뉴 확인 결과 JSONL")
    ap.add_argument("--max", type=int, default=None, help="이번에 볼 최대 가게 수")
    args = ap.parse_args()
    INPUT, OUTPUT = Path(args.input), Path(args.output)
    rows = [json.loads(l) for l in io.open(INPUT, encoding="utf-8") if l.strip()]
    targets = [r for r in rows if r["judgment"] == "EXIST" and r.get("candidate")
               and not category_pass(leaf_of(r["candidate"].get("category")))]
    # REVIEW 검수에서 매핑 O로 편입됐지만 카테고리가 비빵(OTHER/CAFE)인 큐도 확인 대상
    queue_file = ROOT / "matching" / "_tmp_bread_queue.json"
    if queue_file.exists():
        queued = set(json.load(io.open(queue_file, encoding="utf-8")))
        targets += [r for r in rows if r["judgment"] == "REVIEW" and r["mgmtNo"] in queued]
    done = set()
    if OUTPUT.exists():
        for line in io.open(OUTPUT, encoding="utf-8"):
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            if "menus" in rec:   # 09-20 이전 기록은 메뉴 전체가 없어 G1 을 못 가른다 — 다시 긁는다
                done.add(rec["placeId"])
    queue = [r for r in targets if r["candidate"]["placeId"] not in done]
    if args.max:
        queue = queue[:args.max]
    print(f"[bread] 대상 {len(queue)} (전체 {len(targets)}, 완료 {len(done)})", flush=True)

    counts = {"BREAD_MENU": 0, "BREAD_TEXT": 0, "BREAD_NAME": 0, "NO_BREAD": 0, "UNKNOWN": 0}
    with io.open(OUTPUT, "a", encoding="utf-8") as output:
        for i, r in enumerate(queue, 1):
            place_id = r["candidate"]["placeId"]
            apollo, blocked = fetch_place(place_id)
            if blocked:
                print(f"[bread] 차단 감지 — 중단 (재실행 시 재개). 처리 {i-1}", flush=True)
                return 3
            menus, text = [], ""
            if apollo:
                menus = collect_menus(apollo)
                text = collect_text(apollo)
            bread_items = [m for m in menus if BREAD_PAT.search(m)]
            text_match = SUMMARY_PAT.search(text) if text else None
            name_hit = BREAD_PAT.search(r["name"]) or BREAD_PAT.search(r["candidate"]["name"] or "")
            # 증거 강도 티어: 메뉴 실물 > 소개 텍스트 > 상호명
            if bread_items:
                verdict = "BREAD_MENU"
            elif text_match:
                verdict = "BREAD_TEXT"
            elif name_hit:
                verdict = "BREAD_NAME"
            elif menus:
                verdict = "NO_BREAD"
            else:
                verdict = "UNKNOWN"
            snippet = ""
            if text_match:
                i0 = max(0, text_match.start() - 25)
                snippet = text[i0:text_match.end() + 25]
            counts[verdict] += 1
            output.write(json.dumps({
                "placeId": place_id, "name": r["name"], "naverName": r["candidate"]["name"],
                "naverCategory": r["candidate"]["category"], "bakeryHint": r["bakeryHint"],
                "verdict": verdict, "menuCount": len(menus),
                "breadItems": bread_items[:10], "textSnippet": snippet, "menus": menus[:MAX_MENUS],
            }, ensure_ascii=False) + "\n")
            output.flush()
            print(f"[bread] {i}/{len(queue)} {verdict} | {r['name']} | 메뉴 {len(menus)}"
                  f" | 빵류 {len(bread_items)} {bread_items[:3]}", flush=True)
            time.sleep(1.5)

    print(f"[bread] 완료 — {counts}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
