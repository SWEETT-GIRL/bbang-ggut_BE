# ContributionReport / AbuseReport Phase 0 Baseline

Phase 0 freezes the current legacy report behavior before introducing
`ContributionReport` and `AbuseReport`. Later phases should keep these API
paths and response fields compatible unless a phase explicitly documents a
breaking change.

## Legacy Tables And APIs

| Table | Current domain | Main API | Admin kind |
| --- | --- | --- | --- |
| `store_report` | Store update contribution | `POST /stores/{storeId}/reports/images`, `POST /stores/{storeId}/reports/business-hours`, `POST /stores/{storeId}/reports/address` | `STORE_REPORT` |
| `store_add_report` | Store add contribution | `POST /stores/add-reports` | `STORE_ADD_REPORT` |
| `menu_add_report` | Menu add contribution | `POST /stores/{storeId}/menu-add-reports` | `MENU_ADD_REPORT` |
| `user_report` | User abuse report | `POST /reports` | `USER_REPORT` |
| `report_notification` | Report result notification history | `GET /alarm/report-notifications`, `PATCH /alarm/report-notifications/{id}/read` | n/a |

Supporting upload/search APIs remain part of the legacy surface:

- `GET /address/search`
- `POST /stores/{storeId}/reports/images/presigned-urls`
- `GET /stores/add-reports/naver/search`
- `POST /menu-add-reports/images/presigned-url`

## Admin Report Behavior

The current admin entry point is `/api/admin/reports`.

- `GET /api/admin/reports` defaults to `STORE_REPORT` when `kind` is omitted.
- `kind` accepts `STORE_REPORT`, `STORE_ADD_REPORT`, `MENU_ADD_REPORT`, and `USER_REPORT`.
- `status` is passed through as the legacy status enum for each backing table.
- List responses are Spring `Page<AdminReportResponse>` wrapped in `ApiResponse`.
- Detail lookup uses `GET /api/admin/reports/{reportId}?kind={kind}`.
- Store/menu contribution detail responses wrap the list item shape in `target`.
- User abuse report details use the dedicated user-report detail shape and include
  `targetType`, `targetId`, `reasonType`, `reasonDetail`, `moderationAction`,
  `reporterId`, and `reportedMemberId`.
- Review uses `PATCH /api/admin/reports/{reportId}` with body
  `{"kind":"...","action":"ACCEPT|REJECT"}`.
- Already reviewed reports are rejected by the service before applying changes.
- Review side effects currently include legacy table status updates and
  `ReportNotificationService` notification creation.

## Notification Behavior

`report_notification` currently stores a polymorphic legacy reference as
`report_id` plus `report_kind`.

- Contribution approval/rejection notifications use action `APPROVED` or `REJECTED`.
- User abuse report completion notifications use action `USER_REPORT_RESULT`.
- Moderation action notifications use action `MODERATION_ACTION` and include
  `moderation_action_id`.
- `GET /alarm/report-notifications` returns newest-first data from
  `ReportNotificationResponse`: `id`, `reportId`, `moderationActionId`,
  `reportKind`, `action`, `storeId`, `storeName`, `title`, `message`, `read`,
  `readAt`, and `sentAt`.
- `PATCH /alarm/report-notifications/{id}/read` marks only the authenticated
  member's notification as read and returns an empty success `ApiResponse`.

## Compatibility Tests

Phase 0 adds controller-level compatibility tests for:

- Admin report list response fields.
- Admin report detail response fields.
- Admin report review endpoint path/body.
- Report notification list response fields.
- Report notification read endpoint path.
