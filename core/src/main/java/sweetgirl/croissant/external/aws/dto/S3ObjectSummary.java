package sweetgirl.croissant.external.aws.dto;

import java.time.Instant;

/** S3 목록 조회로 얻는 객체 한 건 — 키·용량(바이트)·최종 수정 시각. */
public record S3ObjectSummary(String key, long size, Instant lastModified) {
}
