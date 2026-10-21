-- Java의 AbuseReportTargetType.COURSE 및 공개 신고 API와 DB 제약을 일치시킨다.
ALTER TABLE abuse_report
    DROP CONSTRAINT IF EXISTS ck_abuse_report_target_type;

ALTER TABLE abuse_report
    ADD CONSTRAINT ck_abuse_report_target_type
        CHECK (target_type IN ('REVIEW', 'MEMBER_PROFILE', 'COURSE'));
