# 사용자 제보 API TODO

프론트의 `app/bakery/edit-request.tsx` 기준으로 필요한 사용자 제보 API 명세다.

현재 백엔드는 `/api/v1` prefix를 쓰지 않고 `/stores`, `/alarm`, `/auth`처럼 도메인 루트로 라우팅하고 있으므로, 아래 API도 같은 규칙으로 잡는다.

카카오 주소 검색 API는 프론트에서 `src/services/kakao/kakaoLocalApi.ts`로 직접 처리 중이므로 백엔드 구현 범위에서 제외한다.

## 확정 정책

- 조회 응답의 기존 이미지는 `id`와 `awsKey`를 함께 내려준다.
- 실제 이미지 업로드는 presigned PUT URL로 프론트가 S3에 직접 업로드한다.
- 기존 이미지 삭제 요청은 이미지 `id`로 받는다.
- 새 이미지 추가 요청은 presigned URL 발급 때 받은 `awsKey`로 받는다.
- `url` 필드는 실제 접근 URL에만 사용하고, S3 object key는 `awsKey`로 명명한다.
- 제보 생성 API는 HTTP 201과 `ApiResponse.created(data)`를 사용한다.
- presigned URL 발급 API는 HTTP 200과 `ApiResponse.ok(data)`를 사용한다.
- 메뉴 사진 제보 API와 메뉴 수정 제보 API는 중복되므로 `POST /menus/{menuId}/reports/edit` 하나로 합친다.

## 현재 백엔드 구현 상태

- `StoreController`: `/stores` 기반 조회 API만 있음.
- `StoreService`: 상세, 검색, 근처, 인기, 구독 가게 조회 구현.
- `StoreReport`: 제보 저장 엔티티 존재.
- `StoreReportImageFile`: 제보 이미지 저장 엔티티 존재.
- `StoreReportType`: `MOD_BAKERY_TIME`, `MOD_BAKERY_ADDRESS`, `MOD_BAKERY_IMG`, `MOD_BREAD` 존재.
- `S3Service.generatePresignedUrl(path, mimeTypeStr)`: presigned PUT URL 발급 로직 존재.
- `S3PathGenerator`: `getReportStoreImagePath(storeId)`, `getReportMenuPath(storeId, menuId)` 존재.
- `ApiResponse.created(data)`: 생성 응답 포맷 존재.

## 선행 수정 필요

### 1. `StoreReport.message` 길이 변경

현재 `StoreReport.message`는 `length = 255`라서 JSON 요청 본문을 저장하기에 부족하다.

```java
@Column(nullable = false, columnDefinition = "TEXT")
private String message;
```

### 2. Repository 추가

```java
public interface StoreReportRepository extends JpaRepository<StoreReport, Long> {
}
```

```java
public interface StoreReportImageFileRepository extends JpaRepository<StoreReportImageFile, Long> {
}
```

### 3. 삭제 대상 이미지 소유 검증 메서드 추가

```java
long countByIdInAndStoreId(List<Long> ids, Long storeId);
```

`StoreImageFileRepository`에 추가한다.

```java
long countByIdInAndMenuId(List<Long> ids, Long menuId);
```

`MenuImageFileRepository`에 추가한다.

검증 기준:

- 요청한 삭제 이미지 ID 개수와 count 결과가 다르면 400.
- 이미지 ID가 비어 있으면 count 검증은 건너뛴다.

### 4. 상세 조회 이미지 응답 변경

기존:

```java
List<String> imageUrls
```

변경:

```java
List<ImageResponse> images
```

```java
public record ImageResponse(
    Long id,
    String awsKey
) {}
```

`imageUrls`는 제거하고 `images`만 사용한다.

### 5. 메뉴 이미지 ID 제공 위치

메뉴 이미지 ID는 `StoreDetailResponse`에 메뉴 목록과 함께 포함하는 방향으로 잡는다. 프론트의 빵집 수정 화면에서 가게 정보와 메뉴 정보를 함께 다루기 때문이다.

권장 응답 구조:

```java
public record StoreDetailResponse(
    Long id,
    String name,
    String address,
    List<BusinessHourResponse> businessHours,
    String phone,
    List<ImageResponse> images,
    List<MenuResponse> menus
) {}
```

```java
public record MenuResponse(
    Long id,
    String name,
    Integer price,
    List<ImageResponse> images
) {}
```

## 공통 DTO

### 공통 제보 응답

```java
public record StoreReportResponse(
    Long reportId,
    StoreReportStatus status
) {}
```

생성 응답:

