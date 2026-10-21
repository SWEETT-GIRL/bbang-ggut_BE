package sweetgirl.croissant.world.entity;

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
 * 회원의 세계관 상태. 가입 경로가 넷이라 가입 훅 대신 첫 조회 때 지연 생성한다.
 *
 * <p>{@code member} 를 연관 관계로 잡지 않은 이유: 이 행은 회원 id 만 있으면 만들 수 있고,
 * 여기서 회원 쪽으로 건너갈 일이 없다. 지연 생성 경로에서 회원 엔티티를 굳이 읽지 않으려는 것이다.
 *
 * <p>{@code maxRecordCount} 는 "가장 높이 쌓았던 높이"다. 기록을 지우면 빵탑에서는 빠지지만
 * 행성은 빼앗지 않는다.
 */
@Entity
@Table(name = "member_world")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberWorld extends BaseTimeEntity {

	@Id
	@Column(name = "member_id")
	private Long memberId;

	/** 데리고 다니는 친구. */
	@Column(name = "selected_character", nullable = false, length = 30)
	private String selectedCharacter;

	@Column(nullable = false)
	private int maxRecordCount;

	/** 첫 친구 연출을 봤는가. */
	private LocalDateTime welcomedAt;

	/** 홈에 놓은 소품(상점 품목 코드). 없으면 아무것도 놓지 않았다. */
	@Column(name = "home_prop_item", length = 40)
	private String homePropItem;

	private MemberWorld(Long memberId, String selectedCharacter) {
		this.memberId = memberId;
		this.selectedCharacter = selectedCharacter;
		this.maxRecordCount = 0;
	}

	/**
	 * 어드민이 진행도를 고쳐 준다(CS · 광고 오류 보상).
	 *
	 * <p>실제 기록 수보다 낮춰도 화면은 안 내려간다 — 조회가 저장값과 지금 기록 수 중 큰 쪽을
	 * 쓰기 때문이다. 올리는 쪽으로만 효과가 있다.
	 */
	public void adjustMaxRecordCount(int maxRecordCount) {
		this.maxRecordCount = maxRecordCount;
	}

	/** 첫 조회에서 마스코트를 데리고 여정을 시작한다. */
	public static MemberWorld start(Long memberId, String mascotCode) {
		return new MemberWorld(memberId, mascotCode);
	}

	/** 데리고 다닐 친구를 바꾼다. 가진 친구인지는 서비스가 먼저 확인한다. */
	public void selectCharacter(String characterCode) {
		this.selectedCharacter = characterCode;
	}

	/** 첫 친구 연출을 봤다. 두 번째부터는 처음 본 때를 덮어쓰지 않는다. */
	public void welcome(LocalDateTime welcomedAt) {
		if (this.welcomedAt == null) {
			this.welcomedAt = welcomedAt;
		}
	}
}
