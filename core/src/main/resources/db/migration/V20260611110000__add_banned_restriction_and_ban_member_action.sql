-- member_restriction: BANNED 타입 추가
ALTER TABLE member_restriction
    DROP CONSTRAINT IF EXISTS ck_member_restriction_type;

ALTER TABLE member_restriction
    ADD CONSTRAINT ck_member_restriction_type CHECK (
        restriction_type IN ('WARNING', 'WRITE_RESTRICTED', 'PROFILE_RESTRICTED', 'SUSPENDED', 'BANNED')
    );

-- moderation_action: RESTRICT_MEMBER, BAN_MEMBER action 추가
ALTER TABLE moderation_action
    DROP CONSTRAINT IF EXISTS ck_moderation_action_action;

ALTER TABLE moderation_action
    ADD CONSTRAINT ck_moderation_action_action CHECK (
        action IN ('NONE', 'HIDE_REVIEW', 'DELETE_REVIEW', 'RESTORE_REVIEW', 'RESET_PROFILE',
                   'SUSPEND_MEMBER', 'RESTRICT_MEMBER', 'BAN_MEMBER')
    );

-- moderation_action: MEMBER_RESTRICTION target_type 추가
ALTER TABLE moderation_action
    DROP CONSTRAINT IF EXISTS ck_moderation_action_target_type;

ALTER TABLE moderation_action
    ADD CONSTRAINT ck_moderation_action_target_type CHECK (
        target_type IN ('REVIEW', 'MEMBER_PROFILE', 'MEMBER_RESTRICTION')
    );
