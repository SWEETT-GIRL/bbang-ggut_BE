# Presigned URL 발급 API 설계

## 개요

가게 제보 / 메뉴 제보 이미지 업로드용 S3 Presigned URL을 발급하는 API.

- `POST /stores/{storeId}/reports/images/presigned-urls`
- `POST /menus/{menuId}/reports/images/presigned-urls`

## 파일 구조

### 신규 파일
menu/
  controller/MenuReportController.java
  code/MenuErrorCode.java
```

### 재사용 (수정 없음)

| 파일 | 용도 |
|------|------|
| `external/aws/S3Service` | `generatePresignedUrl(path, mimeType)` 호출 |
| `external/aws/util/S3PathGenerator` | `getReportStoreImagePath(storeId)` / `getReportMenuPath(storeId, menuId)` |
| `external/aws/enums/MimeType` | MIME 타입 검증 (`fromString`) |
| `store/code/StoreErrorCode.STORE_NOT_FOUND` | 가게 없을 때 404 |
| `external/aws/exception/S3ErrorCode.INVALID_FILE_TYPE` | 지원 안 되는 MIME 400 |
| `menu/repository/MenuRepository` | `findById(menuId)` — 없으면 신규 생성 |

## API 계약

### 공통 요청

```json
POST /stores/{storeId}/reports/images/presigned-urls
POST /menus/{menuId}/reports/images/presigned-urls

{
  "mimeTypes": ["image/jpeg", "image/heic"]
}
```

- `mimeTypes`: 업로드할 이미지 장 수만큼의 MIME 타입 배열. 1~5개.
- 허용 MIME: `image/jpeg`, `image/png`, `image/webp`, `image/heic`

### 공통 응답

```json
{
  "success": true,
  "code": "S200",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": [
    { "uploadUrl": "https://bucket.s3.amazonaws.com/...", "awsKey": "reports/stores/1/images/uuid" },
    { "uploadUrl": "https://bucket.s3.amazonaws.com/...", "awsKey": "reports/stores/1/images/uuid2" }
  ]
}
```

## 서비스 흐름

### 가게 제보 (`StoreReportService.generateStoreReportPresignedUrls`)

1. `storeRepository.findById(storeId)` → 없으면 `StoreErrorCode.STORE_NOT_FOUND` (404)
2. `mimeTypes.size() > 5` → `StoreReportErrorCode.REPORT_IMAGE_COUNT_EXCEEDED` (400)
3. 각 mimeType마다 `S3PathGenerator.getReportStoreImagePath(storeId)` + `S3Service.generatePresignedUrl()` 호출
4. `List<PresignedUrlInfo>` 반환

### 메뉴 제보 (`StoreReportService.generateMenuReportPresignedUrls`)

1. `menuRepository.findById(menuId)` → 없으면 `MenuErrorCode.MENU_NOT_FOUND` (404)
2. `menu.getStore().getId()` → storeId 추출
3. `mimeTypes.size() > 5` → `StoreReportErrorCode.REPORT_IMAGE_COUNT_EXCEEDED` (400)
4. 각 mimeType마다 `S3PathGenerator.getReportMenuPath(storeId, menuId)` + `S3Service.generatePresignedUrl()` 호출
5. `List<PresignedUrlInfo>` 반환

## 에러 코드

| 상황 | 에러코드 | HTTP |
|------|----------|------|
| 가게 없음 | `StoreErrorCode.STORE_NOT_FOUND` (기존) | 404 |
| 메뉴 없음 | `MenuErrorCode.MENU_NOT_FOUND` (신규) | 404 |
| 5장 초과 | `StoreReportErrorCode.REPORT_IMAGE_COUNT_EXCEEDED` (신규) | 400 |
| 지원 안 되는 MIME | `S3ErrorCode.INVALID_FILE_TYPE` (기존) | 400 |

`StoreReportErrorCode`는 `store/code/` 패키지에 추가.  
`MenuErrorCode`는 `menu/code/` 패키지에 신규 생성.

## 테스트

`StoreReportServiceTest` (단위 테스트, `S3Service` Mock)

- 가게 없으면 예외
- 메뉴 없으면 예외
- 5장 초과 요청 시 예외
- 지원 안 되는 MIME 시 `S3StorageException` 전파
- 정상 케이스 — 요청 장 수만큼 `PresignedUrlInfo` 반환
