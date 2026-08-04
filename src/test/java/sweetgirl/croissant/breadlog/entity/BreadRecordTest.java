package sweetgirl.croissant.breadlog.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.member.entity.Member;

class BreadRecordTest {

	@Test
	@DisplayName("빵 기록의 먹은 날과 기록 생성 시각은 별도로 관리한다")
	void create_keepsEatenAtSeparateFromCreatedAt() {
		LocalDateTime eatenAt = LocalDateTime.of(2026, 8, 2, 14, 21, 7);
		Member member = Member.builder().nickname("테스터").build();

		BreadRecord record = BreadRecord.builder()
			.member(member)
			.storeName("밀도 성수")
			.regionKey("성동구_성수동")
			.eatenAt(eatenAt)
			.build();

		assertThat(record.getEatenAt()).isEqualTo(eatenAt);
		assertThat(record.getCreatedAt()).isNull();
	}

	@Test
	@DisplayName("빵 기록은 삭제 시 soft delete 처리한다")
	void softDelete_setsDeletedAt() {
		BreadRecord record = BreadRecord.builder()
			.member(Member.builder().nickname("테스터").build())
			.storeName("밀도 성수")
			.eatenAt(LocalDateTime.now())
			.build();

		record.softDelete();

		assertThat(record.getDeletedAt()).isNotNull();
	}
}