```json
{
  "success": true,
  "code": "COMMON201",
  "message": "리소스가 생성되었습니다.",
  "data": {
    "reportId": 1,
    "status": "PENDING"
  }
}
```

### Presigned 요청

```java
public record PresignedUrlRequest(
    @NotEmpty
    @Size(max = 5)
    List<PresignedFileRequest> files
) {}
```

```java
public record PresignedFileRequest(
    @NotBlank String fileName,
    @NotBlank String contentType
) {}
```

### Presigned 응답

`url` 대신 `uploadUrl`을 사용한다. 이 URL은 파일 표시용 URL이 아니라 S3 PUT 업로드용 URL이다.

```java
public record PresignedUrlsResponse(
    List<PresignedUploadResponse> uploads
) {}
```

```java
public record PresignedUploadResponse(
    String fileName,
    String contentType,
    String uploadUrl,
    String awsKey
) {}
```

### 업로드 완료 이미지 요청

```java
public record ReportImageRequest(
    @NotBlank String fileName,
    @NotBlank String contentType,
    @NotBlank String awsKey
) {}
```

저장 시:

- `fileName` -> `StoreReportImageFile.name`
- `contentType` -> `MimeType.fromString(contentType)`
- `awsKey` -> `StoreReportImageFile.awsKey`

## 공통 검증 규칙

- 모든 제보 생성 API는 `@AuthenticationPrincipal Long memberId`를 받고 `Member`를 조회해 `StoreReport.member`에 저장한다.
- `StoreReport.message`에는 `ObjectMapper.writeValueAsString(request)` 결과를 저장한다.
- 허용 MIME 타입은 현재 `MimeType` enum을 그대로 사용한다.
- presigned URL 발급은 요청당 최대 5장으로 제한한다.
- `awsKey`는 예상 prefix로 시작해야 한다.
- 삭제 이미지 ID가 하나라도 대상 가게/메뉴 소유가 아니면 전체 요청을 400으로 실패시킨다.

`awsKey` prefix:

```text
reports/stores/{storeId}/images/
reports/stores/{storeId}/menus/{menuId}/
```

## 1. 사용자 제보: 가게 사진 수정 요청

기존 사진 삭제와 새 사진 추가를 한 번에 접수한다.

```http
POST /stores/{storeId}/reports/images
Authorization: Bearer {accessToken}
Content-Type: application/json
```

Request:

```json
{
  "deleteImageIds": [101, 102],
  "deleteReason": "가게와 관련 없는 사진입니다.",
  "addImages": [
    {
      "fileName": "store-photo-1.jpg",
      "contentType": "image/jpeg",
      "awsKey": "reports/stores/7/images/uuid"
    }
  ],
  "addReason": "최근 방문해서 찍은 외관 사진입니다."
}
```

Response:

```json
{
  "success": true,
  "code": "COMMON201",
  "message": "리소스가 생성되었습니다.",
  "data": {
    "reportId": 1,
    "status": "PENDING"
  }
}
```

저장 방식:

- `StoreReport.member = member`
- `StoreReport.requestType = MOD_BAKERY_IMG`
- `StoreReport.store = store`
- `StoreReport.menu = null`
- `StoreReport.message`에는 요청 본문 JSON 저장
- `addImages`는 `StoreReportImageFile`에 저장

검증:

- store 존재 여부 확인.
- `deleteImageIds`와 `addImages` 둘 다 비어 있으면 400.
- `deleteImageIds`가 모두 해당 store의 이미지인지 확인.
- `addImages[].contentType`은 `MimeType.fromString()`으로 검증.
- `addImages[].awsKey`는 `reports/stores/{storeId}/images/`로 시작해야 한다.

## 2. 사용자 제보: 가게 영업시간 변경

```http
POST /stores/{storeId}/reports/business-hours
Authorization: Bearer {accessToken}
Content-Type: application/json
```

Request:

```json
{
  "hours": [
    {
      "dayOfWeek": "MONDAY",
      "openTime": "10:30",
      "closeTime": "20:30",
      "closed": false
    },
    {
      "dayOfWeek": "TUESDAY",
      "openTime": null,
      "closeTime": null,
      "closed": true
    }
  ],
  "reason": "화요일은 정기 휴무로 변경되었습니다."
}
```

Response:

```json
{
  "success": true,
  "code": "COMMON201",
  "message": "리소스가 생성되었습니다.",
  "data": {
    "reportId": 2,
    "status": "PENDING"
  }
}
```

저장 방식:

- `StoreReport.member = member`
- `StoreReport.requestType = MOD_BAKERY_TIME`
- `StoreReport.store = store`
- `StoreReport.menu = null`
- `StoreReport.message`에는 요청 본문 JSON 저장

