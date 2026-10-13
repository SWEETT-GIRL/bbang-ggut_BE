package sweetgirl.croissant.reward.entity;

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

/**
 * 빵댕이 편지의 글 한 통. 회원마다 {@code seq} 순서대로 받고, 끝까지 받으면 처음으로 돌아간다.
 *
 * <p>운영팀과의 1:1 편지함({@code letter_thread})과는 다른 것이다.
 */
@Entity
@Table(name = "bbangdaeng_letters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BbangdaengLetter {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private int seq;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String body;

	/** 끄면 새로 배달하지 않는다. 이미 받은 편지는 그대로 남는다. */
	@Column(nullable = false)
	private boolean active;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private BbangdaengLetter(int seq, String body, LocalDateTime createdAt) {
		this.seq = seq;
		this.body = body;
		this.active = true;
		this.createdAt = createdAt;
	}

	public static BbangdaengLetter write(int seq, String body, LocalDateTime createdAt) {
		return new BbangdaengLetter(seq, body, createdAt);
	}

	public void revise(String body, boolean active) {
		this.body = body;
		this.active = active;
	}
}
