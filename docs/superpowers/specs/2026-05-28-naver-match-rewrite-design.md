# Naver 매칭/메뉴 파서 리라이트 (Items 2-4)

**날짜:** 2026-05-28
**브랜치:** `feat-kakao-bulk-import`
**대상 파일:** `scripts/naver_menu_scraper.py` (신규 모듈 `scripts/naver_match.py` 분리)

## 배경

`scripts/naver_menu_scraper.py` 의 현재 매칭/파싱에 다음 약점이 누적되어 있다.

1. **매칭 게이트가 느슨함** — `is_reliable_match` 가 "이름 일치 AND (동 겹침 OR 100m 이내)" boolean 조건. 동만 겹쳐도 통과해서, 같은 동의 동명 빵집/카페가 오매칭됨.
2. **후보 평가가 binary** — 첫 번째 통과 후보를 그대로 채택. 더 가까운/이름이 더 정확한 두 번째 후보가 있어도 비교 못 함.
3. **메뉴 파서가 위치기반** — JS 안에서 `nonPrice[0]/[1]` 위치로 name/desc 추출. `대표/BEST/NEW` 같은 라벨이 nonPrice 에 섞이면 그게 name 으로 들어가 깨짐. `시가/문의/3,500원~` 도 가격으로 인식 안 됨.

이 셋을 한 번에 리라이트한다. 매칭과 파싱 모두 순수 함수로 분리해 pytest 로 검증 가능하게 만든다.

## 변경 범위

### 파일 구조

| 파일 | 역할 | 신규/수정 |
|---|---|---|
| `scripts/naver_menu_scraper.py` | Playwright/HTTP 오케스트레이션만. 매칭·파싱은 `naver_match` 호출. | 수정 |
| `scripts/naver_match.py` | **순수 함수** — 이름 정규화, 거리 계산, 스코어러, 메뉴 텍스트 분류기. I/O 없음. | 신규 |
| `scripts/tests/test_naver_match.py` | pytest 단위 테스트. | 신규 |
| `scripts/tests/__init__.py` | 빈 파일. | 신규 |
| `scripts/requirements-dev.txt` | `pytest`. | 신규 |

스크래퍼에서 옮길 함수: `normalize_name`, `name_matches`, `strip_company_suffix`, `strip_branch_suffix`, `strip_generic_branch_suffix`, `strip_category_suffix`, `haversine_m`, `extract_district_tokens`, `extract_best_district`, `_extract_road_name`, `is_drink`, `guess_category`. 모두 I/O 없는 순수 함수.

### 매칭 스코어러 (items 2 + 3)

#### 점수표 (총점 ~100, 음수 가능)

| 항목 | 점수 | 조건 |
|---|---|---|
| 이름 exact | 40 | 정규화 후 완전 일치 |
| 이름 prefix | 25 | `len(shorter) ≥ 2`, ratio ≥ 0.4, 한쪽이 다른쪽 prefix |
| 이름 mid-substring | 15 | `len(shorter) ≥ 3`, ratio ≥ 0.6 |
| 거리 ≤ 30m | 30 | 좌표 양쪽 다 있을 때 |
| 거리 ≤ 60m | 20 | |
| 거리 ≤ 80m | 15 | |
| 거리 ≤ 100m | 10 | |
| 거리 > 100m | -10 | 좌표 둘 다 있는데 멀면 감점 |
| 도로명 + 건물번호 일치 | 15 | 주소 토큰화 후 `endswith("로"|"길")` 토큰 + 그 다음 숫자 토큰(`r"^\d+(-\d+)?$"`) 둘 다 양쪽에서 일치 |
| 행정동(동/읍/면) 일치 | 5 | |
| 폐업 시그널 | -100 | `category` 또는 `name` 에 `'폐업'`/`'(폐업)'` 또는 `CLOSED` |

이름 점수는 exact/prefix/mid 중 가장 높은 하나만 적용 (mutually exclusive).

#### 함수 시그니처

