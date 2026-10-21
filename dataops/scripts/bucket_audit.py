"""'빵'·'기타' 버킷 감사 — 운영 메뉴 중 두 버킷에 있는 이름을 dataops 끝단어 분류기(MenuCategoryClassifier 사전)로 다시 분류해
① kind 로 회복 가능한 건수(종류별) ② 음료로 보이는 건수 ③ 정크(사진 탭 이름 등) ④ 남는 이름 상위(enum 후보)를 센다. 읽기 전용.

분류 규칙(분류기와 같은 원리): 이름에서 괄호 안을 지우고, 사전의 별칭 중 가장 뒤에서 끝나는 것(= 마지막 제품 타입 단어)을 고른다.
음료 판정은 음료 제품타입 단어가 빵 종류 단어보다 뒤에서 끝날 때. 실제 UPDATE 는 판정층·승인 경로에서 하므로 여기 숫자는 규모 추정용.

사용
    python scripts/bucket_audit.py --env ..\bbang-ggut_dataops_BE-localdata\.env
"""
import argparse
import csv
import io
import os
import re
import subprocess
from collections import Counter, defaultdict

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# MenuCategoryClassifier (reconcile/policy) 사전 — 2026-09-04 main 기준. '빵' 은 폴백이라 제외
KIND_ALIASES = {
    "소금빵": ["소금빵"], "식빵": ["식빵"], "바게트": ["바게트", "바게뜨", "바켓트"],
    "크루아상": ["크루아상", "크로와상", "크로아상", "크라상", "크루아쌍"], "퀸아망": ["퀸아망", "뀐아망"],
    "치아바타": ["치아바타", "치아바따"], "포카치아": ["포카치아", "포카챠", "포카시아"], "사워도우": ["사워도우", "샤워도우"],
    "깜빠뉴": ["깜빠뉴", "캄파뉴", "깜파뉴"], "베이글": ["베이글"], "샌드위치": ["샌드위치", "샌드윗치", "샌드"],
    "토스트": ["토스트", "토스티", "프렌치토스트"], "쫀득빵": ["쫀득빵"], "맘모스": ["맘모스", "맘모스빵"], "앙버터": ["앙버터"],
    "소보로": ["소보로", "소보루"], "브리오슈": ["브리오슈", "브리오쉬"],
    "페이스트리": ["페이스트리", "페스츄리", "패스츄리", "페스트리", "데니쉬", "데니시"], "스콘": ["스콘"],
    "에그타르트": ["에그타르트", "에그타르뜨", "에그따르트"], "타르트": ["타르트", "따르트", "타르뜨"],
    "휘낭시에": ["휘낭시에", "피낭시에"], "마들렌": ["마들렌", "마들렌느", "마들랜"], "까눌레": ["까눌레", "카눌레", "꺄눌레"],
    "쿠키": ["쿠키", "사브레"], "브라우니": ["브라우니"], "케이크": ["케이크", "케익", "케잌", "갸또", "가또", "가토"],
    "푸딩": ["푸딩"], "러스크": ["러스크"], "파이": ["파이"], "도넛": ["도넛", "도너츠", "도나스", "도넛츠", "도우넛", "꽈배기"],
    "떡": ["떡", "설기", "인절미", "찹쌀떡", "가래떡", "경단", "송편"], "소세지빵": ["소세지빵", "소시지빵"],
    "마늘빵": ["마늘빵", "갈릭브레드"], "고구마빵": ["고구마빵"], "밤빵": ["밤빵"], "빙수": ["빙수", "팥빙수"],
    "티라미수": ["티라미수", "티라미슈"], "크로플": ["크로플"], "아이스크림": ["아이스크림", "젤라또", "젤라토", "소프트콘"],
    "브륄레": ["브륄레", "브릴레", "크렘브륄레"], "버터떡": ["버터떡"], "요거트": ["요거트", "요거트볼", "그릭요거트"],
    "크로크무슈": ["크로크무슈", "크로크무시", "크로크마담"], "파운드": ["파운드"], "쫀득쿠키": ["쫀득쿠키", "쫀쿠"],
    "번": ["번"], "롤": ["롤", "시나몬롤", "소금롤"], "버터바": ["버터바"], "에끌레어": ["에끌레어", "에클레어", "에끌레르"],
    "마카롱": ["마카롱", "카롱", "뚱카롱"], "슈": ["슈", "슈크림"], "프레첼": ["프레첼", "프레즐", "프레츨"], "몽블랑": ["몽블랑"],
    "고로케": ["고로케", "고로께", "고르케", "크로켓"], "프레지에": ["프레지에"], "비스킷": ["비스킷", "비스켓", "비스코티"],
    "티그레": ["티그레"], "크림빵": ["크림빵"], "샌드베이글": ["샌드베이글"], "떠먹케": ["떠먹케"], "와플": ["와플"],
    "맘모롤": ["맘모롤"], "머핀": ["머핀"], "다쿠아즈": ["다쿠아즈", "다크아즈", "다쿠와즈"],
}
# 음료 제품타입 단어 (BeverageFilter 의 끝단어 원리 축약판 — 규모 추정용)
BEVERAGE_WORDS = [
    "라떼", "라테", "아메리카노", "커피", "에스프레소", "콜드브루", "카푸치노", "마끼아또", "마키아토", "마끼야또", "모카", "플랫화이트",
    "에이드", "주스", "쥬스", "스무디", "쉐이크", "셰이크", "프라페", "프라푸치노", "밀크티", "아이스티", "티", "차", "뱅쇼", "코코아",
    "핫초코", "초코우유", "우유", "미숫가루", "아포가토", "아포카토", "아샷추", "소다", "콕", "맥주", "와인", "음료", "드링크", "생맥",
    "하이볼", "칵테일", "사이다", "콜라", "탄산수", "생수", "물", "레모네이드", "요거트스무디", "블렌디드", "카모마일", "히비스커스",
    "얼그레이", "페퍼민트", "루이보스", "핸드드립", "드립", "더치", "말차", "녹차", "홍차", "유자", "자몽", "생강", "대추", "오미자", "쌍화",
]
# '차' 처럼 짧은 단어는 오탐이 많아 이름 끝에서만 인정
STRICT_END = {"차", "티", "물", "콕", "드립", "모카", "유자", "자몽", "생강", "대추", "오미자", "쌍화", "말차", "녹차", "홍차"}
JUNK_EXACT = {"내부", "외부", "음식·음료", "음식음료", "메뉴", "기타", "매장", "매장내부", "인테리어", "외관", "사진", "메뉴판", "가격표", "간판", "포장", "굿즈"}
PAREN = re.compile(r"\([^()]*\)")


