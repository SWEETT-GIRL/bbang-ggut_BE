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
| `abuse_report` | User abuse report | `POST /abuse-reports` | `ABUSE_REPORT` |
| `report_notification` | Report result notification history | `GET /alarm/report-notifications`, `PATCH /alarm/report-notifications/{id}/read` | n/a |

Supporting upload/search APIs remain part of the legacy surface:

- `GET /address/search`
- `POST /stores/{storeId}/reports/images/presigned-urls`
- `GET /stores/add-reports/naver/search`
- `POST /menu-add-reports/images/presigned-url`

## Admin Report Behavior

The current admin entry point is `/api/admin/reports`.

- `GET /api/admin/reports` defaults to `STORE_REPORT` when `kind` is omitted.
- `kind` accepts `STORE_REPORT`, `STORE_ADD_REPORT`, `MENU_ADD_REPORT`, and `ABUSE_REPORT`.
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
- User abuse report completion notifications use action `ABUSE_REPORT_RESULT`.
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

## Phase 6-7 Compatibility Strategy

The notification domain now uses `sweetgirl.croissant.common.enums.ReportKind`
so report identity is no longer owned by the alarm package.

`report_notification` keeps `report_id` and `report_kind` as legacy API
compatibility fields. New writes also populate one canonical link:

- `contribution_report_id` for contribution approval/rejection notifications.
- `abuse_report_id` for abuse report result and moderation action notifications.

The old contribution detail tables have no production data at the time of this
phase, so the data backfill is expected to be a no-op or verification safety
net. They still cannot be dropped in this phase because the application still
stores contribution detail payloads there.

- Keep `store_report`, `store_add_report`, and `menu_add_report` until their
  detail columns are moved into contribution-owned detail tables.
- Keep `store_contribution_report` and `menu_contribution_report` as bridge
  tables from `contribution_report` to legacy detail rows.
- Stop adding new legacy-only write paths. New contribution writes must create
  `contribution_report` and the matching bridge row in the same transaction.
- Drop the old detail tables only after admin list/detail/review no longer read
  `StoreReport`, `StoreAddReport`, or `MenuAddReport`.
- Remove `StoreReportRepository`, `StoreAddReportRepository`,
  `MenuAddReportRepository`, and their image-detail repositories in that same
  follow-up, not in the notification-link phase.

`abuse_report` is the canonical abuse table. The old `user_report` table name is
renamed by migration and should not be recreated by application code. The public
path is `/abuse-reports`.

## Phase 7 Migration Checklist

- Run Flyway through `V20260612040000__backfill_contribution_and_report_notification_links.sql`.
- If any data exists, compare counts:
  `store_report + store_add_report + menu_add_report` detail rows with
  `contribution_report` rows joined through bridge tables.
- If all old detail tables are empty, record that the backfill was a no-op.
- Verify `report_notification` rows with `ABUSE_REPORT` have `abuse_report_id`.
- Verify contribution notification rows have `contribution_report_id`.
- Confirm admin review still updates both `contribution_report.status` and the
  legacy detail status during the compatibility window.
- Confirm `/alarm/report-notifications` still returns `reportId` and
  `reportKind` for old clients.
- Next cleanup before DROP: move contribution detail payload columns and image
  references out of `store_report`, `store_add_report`, and `menu_add_report`;
  then remove legacy repositories/services and add a final drop-table migration.