```python
def score_candidate(kakao: dict, naver: dict) -> dict:
    """Return {'total': int, 'breakdown': {'name': int, 'distance': int, ...},
              'distance_m': Optional[float], 'closed': bool}."""

def is_chain_like(name: str) -> bool:
    """Kakao 가게 이름이 체인성/지점 패턴인지 — 가게 데이터의 이름 기반."""

def select_match(
    kakao: dict,
    candidates: list[dict],
) -> tuple[Optional[dict], list[tuple[dict, dict]], str]:
    """
    Return (chosen_or_None, scored_candidates, reason).
    scored_candidates: list of (cand, score_dict), score 내림차순 정렬.
    chosen 이 None 이면 reason 에 이유.
    """
```

#### 자동 채택 게이트 (점수 외 추가 조건)

게이트가 다 통과해야 매칭. 통과 못 하면 호출자가 `save_candidates + mark_failed`.

```
A. 체인성 이름이면 (CHAIN_HINTS 매치 OR _BRANCH_PATTERN 매치):
   거리 ≤ 30m AND (도로명 번호 일치 OR 이름 exact)

B. 체인성 아니면서 좌표 양쪽 다 있으면:
   거리 ≤ 60m
   (이름 exact + 동 일치면 80m 까지 허용)

C. 좌표 한쪽이라도 없으면:
   도로명 + 건물번호 일치 필수

D. 후보 ≥ 2 개일 때 (A/B/C 통과 후 추가):
   top1.total ≥ 60 AND (top1 − top2) ≥ 15
```

#### 체인성 판정

```python
CHAIN_HINTS = (...)  # FranchiseName.java 와 동기화한 한글 리스트
                     # (현 시점 ~108개, 동기화는 수동 — 변경 빈도 낮음)
_BRANCH_PATTERN = re.compile(r"\S+\s+\S*(?:\d+호점|점|매장|직영점)$")

def is_chain_like(name: str) -> bool:
    return any(name.startswith(h) for h in CHAIN_HINTS) or bool(_BRANCH_PATTERN.search(name))
```

> Kakao 임포트 시점에 `FranchiseName.matches()` 로 메이저 프랜차이즈는 이미 필터됨. 그래서 DB store 는 대개 비-프랜차이즈. `CHAIN_HINTS` 가 잡는 건 누락된 분점/로컬 체인 정도.

#### 폐업 감지 (보수적)

네이버 검색 응답에 명시적 closed 플래그가 없으므로:
- `naver['category']` 에 `'폐업'`
- `naver['name']` 에 `'폐업'` 또는 `'(폐업)'`

발견 시 점수 -100 (다른 가산을 모두 무력화). 누락은 다음 사이클에 사람이 처리.

### 메뉴 파서 분류기 (item 4)

#### Python 분류기 시그니처

```python
LABEL_TOKENS = {"대표", "BEST", "NEW", "추천", "신메뉴", "인기",
                "소", "중", "대", "S", "M", "L"}
PRICE_LIKE_NULL = {"시가", "변동", "문의"}
PRICE_LIKE_NULL_PREFIXES = ("시가",)  # '시가/계절', '시가 (변동)'

_PRICE_NUMERIC_RE = re.compile(r"^([\d,]+)\s*원?(?:\s*[~\(].*)?$")

def classify_menu_texts(uniq: list[str]) -> dict:
    """Return {'name': str, 'price': Optional[int], 'desc': Optional[str]}."""
```

처리 순서:
1. trim. 빈 문자열 skip.
2. 라벨 (case-insensitive exact 일치) → skip.
3. price-like null (`시가/변동/문의` 또는 `시가` prefix) → skip. `price` 그대로 None.
4. 가격 regex 매치 → 첫 매치를 int 로 `price`.
5. 나머지 텍스트 → 첫 개를 `name`, 그 외는 `desc_parts`.
6. `desc = " · ".join(desc_parts)`. 256 자 이상이면 `desc[:254] + "…"`.
7. `desc` 빈 문자열이면 `None`.

#### JS evaluate 변경

기존 위치기반 분류 제거, raw uniq 반환.

