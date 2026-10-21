# 네이버 플레이스 응답(__APOLLO_STATE__) 구조

> 작성 2026-08-02 · **관찰 표본 1,000곳 실측 기반** (place_snapshot 전수 분석)
> 워커가 `m.place.naver.com/restaurant/{place_id}/home` 에서 추출해 제출하는 payload 의 구조 문서.
> 파서(`NaverPlaceParser`)의 필드 경로 근거이며, 구조가 바뀌면 이 문서와 파서를 함께 갱신한다.

---

## 1. 기본 성질 — Apollo 정규화 캐시

payload 는 Apollo Client 의 **정규화 캐시**다. 문서 트리가 아니라 **엔티티들의 평면 사전**이며, 다음 규칙을 모르면 파싱이 틀린다.

| 규칙 | 내용 |
|---|---|
| 키 형식 | `"타입명:id"` (예: `PlaceDetailBase:38427733`, `Menu:38427733_0`) + 특수 키 `ROOT_QUERY` |
| 참조 | 엔티티 간 연결은 `{"__ref": "타입명:id"}` — **반드시 해소해야 실값이 나온다** (미해소가 리서치 2.3 has_hours 유실의 원인) |
| 동적 키 | `ROOT_QUERY` 아래 필드 키에 GraphQL 인자가 그대로 박힌다: `placeDetail({"input":{"id":"...","deviceType":"mobile",...}})` — **접두 매칭**(`placeDetail(`)으로 찾아야 한다 |
| 타입 판별 | 모든 엔티티에 `__typename` 필드 |
| 인코딩 | HTTP 응답이 `Content-Type` 에 charset 미표기 — **UTF-8 명시 디코딩 필수** (워커에서 처리, requests 기본값은 Latin-1 오디코딩) |

## 2. 전체 지도 — 어디에 뭐가 사는가

```
__APOLLO_STATE__
├── ROOT_QUERY
│   └── placeDetail({"input":{...}})          ← 접두 매칭으로 찾기
│       ├── newBusinessHours({"format":"restaurant"})[0]
│       │   ├── businessHours[]               ← 요일별 영업시간 (실데이터, 74.3% 보유)
│       │   └── businessStatusDescription     ← 당일 영업 상태 (폐업 아님!)
│       └── shopWindow.businessHours          ← 관찰 범위 내 전부 null (죽은 경로)
├── PlaceDetailBase:{id}                      ← 가게 기본 정보 (97.6% 보유)
├── Menu:{id}_{n}                             ← 네이버 등록 메뉴 (가게당 0~n개)
├── PlaceDetail_BaeminMenu:{id}               ← 배민 배달 메뉴 (배민 연동 가게만, 5.8%)
├── PlaceDetail_BaeminMenuGroup:{...}         ← 배민 메뉴 그룹 (그룹명 = 음료 판별 입력)
└── (기타: 리뷰·사진·교통·파노라마 등 — 현재 미사용)
```

## 3. 실측 커버리지 (표본 1,000곳, 2026-08-01 수집)

| 항목 | 보유 가게 | 비율 | 비고 |
|---|---:|---:|---|
| `PlaceDetailBase` (가게명) | 976 | 97.6% | **24곳은 base 자체가 없음** — 페이지 구조 상이 추정, 1단계 눈검사 대상 |
| 영업시간 (`businessHours`) | 743 | 74.3% | 운영 DB 커버리지 0.5% 대비 **148배** — 리서치 TODO #8 해소 실증 |
| 영업상태 (`businessStatusDescription`) | 743 | 74.3% | 영업시간과 동거 |
| 메뉴 1개 이상 (`Menu`) | 564 | 56.4% | 표본이 메뉴 0개 가게 80% 편중임을 감안 — 백필 기회가 실재한다는 뜻 |
| 인스타 링크 | 451 | 45.1% | 리서치 2.4 의 60.8%(무편중 표본)와 정합 — 메뉴 0개 편중 표본이라 낮게 나옴 |
| 전화번호 (`phone`) | 374 | 37.4% | `virtualPhone`(안심번호) 별도 존재 |
| 배민 메뉴 | 58 | 5.8% | 배민 연동 가게에만 |

## 4. 주요 엔티티 상세

### 4.1 `PlaceDetailBase:{id}` — 가게 기본 정보

