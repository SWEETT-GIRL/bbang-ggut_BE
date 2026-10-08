package sweetgirl.croissant.breadlog.dto.projection;

import java.time.LocalDateTime;

import sweetgirl.croissant.breadlog.enums.RecordType;

/** 사진·태그·가게 엔티티를 로딩하지 않는 활동 집계용 스칼라 조회. */
public interface BreadRecordActivityProjection {

	Long getId();

	LocalDateTime getCreatedAt();

	LocalDateTime getEatenAt();

	RecordType getRecordType();
}
