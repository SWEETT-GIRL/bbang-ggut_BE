package sweetgirl.croissant.letter.enums;

/**
 * 편지를 보낸 쪽. 말풍선 방향과 "누가 읽어야 하는지"를 함께 정한다 —
 * ADMIN이 보낸 편지는 사용자가 읽고, MEMBER가 보낸 편지는 어드민이 읽는다.
 */
public enum LetterSender {
	ADMIN,
	MEMBER
}
