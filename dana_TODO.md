# 사용자 제보 API TODO

프론트의 `app/bakery/edit-request.tsx` 기준으로 필요한 사용자 제보 API 중 아직 남은 작업만 정리한다.

현재 백엔드는 `/api/v1` prefix를 쓰지 않고 `/stores`, `/alarm`, `/auth`처럼 도메인 루트로 라우팅하고 있으므로, 아래 API도 같은 규칙으로 잡는다.

카카오 주소 검색 API는 프론트에서 `src/services/kakao/kakaoLocalApi.ts`로 직접 처리 중이므로 백엔드 구현 범위에서 제외한다.

## 남은 정책/검증 TODO

- 제보 생성 API는 HTTP 201과 `ApiResponse.created(data)`를 사용한다.
- 새 이미지 추가 요청의 `awsKey`는 예상 prefix로 시작해야 한다.
- 삭제 이미지 ID가 하나라도 대상 가게/메뉴 소유가 아니면 전체 요청을 400으로 실패시킨다.

`awsKey` prefix:

```text
reports/stores/{storeId}/images/
reports/stores/{storeId}/menus/{menuId}/
```

## 선행 수정 필요

### 메뉴 삭제 대상 이미지 소유 검증 메서드 추가

```java
long countByIdInAndMenuId(List<Long> ids, Long menuId);
```

`MenuImageFileRepository`에 추가한다.

검증 기준:

- 요청한 삭제 이미지 ID 개수와 count 결과가 다르면 400.
- 이미지 ID가 비어 있으면 count 검증은 건너뛴다.

## 1. 사용자 제보: 메뉴 수정 요청

가격 변경, 사진 삭제, 사진 추가를 한 번에 접수한다.

```http
POST /menus/{menuId}/reports/edit
Authorization: Bearer {accessToken}
Content-Type: application/json
```

Request:

```json
{
  "price": 6500,
  "deleteImageIds": [301, 302],
  "addImages": [
    {
      "fileName": "menu-photo-1.jpg",
      "contentType": "image/jpeg",
      "awsKey": "reports/stores/7/menus/14/uuid"
    }
  ],
  "reason": "가격과 메뉴 사진이 현재 매장 정보와 다릅니다."
}
```

Response:

```json
{
  "success": true,
  "code": "COMMON201",
  "message": "리소스가 생성되었습니다.",
  "data": {
    "reportId": 4,
    "status": "PENDING"
  }
}
```

저장 방식:

- `StoreReport.member = member`
- `StoreReport.requestType = MOD_BREAD`
- `StoreReport.store = menu.store`
- `StoreReport.menu = menu`
- `StoreReport.message`에는 요청 본문 JSON 저장
- `addImages`는 `StoreReportImageFile`에 저장

검증:

- menu 존재 여부 확인.
- `price`, `deleteImageIds`, `addImages` 중 최소 하나 필수.
- `price`는 0보다 큰 정수.
- 삭제 이미지 ID는 모두 해당 menu의 이미지여야 한다.
- `addImages[].contentType`은 `MimeType.fromString()`으로 검증.
- `addImages[].awsKey`는 `reports/stores/{storeId}/menus/{menuId}/`로 시작해야 한다.

## 공통 보정 TODO

- `POST /stores/{storeId}/reports/images` 응답을 HTTP 201과 `ApiResponse.created(data)`로 변경.
- `POST /stores/{storeId}/reports/business-hours` 응답을 HTTP 201과 `ApiResponse.created(data)`로 변경.
- `POST /stores/{storeId}/reports/address` 응답을 HTTP 201과 `ApiResponse.created(data)`로 변경.
- 가게 사진 제보의 `addImages[].awsKey` prefix 검증 추가.
- 메뉴 수정 제보의 `addImages[].awsKey` prefix 검증 추가.

## 남은 API 목록

```http
POST /menus/{menuId}/reports/edit
```

## 구현 순서 추천

1. `MenuImageFileRepository`에 삭제 대상 소유 검증 메서드 추가.
2. 메뉴 수정 제보 요청 DTO 추가.
3. 메뉴 수정 제보 서비스 로직 추가.
4. `MenuReportController`에 `POST /menus/{menuId}/reports/edit` 추가.
5. 제보 생성 API 응답을 HTTP 201과 `ApiResponse.created(data)`로 보정.
6. 가게/메뉴 이미지 제보에 `awsKey` prefix 검증 추가.
7. 서비스 단위 테스트 추가.

## 테스트 시나리오

- 가게 사진 제보: `awsKey` prefix가 다르면 400.
- 메뉴 수정 제보: 변경 내용이 모두 비어 있으면 400.
- 메뉴 수정 제보: 삭제 이미지가 해당 menu 소유가 아니면 400.
- 메뉴 수정 제보: `awsKey` prefix가 다르면 400.
- 메뉴 수정 제보: 허용하지 않는 MIME 타입이면 400.
- 정상 제보 생성 시 HTTP 201과 `ApiResponse.created(data)`를 반환.
- 정상 제보 생성 시 `StoreReport.status = PENDING`.
- 정상 제보 생성 시 `StoreReport.member`가 로그인 사용자로 저장됨.

## GitHub Issue 분리 추천

### Issue 4. 메뉴 제보 API + 테스트 보강

범위:

- `POST /menus/{menuId}/reports/edit`
- 가격 변경 요청 처리.
- 메뉴 사진 삭제 요청 처리.
- 메뉴 사진 추가 요청 처리.
- 삭제 이미지가 해당 menu 소유인지 검증.
- 추가 이미지 `awsKey` prefix 검증.
- 가게 사진 제보 `awsKey` prefix 검증 보강.
- 제보 생성 API HTTP 201 응답 보정.
- 가게 사진 제보 정상/실패 테스트.
- 영업시간 제보 정상/실패 테스트.
- 주소 제보 정상/실패 테스트.
- 메뉴 수정 제보 정상/실패 테스트.

주요 실패 케이스:

- 대상 menu 없음.
- 요청 내용이 모두 비어 있음.
- 삭제 이미지가 대상 menu 소유가 아님.
- `awsKey` prefix 불일치.
- 허용하지 않는 MIME 타입.

완료 기준:

- 가격 변경, 사진 삭제, 사진 추가 내역을 하나의 메뉴 수정 제보로 저장할 수 있다.
- 정상 생성 시 HTTP 201과 `ApiResponse.created(data)`를 반환한다.
- 메뉴 수정 제보는 `StoreReport.requestType = MOD_BREAD`로 저장된다.
- 핵심 검증 로직이 서비스 단위 테스트로 보호된다.
- 정상 생성 시 `StoreReport.status = PENDING`과 `StoreReport.member` 저장이 확인된다.
