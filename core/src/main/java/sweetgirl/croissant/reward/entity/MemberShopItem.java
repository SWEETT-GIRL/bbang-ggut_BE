package sweetgirl.croissant.reward.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원이 산 품목. 읽기 전용이다 — 행은 {@code MemberShopItemRepository.insertIfAbsent} 로만 생긴다.
 *
 * <p>모습은 친구마다 따로 사므로 {@code characterCode} 가 있고, 소품은 계정에 하나라 빈 문자열이다.
 */
@Entity
@Table(name = "member_shop_items", uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "character_code",
	"item_code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberShopItem {

	/** 소품처럼 친구에 딸리지 않은 품목의 {@code characterCode}. */
	public static final String NO_CHARACTER = "";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "character_code", nullable = false, length = 30)
	private String characterCode;

	@Column(name = "item_code", nullable = false, length = 40)
	private String itemCode;

	@Column(nullable = false, updatable = false)
	private LocalDateTime purchasedAt;

	/** 친구에 딸린 품목이면 그 친구, 아니면 null. */
	public String characterCodeOrNull() {
		return NO_CHARACTER.equals(characterCode) ? null : characterCode;
	}
}
