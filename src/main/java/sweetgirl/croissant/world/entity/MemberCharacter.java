package sweetgirl.croissant.world.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.world.enums.AcquiredBy;
import sweetgirl.croissant.world.enums.CharacterStatus;

/**
 * 회원이 가진 캐릭터. 행은 지연 생성이라, 광고를 한 편이라도 보거나 해금되기 전에는 행 자체가 없다.
 *
 * <p>DB 가 지키는 약속: AWAKE 면 획득 경로와 시각이 둘 다 차 있고, SLEEPING 이면 둘 다 비어 있다.
 */
@Entity
@Table(name = "member_characters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberCharacter {

	@EmbeddedId
	private MemberCharacterId id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private CharacterStatus status;

	@Column(nullable = false)
	private int adViews;

	@Enumerated(EnumType.STRING)
	@Column(name = "acquired_by", length = 12)
	private AcquiredBy acquiredBy;

	private LocalDateTime acquiredAt;

	private MemberCharacter(
			MemberCharacterId id,
			CharacterStatus status,
			int adViews,
			AcquiredBy acquiredBy,
			LocalDateTime acquiredAt) {
		this.id = id;
		this.status = status;
		this.adViews = adViews;
		this.acquiredBy = acquiredBy;
		this.acquiredAt = acquiredAt;
	}

	/** 이미 깨어난 채로 받는 친구 — 마스코트 · 룰렛 · 추천 · 어드민 지급. */
	public static MemberCharacter awake(
			Long memberId,
			String characterCode,
			AcquiredBy acquiredBy,
			LocalDateTime acquiredAt) {
		return new MemberCharacter(
			new MemberCharacterId(memberId, characterCode),
			CharacterStatus.AWAKE,
			0,
			acquiredBy,
			acquiredAt);
	}

	public String characterCode() {
		return id.getCharacterCode();
	}

	public boolean isAwake() {
		return status == CharacterStatus.AWAKE;
	}
}
