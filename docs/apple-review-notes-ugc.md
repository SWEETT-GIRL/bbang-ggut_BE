# Apple Review Notes - UGC Safety

작성일: 2026-06-05

## 제출 메모

```text
Bbang-ggut includes user-generated reviews and member profile information.

Users can report reviews and member profiles from the app. Submitted reports are reviewed by the operations team. After review, the team may hide or delete violating content, reset profile information, or restrict member usage depending on severity and repeat violations.

Restricted users are notified in-app and can submit an appeal from the restriction notification or through customer support.

Users can block review authors. Reviews and profile information from blocked users are hidden from the blocking user's screens. Blocked authors are also excluded from the blocking user's review preview, review list, review detail, average rating, and review count views.

Reviews are checked before saving and are rejected when they contain prohibited words, hate/abusive expressions, or personal information patterns such as phone numbers, email addresses, or resident-registration-number-like strings.

Reviews can be edited only within 24 hours after creation. After that period, the author can delete the review but cannot edit it.

Customer Support URL:
https://bbanggut.com/support

Community Guidelines URL:
https://bbanggut.com/community-guidelines
```

## URL 정리

- 고객지원 URL: `https://bbanggut.com/support`
- 커뮤니티 가이드라인 URL: `https://bbanggut.com/community-guidelines`
- 앱 내 고객지원 경로: `POST /inquiries`
- 앱 내 약관/정책 조회 경로: `GET /terms`

## 포함 항목 확인

- 리뷰 및 프로필 신고 가능
- 운영팀 검토 후 콘텐츠 숨김/삭제/회원 이용 제한 처리
- 이용 제한 알림 및 고객지원에서 이의제기 가능
- 작성자 차단 가능
- 차단한 사용자의 리뷰/프로필은 내 화면에서 숨김
- 리뷰 게시 전 콘텐츠 필터링
- 작성 후 24시간 이후 리뷰 수정 제한 및 삭제 가능
- 고객지원 URL
- 커뮤니티 가이드라인 URL