실측 주요 필드 (전체 39개 중 사용·관심 필드만):

| 필드 | 타입 | 사용 |
|---|---|---|
| `name` | String | ✅ ParsedPlace.name |
| `phone` | String\|null | ✅ ParsedPlace.phone |
| `virtualPhone` | String\|null | 안심번호 — 매칭 트랙에서 전화 대조 시 제외 대상 |
| `hasMobilePhoneNumber` | Boolean | true 여도 **번호 자체는 payload 에 없음** ("전화번호 보기" 클릭 시 별도 요청) — 휴대전화는 추출 불가 한계 (눈검사 오크레페 사례) |
| `address` / `roadAddress` / `road` | String | 매칭 트랙 주소 대조 후보 |
| `coordinate` | {x, y} | 매칭 트랙 좌표 대조 후보 |
| `category` / `categoryCode` / `categoryCodeList` | String | 업종 (베이커리/카페 구분) |
| `conveniences`, `paymentInfo`, `microReviews` | — | 미사용 |
| `openingHours` | **항상 null** | ⚠️ 죽은 필드 — 영업시간은 §4.2 로 |
| `visitorReviewsScore` / `visitorReviewsTotal` | — | 미사용 (추후 인기 신호 후보) |

### 4.2 영업시간·영업상태 — `ROOT_QUERY` 아래 (⚠️ base 가 아님)

경로: `ROOT_QUERY → placeDetail({...}) → newBusinessHours({"format":"restaurant"}) → [0]`

```jsonc
{
  "businessHours": [                    // 요일별 배열
    {
      "day": "월",                      // 임시휴무 주간엔 "토(8/1)" 처럼 날짜 병기
      "businessHours": {"start": "12:00", "end": "20:00"},   // 휴무일이면 null
      "breakHours": null,
      "description": "휴무",            // 휴무 사유 등
      "lastOrderTimes": null,
      "__typename": "WorkingHoursInfo"
    }
  ],
  "businessStatusDescription": {
    "status": "영업 종료",              // 아래 값 분포 참조
    "description": "12:00에 영업 시작",
    "__typename": "BusinessStatusDescription"
  }
}
```

**`status` 값 분포 (743곳, 심야 수집 기준):** 영업 종료 566 · 오늘 휴무 127 · 24시간 영업 17 · 영업 중 15 · **운영 종료 7** · 곧 영업 종료 5 · 기타 6

> ⚠️ **이 필드는 "지금 문 열려 있나"(당일 상태)이지 폐업 여부가 아니다** (리서치 2.3 — 400건 실측에서 폐업 표기 0건, Google 의 "hours 로 closure 판단 금지" 원칙과 동일).
> `운영 종료`(7곳)도 **폐업이 아님을 확인** (08-11 실측 — desc 가 전부 "10:00에 운영 시작"류 다음 영업 안내).
> "영업"이 아니라 "운영"인 것은 업종 템플릿 차이로 보임 — 7곳이 스튜디오·컨설팅·팝업스토어류라서,
> 오히려 **비(非)판매 업종(서비스 제외 후보)의 간접 신호**로 쓸 여지가 있다.

### 4.3 `Menu:{id}_{n}` — 네이버 등록 메뉴 (필드 12개 전수 동일)

| 필드 | 예시·설명 | 사용 |
|---|---|---|
| `name` | "소금빵" | ✅ |
| `price` | `"3500"` — **문자열**. 빈 값·"변동" 가능성 → 정규화는 판정층 | ✅ |
| `description` | 설명 (null 가능) | ✅ |
| `images` | 이미지 URL 배열 — **`ldb-phinf.pstatic.net` 원본 URL, 리사이즈 파라미터 없음** (8,691건 전수 확인). 본 앱 스크래퍼의 DOM 경로는 `search.pstatic.net` 프록시 `type=f320_320`(320×320 정사각 축소, 실측 382KB→42KB)라 **Apollo 경로가 원본 화질**이다. URL 날짜 접두는 최초 수집 누락 판별에 쓰였음(리서치 2.5) | ✅ |
| `recommend` | 대표/추천 여부 | 미사용 |
| `priority`, `index`, `change`, `priceType`, `nameForBlogReview`, `id` | 정렬·메타 | 미사용 |