```js
const items = [];
document.querySelectorAll('li').forEach(li => {
    const img = li.querySelector('img[src*="pstatic"]');
    const src = img ? (img.getAttribute('src') || '') : '';
    const texts = [];
    li.querySelectorAll('span, em, strong, p, div').forEach(el => {
        if (el.childNodes.length === 1 && el.childNodes[0].nodeType === 3) {
            const t = el.textContent.trim();
            if (t) texts.push(t);  // length>1 필터 제거 — Python 에서 라벨 처리
        }
    });
    const uniq = [...new Set(texts)];
    // 메뉴 li 시그널 — 가격성 텍스트(숫자+원, 시가/변동/문의) 또는 pstatic 이미지
    const hasPriceLike = uniq.some(t => /\d.*원|시가|변동|문의/.test(t));
    if (uniq.length && (hasPriceLike || src)) items.push({ uniq, src });
});
return items;
```

리뷰 li 오인 방지는 기존 `/menu/` URL 가드로 충분. `hasPriceLike` 가 추가 가드.

#### Python 통합

```python
parsed = classify_menu_texts(item["uniq"])
if not parsed["name"]:
    continue
menu_map[parsed["name"]] = {
    "src": item["src"],
    "desc": parsed["desc"],
    "price": parsed["price"],
}
```

## 테스트

`scripts/tests/test_naver_match.py` — pytest. 픽스처는 dict literal.

### 매칭 스코어러 테스트 (~12 케이스)

- 이름 exact + 거리 20m → 채택, total ~85
- 이름 exact + 거리 200m → 게이트 fail (60m 초과)
- 이름 prefix + 거리 50m → 채택
- 체인성 ('테라로사 강남점') + 거리 25m + 도로명 번호 일치 → 채택
- 체인성 + 거리 50m → 게이트 fail (30m 초과)
- 폐업 시그널 → 점수 -100, top1 60 미달
- 후보 2개, top1=70 top2=60 → tie-break fail (gap 10 < 15)
- 후보 2개, top1=85 top2=55 → 채택
- 좌표 없음 + 도로명 번호 일치 → 채택
- 좌표 없음 + 도로명 번호 불일치 → 게이트 fail
- 이름 exact + 동 일치 + 거리 75m (비체인) → 채택 (80m 룰)
- 빈 후보 list → None

### 메뉴 파서 테스트 (~8 케이스)

- `['대표', '소금빵', '3,500원']` → name='소금빵', price=3500, desc=None
- `['NEW', '소금빵', '3,500원~', '버터향']` → name='소금빵', price=3500, desc='버터향'
- `['시가', '한치']` → name='한치', price=None, desc=None
- `['BEST', '치즈케이크', '4500원 (1인분)', '바닐라빈', '진한 풍미']` → name='치즈케이크', price=4500, desc='바닐라빈 · 진한 풍미'
- `['소', '대', '아메리카노', '3,000원']` → name='아메리카노', price=3000, desc=None
- 256자 desc → 254자 + '…'
- 빈 list → name='', price=None, desc=None
- `['추천', '문의', '한정메뉴']` → name='한정메뉴', price=None, desc=None

### 실행

```bash
cd scripts && python -m pytest tests/ -v
```

CI 연동은 별도 사이클. 로컬 검증용.

## 마이그레이션·롤백

- 기존 동작과 매칭 결과가 다를 수 있음 (의도된 변경). `--dry-run --verbose` 로 베이스라인 비교 후 머지 권장.
- 새 매칭에서 fail 된 가게는 `save_candidates` 로 후보 보존되므로 어드민이 수동 선택 가능 — 데이터 손실 없음.
- 롤백: 이 PR revert. `naver_match.py` 삭제 + `naver_menu_scraper.py` 원복.

## YAGNI

다음은 의도적으로 미포함.
- pytest CI 연동 (운영 사이클로 검증 후 결정)
- `naver_match.py` 의 점수 노브 외부 설정화 (당장 변경 빈도 낮음, 코드 상수로 충분)
- 폐업 감지를 위한 추가 네이버 API 호출 (지금 정확도로도 충분, 누락은 후속 사이클)
- `FranchiseName.java` 와 `CHAIN_HINTS` 자동 동기화 도구 (수동, 변경 빈도 낮음)
