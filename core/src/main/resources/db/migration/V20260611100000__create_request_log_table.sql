CREATE TABLE request_log
(
	id               BIGSERIAL PRIMARY KEY,
	member_id        BIGINT,
	http_method      VARCHAR(10)  NOT NULL,
	endpoint         VARCHAR(500) NOT NULL,
	query_params     TEXT,
	request_body     TEXT,
	response_status  SMALLINT     NOT NULL,
	response_time_ms INT,
	ip_address       VARCHAR(45),
	user_agent       TEXT,
	store_id         BIGINT,
	event_type       VARCHAR(50),
	error_code       VARCHAR(50),
	error_message    TEXT,
	created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rlog_store_created ON request_log (store_id, created_at);
CREATE INDEX idx_rlog_member ON request_log (member_id);
