package sweetgirl.croissant.reward.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 친구가 지금 하고 있는 모습. 행이 없으면 기본 모습이다. */
@Entity
@Table(name = "member_character_looks")
@IdClass(MemberCharacterLookId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberCharacterLook {

	@Id
	@Column(name = "member_id")
	private Long memberId;

	@Id
	@Column(name = "character_code", length = 30)
	private String characterCode;

	@Column(name = "item_code", nullable = false, length = 40)
	private String itemCode;

	private MemberCharacterLook(Long memberId, String characterCode, String itemCode) {
		this.memberId = memberId;
		this.characterCode = characterCode;
		this.itemCode = itemCode;
	}

	public static MemberCharacterLook of(Long memberId, String characterCode, String itemCode) {
		return new MemberCharacterLook(memberId, characterCode, itemCode);
	}

	public void wear(String itemCode) {
		this.itemCode = itemCode;
	}
}
