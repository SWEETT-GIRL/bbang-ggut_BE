package sweetgirl.croissant.reward.entity;

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
 * 회원의 부스러기 잔액. 첫 요약 조회나 첫 적립 때 지연 생성한다.
 *
 * <p>잔액은 이 엔티티로 고치지 않는다. 적립·차감은 {@code MemberCrumbWalletRepository} 의
 * 잠금·조건부 UPDATE 로만 일어난다 — 읽어서 더하고 저장하면 동시 요청이 서로를 덮어쓴다.
 */
@Entity
@Table(name = "member_crumb_wallet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberCrumbWallet extends BaseTimeEntity {

	@Id
	@Column(name = "member_id")
	private Long memberId;

	@Column(nullable = false)
	private int balance;

	/** 1.x 사용자 이관 선물을 준 때. 새 회원은 비어 있다. */
	private LocalDateTime carriedOverAt;
}