def read_env(path, key):
    for line in open(path, encoding="utf-8"):
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    raise SystemExit(f"{path}: {key} 없음")


def prod_csv(sql, env_path):
    r = subprocess.run(["docker", "exec", "-i", "-e", f"PGPASSWORD={read_env(env_path, 'PROD_DB_PASSWORD')}", "-e", "PGSSLMODE=require",
                        "-e", "PGOPTIONS=-c default_transaction_read_only=on", "bbanggut-dataops-db",
                        "psql", "-h", "host.docker.internal", "-p", "15432", "-U", read_env(env_path, "PROD_DB_USERNAME"), "-d", "soboro",
                        "--csv", "-v", "ON_ERROR_STOP=1", "-f", "-"],
                       input=sql, capture_output=True, text=True, encoding="utf-8")
    if r.returncode:
        raise SystemExit("psql 실패: " + r.stderr.strip()[:500])
    return list(csv.DictReader(io.StringIO(r.stdout)))


def last_end(name, words):
    """words 중 가장 뒤에서 끝나는 것 → (끝 위치, 단어). 없으면 (-1, None)"""
    best = (-1, None)
    for w in words:
        idx = name.rfind(w)
        if idx < 0:
            continue
        if w in STRICT_END and idx + len(w) != len(name):
            continue
        end = idx + len(w)
        if end > best[0] or (end == best[0] and len(w) > len(best[1] or "")):
            best = (end, w)
    return best


def classify(name):
    """→ ('kind', 종류) | ('beverage', 단어) | ('junk', None) | ('bread', None) | ('other', None)"""
    n = PAREN.sub("", (name or "").lower()).replace(" ", "")
    if not n or n in JUNK_EXACT or len(n) == 1:
        return "junk", None
    kind_end, kind = -1, None
    for k, aliases in KIND_ALIASES.items():
        end, alias = last_end(n, aliases)
        if end > kind_end or (end == kind_end and alias and len(alias) > len(kind or "")):
            kind_end, kind = end, k
    bev_end, bev = last_end(n, BEVERAGE_WORDS)
    if bev_end > kind_end and bev_end >= 0:
        return "beverage", bev
    if kind:
        return "kind", kind
    return ("bread", None) if "빵" in n else ("other", None)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", default=os.path.join(REPO_ROOT, ".env"))
    parser.add_argument("--top", type=int, default=25)
    args = parser.parse_args()
    rows = prod_csv(
        "SELECT mc.name AS bucket, m.normalized_name AS name, COUNT(*) AS n "
        "FROM menu m JOIN menu_category mc ON mc.id = m.menu_category_id "
        "WHERE m.deleted_at IS NULL AND mc.name IN ('빵','기타') GROUP BY 1, 2;", args.env)
    for bucket in ("빵", "기타"):
        sub = [r for r in rows if r["bucket"] == bucket]
        total = sum(int(r["n"]) for r in sub)
        by_class, by_kind, by_bev, remain = Counter(), Counter(), Counter(), Counter()
        for r in sub:
            cls, detail = classify(r["name"])
            n = int(r["n"])
            by_class[cls] += n
            if cls == "kind":
                by_kind[detail] += n
            elif cls == "beverage":
                by_bev[detail] += n
            else:
                remain[r["name"]] += n
        print(f"\n===== '{bucket}' 버킷: 메뉴 {total:,}건 · 이름 {len(sub):,}종 =====")
        for cls, label in (("kind", "kind 로 회복 가능"), ("beverage", "음료로 보임"), ("junk", "정크"), ("bread", "남음 — '빵' 포함"), ("other", "남음 — 종류 단어 없음")):
            print(f"  {label:18} {by_class[cls]:>8,}건  ({100 * by_class[cls] / max(total, 1):4.1f}%)")
        print(f"  --- 회복 종류 상위 {args.top} ---")
        for kind, n in by_kind.most_common(args.top):
            print(f"    {kind:8} {n:>7,}")
        print(f"  --- 음료 단어 상위 12 ---")
        print("    " + ", ".join(f"{w} {n:,}" for w, n in by_bev.most_common(12)))
        print(f"  --- 남는 이름 상위 {args.top} (enum 후보·정크 확인용) ---")
        for name, n in remain.most_common(args.top):
            print(f"    {name:24} {n:>6,}")


if __name__ == "__main__":
    main()
