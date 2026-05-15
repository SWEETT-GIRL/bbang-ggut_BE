# TODO — bbang-ggut BE

## 사전 설정

- [ ] `feat: SwaggerConfig Server 추가` — localhost:8080 하드코딩

---

## 이슈 목록

- [x] **#8** 이미지 처리
- [ ] **#5** 빵집을 구독한다 (빵알람)
- [ ] **#6** 빵집 구독을 해제한다
- [ ] **#7** 빵집 정보 수정을 제안한다
- [ ] **#9** StoreDetailResponse 개선
- [x] **#10** 가게 메뉴 목록 조회
- [x] **#11** 메뉴 상세 조회
- [x] **#12** 메뉴 추천 / 취소

---

## 상세

### #8 이미지 처리

**결정 사항**

- 업로드: Presigned URL (클라이언트 직접 S3 업로드), 도메인별 API에서 발급
- URL: CDN이 awsKey 기반으로 처리 — 서버는 awsKey 그대로 반환
- ImageController 없음 — 각 도메인 API에서 presigned URL 발급

---

### #9 StoreDetailResponse 개선

- [x] `feat: StoreDetailResponse 이미지에 id 포함` — `List<String>` → `List<ImageResponse>` (id + awsKey)

---

### #10 가게 메뉴 목록 조회

- `GET /stores/{id}/menus?filter=&category=`
- 응답: menuId, 메뉴명, 이미지 awsKey, 가격, 추천수
- 필터: 인기메뉴(추천수 내림차순) / 카테고리(MenuKind enum)

- [x] `feat: MenuRepository 메뉴 목록 쿼리 추가`
- [x] `feat: StoreMenuResponse DTO 추가`
- [x] `feat: MenuService 추가`
- [x] `feat: MenuController 추가` — `GET /stores/{id}/menus`

---

### #11 메뉴 상세 조회

- `GET /menus/{id}`
- 응답: 가게명, 메뉴명, 이미지 awsKey 목록, 가격, 설명, 추천수

- [x] `feat: MenuDetailResponse DTO 추가`
- [x] `feat: MenuService 메뉴 상세 조회 메서드 추가`
- [x] `feat: MenuController 메뉴 상세 엔드포인트 추가`

---

### #12 메뉴 추천 / 취소

- `POST /menus/{id}/recommend` — 중복 시 409
- `DELETE /menus/{id}/recommend` — 미추천 시 404
- 인증 필요

- [x] `feat: RecommendMenuRepository 추가`
- [x] `feat: MenuRecommendService 추가` — Menu.recommendCount 동기화
- [x] `feat: MenuController 추천 엔드포인트 추가`

---

### #5 빵집을 구독한다 (빵알람)

- `POST /stores/{id}/subscriptions` — 중복 시 409
- 인증 필요

### #6 빵집 구독을 해제한다

- `DELETE /stores/{id}/subscriptions` — 미구독 시 404
- 인증 필요

### #7 빵집 정보 수정을 제안한다

- `POST /stores/{id}/reports`
- requestType: `MOD_BAKERY_TIME` | `MOD_BAKERY_ADD` | `MOD_BAKERY_IMG` | `MOD_BREAD`

**세부 API**

- [ ] 영업시간 수정 요청 — 요일별 openTime / closeTime / isClosed
- [ ] 주소 수정 요청 — `GET /address/search?q=` (KakaoLocalClient 재활용) 후 선택값 전달
- [ ] 가게 이미지 수정 요청 — 삭제 imageId 배열 + 추가 awsKey 배열, presigned URL은 `GET /stores/{id}/reports/images/presigned-url`
- [ ] 메뉴 수정 요청 — 가격 변동 + 이미지 삭제 imageId 배열 + 이미지 추가 awsKey 배열, presigned URL은
  `GET /menus/{id}/reports/images/presigned-url`
