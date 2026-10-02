package sweetgirl.croissant.breadlog.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;

/**
 * 빵로그 랭킹 "내 지역" — 지금 있는 곳으로 인증한 시·군·구.
 *
 * <p>앱이 지역을 보내게 하지 않고 서버가 들고 있는 이유: 여러 지역 판을 돌아가며 보지 못하게 하려는 것이다.
 * 위치로만 인증하니 다른 판을 보려면 실제로 거기 가 있어야 한다 — 그래서 재인증 잠금은 두지 않고, 어디서든 다시 인증하면 바로 바뀐다.
 *
 * <p>{@code member} 를 연관 관계로 잡지 않은 이유는 {@code MemberWorld} 와 같다 — 회원 id 만 있으면 된다.
 */
@Entity
@Table(name = "member_ranking_region")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberRankingRegion extends BaseTimeEntity {

	@Id
	@Column(name = "member_id")
	private Long memberId;

	/** 짧은 시·도(서울·경기…) — bread_record.region_key 첫 칸과 같은 표기. */
	@Column(nullable = false, length = 10)
	private String sido;

	/** 시·군·구("동대문구"·"수원시") — bread_record.region_key 둘째 칸과 같은 표기. 시·도만 고르던 때의 행은 null. */
	@Column(length = 20)
	private String sigungu;

	/** 마지막으로 인증한 시각(Asia/Seoul). 같은 곳을 다시 인증해도 새로 찍는다. */
	@Column(nullable = false)
	private LocalDateTime verifiedAt;

	private MemberRankingRegion(Long memberId, String sido, String sigungu, LocalDateTime now) {
		this.memberId = memberId;
		this.sido = sido;
		this.sigungu = sigungu;
		this.verifiedAt = now;
	}

	public static MemberRankingRegion verified(Long memberId, String sido, String sigungu, LocalDateTime now) {
		return new MemberRankingRegion(memberId, sido, sigungu, now);
	}

	/** 다시 인증 — 다른 곳이면 바뀌고, 같은 곳이면 인증 시각만 새로 찍는다. */
	public void reverify(String sido, String sigungu, LocalDateTime now) {
		this.sido = sido;
		this.sigungu = sigungu;
		this.verifiedAt = now;
	}

	/** 시·군·구까지 인증했는가 — 시·도만 고르던 때의 행은 내 지역 판을 볼 수 없다. */
	public boolean isVerified() {
		return sigungu != null;
	}
}