검증:

- store 존재 여부 확인.
- `hours` 최소 1개.
- `dayOfWeek`: `java.time.DayOfWeek` 사용.
- `closed`: `Boolean`과 `@NotNull` 사용.
- `closed = true`면 `openTime`, `closeTime`은 null 허용.
- `closed = false`면 `openTime`, `closeTime` 필수.
- `closed = false`면 `openTime < closeTime` 검증.
- 시간 형식은 `HH:mm`.

## 3. 사용자 제보: 주소 수정 요청

카카오 주소 검색은 제외하고, 프론트에서 선택한 주소를 제보로 접수한다.

```http
POST /stores/{storeId}/reports/address
Authorization: Bearer {accessToken}
Content-Type: application/json
```

Request:

```json
{
  "address": "서울 성동구 연무장길 37-14",
  "roadAddress": "서울 성동구 연무장길 37-14",
  "placeName": "하츠 베이커리",
  "latitude": 37.544,
  "longitude": 127.056,
  "reason": "기존 주소가 잘못 등록되어 있습니다."
}
```

Response:

```json
{
  "success": true,
  "code": "COMMON201",
  "message": "리소스가 생성되었습니다.",
  "data": {
    "reportId": 3,
    "status": "PENDING"
  }
}
```

저장 방식:

- `StoreReport.member = member`
- `StoreReport.requestType = MOD_BAKERY_ADDRESS`
- `StoreReport.store = store`
- `StoreReport.menu = null`
- `StoreReport.message`에는 요청 본문 JSON 저장

검증:

- store 존재 여부 확인.
- `address`, `latitude`, `longitude` 필수.
- `roadAddress`, `placeName`, `reason`은 선택값.

프론트 참고:

- 현재 프론트는 `selectedAddress` 문자열만 저장한다.
- 카카오 검색 결과에서 `placeName`, `roadAddress`, `address`, `lat`, `lng`까지 보관해서 전송하는 방향이 좋다.

## 4. 사용자 제보: 메뉴 수정 요청

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

## 권장 패키지 구조

```text
store/
  controller/
    StoreReportController.java
  service/
    StoreReportService.java
  dto/
    ImageResponse.java
    StoreReportResponse.java
    PresignedUrlRequest.java
    PresignedFileRequest.java
    PresignedUrlsResponse.java
    PresignedUploadResponse.java
    ReportImageRequest.java
    StoreImageReportRequest.java
    StoreBusinessHoursReportRequest.java
    StoreAddressReportRequest.java
    MenuEditReportRequest.java
  repository/
    StoreReportRepository.java
    StoreReportImageFileRepository.java
```

메뉴 report endpoint는 `/menus/...`이지만 제보 저장 엔티티가 `StoreReport`이므로 `StoreReportController`에서 함께 처리해도 된다.

## 최종 API 목록

```http
POST /stores/{storeId}/reports/images/presigned-urls
POST /menus/{menuId}/reports/images/presigned-urls
POST /stores/{storeId}/reports/images
POST /stores/{storeId}/reports/business-hours
POST /stores/{storeId}/reports/address
POST /menus/{menuId}/reports/edit
```

## 구현 순서 추천

1. `StoreReport.message`를 `TEXT`로 변경.
2. `StoreReportRepository`, `StoreReportImageFileRepository` 추가.
3. `StoreImageFileRepository`, `MenuImageFileRepository`에 삭제 대상 소유 검증 메서드 추가.
4. `StoreDetailResponse`에 `ImageResponse(id, awsKey)` 적용.
5. `StoreDetailResponse`에 메뉴 목록과 메뉴 이미지 ID 포함.
6. presigned URL 발급 API 2개 추가.
7. 가게 사진 제보 API 추가.
8. 영업시간 제보 API 추가.
9. 주소 제보 API 추가.
10. 메뉴 수정 제보 API 추가.
11. 각 API에 validation, `awsKey` prefix 검증, 이미지 소유 관계 검증 추가.
12. 서비스 단위 테스트 추가.

## 테스트 시나리오

