package sweetgirl.croissant.member.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;

/** 닉네임을 바꿀 때 바꾸기 전 이름을 남긴다 — 사칭 신고 검토용. */
@Entity
@Table(name = "nickname_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NicknameHistory extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long memberId;

	@Column(nullable = false, length = 100)
	private String nickname;

	@Column(nullable = false)
	private LocalDateTime changedAt;

	private NicknameHistory(Long memberId, String nickname, LocalDateTime changedAt) {
		this.memberId = memberId;
		this.nickname = nickname;
		this.changedAt = changedAt;
	}

	public static NicknameHistory of(Long memberId, String previousNickname, LocalDateTime changedAt) {
		return new NicknameHistory(memberId, previousNickname, changedAt);
	}
}
