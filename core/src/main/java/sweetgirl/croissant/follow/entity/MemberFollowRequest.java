package sweetgirl.croissant.follow.entity;

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

/**
 * requester 가 비공개 계정 target 에게 보낸 팔로우 요청. target 이 수락하면 {@link MemberFollow} 가 되고 이 행은 지운다.
 *
 * <p>쓰기는 {@code MemberFollowRequestRepository.insertIfAbsent} 가 한다(이중 탭이 겹쳐도 PK 충돌로 안 죽게).
 * 이 엔티티는 받은 요청 목록 읽기용이다.
 */
@Entity
@Table(name = "member_follow_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberFollowRequest extends BaseTimeEntity {

	@EmbeddedId
	private MemberFollowRequestId id;

	@MapsId("requesterId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "requester_id", nullable = false)
	private Member requester;

	@MapsId("targetId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "target_id", nullable = false)
	private Member target;

	private MemberFollowRequest(Member requester, Member target) {
		this.id = new MemberFollowRequestId(requester.getId(), target.getId());
		this.requester = requester;
		this.target = target;
	}

	public static MemberFollowRequest of(Member requester, Member target) {
		return new MemberFollowRequest(requester, target);
	}
}
