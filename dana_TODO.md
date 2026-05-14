# Admin API TODO


## 3순위 — 사진 업로드 (AWS S3)

- [ ] `POST /admin/stores/{id}/photos` 가게 사진 업로드/교체
- [ ] `POST /admin/menus/{id}/photos` 메뉴 사진 업로드/교체

---

## 나중에

- [ ] `DELETE /admin/stores/{id}` 가게 삭제
- [ ] `DELETE /admin/menus/{id}` 메뉴 삭제
- [ ] `PATCH /admin/stores/{storeId}/menus/reorder` 메뉴 순서 변경
- [ ] `GET /admin/menus` 전체 메뉴 목록 조회
- [ ] `GET /admin/menus/{id}` 메뉴 상세 조회
- [ ] `GET /admin/reports` 제보 목록 조회
- [ ] `PATCH /admin/reports/{id}` 제보 승인/거절


- [ ] `GET /admin/banners` 배너 목록 조회
- [ ] `GET /admin/banners/{id}` 배너 상세 조회
- [ ] `POST /admin/banners` 배너 생성
- [ ] `PUT /admin/banners/{id}` 배너 수정
- [ ] `DELETE /admin/banners/{id}` 배너 삭제
