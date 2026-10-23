package sweetgirl.croissant.breadlog.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;

/** 공개 빵로그 좋아요. 쓰기는 {@code BreadRecordLikeRepository.insertIfAbsent} 가 하고 이 엔티티는 읽기용이다. */
@Entity
@Table(name = "record_like")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BreadRecordLike extends BaseTimeEntity {

	@EmbeddedId
	private BreadRecordLikeId id;

	@MapsId("recordId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "record_id", nullable = false)
	private BreadRecord record;

	@MapsId("memberId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;
}
