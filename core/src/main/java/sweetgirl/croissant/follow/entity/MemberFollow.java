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
 * follower 가 followee 를 팔로우한다. 한쪽이 누르면 끝이고 수락이 없다.
 *
 * <p>쓰기는 {@code MemberFollowRepository.insertIfAbsent} 가 한다(이중 탭 · 자동 팔로우가 겹쳐도 PK 충돌로 안 죽게).
 * 이 엔티티는 목록 읽기용이다.
 */
@Entity
@Table(name = "member_follow")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberFollow extends BaseTimeEntity {

	@EmbeddedId
	private MemberFollowId id;

	@MapsId("followerId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "follower_id", nullable = false)
	private Member follower;

	@MapsId("followeeId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "followee_id", nullable = false)
	private Member followee;

	private MemberFollow(Member follower, Member followee) {
		this.id = new MemberFollowId(follower.getId(), followee.getId());
		this.follower = follower;
		this.followee = followee;
	}

	public static MemberFollow of(Member follower, Member followee) {
		return new MemberFollow(follower, followee);
	}
}
