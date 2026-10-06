CREATE TABLE monthly_report_notification (
 member_id bigint NOT NULL, year_month varchar(7) NOT NULL,
 notification_id bigint UNIQUE REFERENCES notification(id) ON DELETE SET NULL,
 PRIMARY KEY (member_id, year_month),
 FOREIGN KEY (member_id, year_month) REFERENCES monthly_report_member(member_id, year_month) ON DELETE CASCADE
);
ALTER TABLE monthly_report_push ADD COLUMN completed_at timestamptz;
ALTER TABLE monthly_report_push ADD COLUMN total_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE monthly_report_push ADD COLUMN manual_retries integer NOT NULL DEFAULT 0;
-- 발송 결과는 기존 notification_delivery에서 조회한다. 여기는 예약·재시도 정보만 추가한다.
UPDATE monthly_report_push SET total_attempts = attempts;

-- 보고서 상세의 성공 조회만 기존 요청 로그에서 찾는다. 시각은 서버의 한국 벽시계다.
CREATE INDEX idx_rlog_monthly_report_view
    ON request_log (member_id, endpoint, created_at)
    WHERE http_method = 'GET' AND response_status = 200
      AND endpoint LIKE '/reports/monthly/%/me';