### 4.4 배민 메뉴 — `PlaceDetail_BaeminMenu` + `PlaceDetail_BaeminMenuGroup`

배민 연동 가게(5.8%)에만 존재. **추가 요청 0회로 배달 메뉴가 덤으로 온다.**

```jsonc
// PlaceDetail_BaeminMenu:{id}
{
  "name": "[인기폭발]아메리카노",       // 프로모 태그 붙음 — 정규화는 판정층
  "price": "1900",                      // 문자열
  "desc": "산미가 없고 …",              // ⚠️ 필드명이 description 이 아니라 desc
  "images": ["http://imagefarm.baemin.com/..."],
  "menuId": "13939776_121620116",
  "orderType": "DELIVERY",
  "isRepresentative": true
}
// PlaceDetail_BaeminMenuGroup:{id}
{
  "name": "대표메뉴",                   // 그룹명
  "menus": [{"__ref": "PlaceDetail_BaeminMenu:..."}]
}
```

**그룹명 실측 분포 (58곳):** 대표메뉴 58 · COFFEE/커피/Coffee 31 · 에이드/ADE/Ade 18 · TEA/티 10 · 스무디 6 · 라떼/LATTE 8 · 디저트/DESSERT 13 · 쿠키 5 · 케이크 5 · 스콘 5 · BAKERY 4 · 휘낭시에 3 · 주스 3 …

> 그룹명이 **음료 제외 필터의 1차 판별 입력**이다 (설계서 5장) — COFFEE·에이드·TEA·스무디·라떼·주스 등 음료 그룹이 명시적으로 갈라져 있어 키워드 필터가 실효적임이 확인됨. 한 메뉴가 "대표메뉴"와 실그룹에 중복 소속되면 **실그룹명이 이긴다** (파서 규칙).

### 4.5 인스타 핸들

`PlaceDetailBase.homepages` 안 URL 로 온다. 위치 가정 없이 payload 전체에서 `instagram.com/{handle}` 패턴 추출 (`p`·`reel`·`explore` 경로 제외).

## 5. ParsedPlace 매핑 요약

| ParsedPlace 필드 | 원본 경로 |
|---|---|
| `name` / `phone` | `PlaceDetailBase.name` / `.phone` |
| `businessStatusDescription` | `ROOT_QUERY.placeDetail(…).newBusinessHours(…)[0].businessStatusDescription.status` |
| `businessHoursRaw` | 같은 노드 `[0].businessHours` (JSON 원문 보존 — 구조화는 2단계) |
| `instagramHandle` | payload 전체 정규식 |
| `menus[]` | `__typename == "Menu"` 엔티티 전수 (groupName=null) |
| `baeminMenus[]` | `__typename == "PlaceDetail_BaeminMenu"` + 그룹 역참조로 groupName 부착 |

## 6. 함정 목록 (전부 실측으로 확인된 것)

1. **charset 미표기** → UTF-8 명시 디코딩 없이는 한글 전체 mojibake (워커에서 해결)
2. **`__ref` 참조** → 해소 없이는 영업상태 등 유실 (파서 resolveDeep)
3. **ROOT_QUERY 동적 키** → 정확 일치가 아니라 접두 매칭 필요
4. **`PlaceDetailBase.openingHours` 는 죽은 필드** (전부 null) — 진짜 영업시간은 ROOT_QUERY 경로
5. **배민 desc ≠ description** — 필드명 다름
6. **배민 "대표메뉴" 그룹 중복 소속** — 실그룹명 우선 규칙 필요
7. **`businessStatusDescription` 은 당일 상태** — 폐업 판정에 쓰지 말 것 (`운영 종료` 값만 추적 관찰)
8. **base 없는 가게 24곳(2.4%)** — 파서가 name null 을 반환. **눈검사 최종(08-11): 표본 8곳 전수 폐업**
   (백화점 입점매장으로 보였던 노티드·유어스베이크샵도 실제로는 입점 철수). → **base 부재는 강한 폐업
   후보 신호**로 승격. 단 표본 8이므로 단독 자동 판정은 여전히 금지(리서치 1.5) — 매칭 검색 부재와
   결합해 폐업 후보 리스트에 적재하고, 확정은 2단계 proposal + 4단계 공공데이터 교차 검증으로
