package sweetgirl.croissant.moderation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.moderation.enums.ForbiddenWordKind;

/** 금칙어 한 줄. BLOCK 은 막는 말, ALLOW 는 막는 말을 품었지만 괜찮은 말(예: "시발점"). 어드민이 고친다. */
@Entity
@Table(name = "forbidden_word")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ForbiddenWord extends BaseTimeEntity {

	public static final int WORD_MAX_LENGTH = 50;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = WORD_MAX_LENGTH)
	private String word;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private ForbiddenWordKind kind;

	private ForbiddenWord(String word, ForbiddenWordKind kind) {
		this.word = word;
		this.kind = kind;
	}

	public static ForbiddenWord of(String word, ForbiddenWordKind kind) {
		return new ForbiddenWord(word, kind);
	}
}