- presigned URL 발급: store/menu가 없으면 404.
- presigned URL 발급: 파일 5장 초과면 400.
- presigned URL 발급: 허용하지 않는 MIME 타입이면 400.
- 가게 사진 제보: 삭제/추가가 모두 비어 있으면 400.
- 가게 사진 제보: 삭제 이미지가 해당 store 소유가 아니면 400.
- 가게 사진 제보: `awsKey` prefix가 다르면 400.
- 영업시간 제보: `closed` 누락이면 400.
- 영업시간 제보: `closed = false`인데 시간이 없으면 400.
- 영업시간 제보: `openTime >= closeTime`이면 400.
- 주소 제보: `address`, `latitude`, `longitude` 누락이면 400.
- 메뉴 수정 제보: 변경 내용이 모두 비어 있으면 400.
- 메뉴 수정 제보: 삭제 이미지가 해당 menu 소유가 아니면 400.
- 메뉴 수정 제보: `awsKey` prefix가 다르면 400.
- 정상 제보 생성 시 `StoreReport.status = PENDING`.
- 정상 제보 생성 시 `StoreReport.member`가 로그인 사용자로 저장됨.

## GitHub Issue 분리 추천

작업량과 의존성을 고려해 4개 이슈로 나눈다.

### Issue 2. Presigned URL 발급 API

범위:

- `POST /stores/{storeId}/reports/images/presigned-urls`
- `POST /menus/{menuId}/reports/images/presigned-urls`
- 요청당 최대 5장 제한.
- MIME 타입 검증.
- `uploadUrl`, `awsKey` 응답.

완료 기준:

- 프론트가 가게 제보 이미지 업로드용 presigned URL을 발급받을 수 있다.
- 프론트가 메뉴 제보 이미지 업로드용 presigned URL을 발급받을 수 있다.
- 발급 응답은 모두 `ApiResponse.ok(data)` 형식이다.

### Issue 3. 가게 제보 API: 사진, 영업시간, 주소

범위:

- `POST /stores/{storeId}/reports/images`
- 삭제 이미지 ID 배열 처리.
- 추가 이미지 `awsKey` 저장.
- 삭제 이미지가 해당 store 소유인지 검증.
- 추가 이미지 `awsKey` prefix 검증.
- `POST /stores/{storeId}/reports/business-hours`
- 각 요일별 오픈 시간, 마감 시간, 휴무 여부 입력 처리.
- `closed` 누락 검증.
- `closed = false`일 때 `openTime`, `closeTime` 필수 검증.
- `openTime < closeTime` 검증.
- `POST /stores/{storeId}/reports/address`
- `address`, `latitude`, `longitude` 필수 검증.
- `roadAddress`, `placeName`, `reason` 선택값 처리.
- 카카오 주소 검색 API는 백엔드 구현 범위에서 제외.

완료 기준:

- 기존 사진 삭제 요청과 새 사진 추가 요청을 하나의 제보로 저장할 수 있다.
- 영업시간 수정 요청을 제보로 저장할 수 있다.
- 프론트가 선택한 주소 정보를 제보로 저장할 수 있다.
- 정상 생성 시 모두 HTTP 201과 `ApiResponse.created(data)`를 반환한다.
- 사진 제보는 `StoreReport.requestType = MOD_BAKERY_IMG`로 저장된다.
- 영업시간 제보는 `StoreReport.requestType = MOD_BAKERY_TIME`으로 저장된다.
- `StoreReport.requestType = MOD_BAKERY_ADDRESS`로 저장된다.

### Issue 4. 메뉴 제보 API + 테스트 보강

범위:

- `POST /menus/{menuId}/reports/edit`
- 가격 변경 요청 처리.
- 메뉴 사진 삭제 요청 처리.
- 메뉴 사진 추가 요청 처리.
- 삭제 이미지가 해당 menu 소유인지 검증.
- 추가 이미지 `awsKey` prefix 검증.
- presigned URL 발급 정상/실패 테스트.
- 가게 사진 제보 정상/실패 테스트.
- 영업시간 제보 정상/실패 테스트.
- 주소 제보 정상/실패 테스트.
- 메뉴 수정 제보 정상/실패 테스트.

주요 실패 케이스:

- 대상 store/menu 없음.
- 요청 내용이 모두 비어 있음.
- 삭제 이미지가 대상 store/menu 소유가 아님.
- `awsKey` prefix 불일치.
- 허용하지 않는 MIME 타입.
- 영업시간 `closed` 누락.
- 영업시간 `openTime >= closeTime`.

완료 기준:

- 가격 변경, 사진 삭제, 사진 추가 내역을 하나의 메뉴 수정 제보로 저장할 수 있다.
- 정상 생성 시 HTTP 201과 `ApiResponse.created(data)`를 반환한다.
- 메뉴 수정 제보는 `StoreReport.requestType = MOD_BREAD`로 저장된다.
- 핵심 검증 로직이 서비스 단위 테스트로 보호된다.
- 정상 생성 시 `StoreReport.status = PENDING`과 `StoreReport.member` 저장이 확인된다.
