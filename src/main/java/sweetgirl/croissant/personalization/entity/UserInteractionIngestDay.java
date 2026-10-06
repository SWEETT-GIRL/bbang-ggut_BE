package sweetgirl.croissant.personalization.entity;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 종료된 하루의 신호 적재 완료 표식. 원천 요청 로그의 수집 무손실을 보장하는 표식은 아니다. */
@Entity
@Table(name = "user_interaction_ingest_day")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserInteractionIngestDay {
	@Id
	@Column(name = "ingest_date", nullable = false)
	private LocalDate ingestDate;

	@Column(name = "completed_at", nullable = false)
	private Instant completedAt;
}
