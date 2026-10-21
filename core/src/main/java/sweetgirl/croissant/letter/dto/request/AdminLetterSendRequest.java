package sweetgirl.croissant.letter.dto.request;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 어드민이 여러 명에게 같은 편지를 보낸다. 각자 자기 스레드에 한 통씩 꽂히고 답장도 따로 온다.
 *
 * <p>"전체에게"는 일부러 열지 않았다 — 답장이 오는 통로라서, 전체 발송은 공지(알림) 쪽이 맡는다.
 */
@Getter
@NoArgsConstructor
public class AdminLetterSendRequest {

	@NotEmpty
	@Size(max = 500)
	private List<Long> memberIds;

	@NotBlank
	@Size(max = 2000)
	private String body;

	/**
	 * 어드민이 알아볼 발송 이름 — "A. 구경파 · 10/8 설문". 사용자에게는 보이지 않는다.
	 * 비우면 본문 앞부분으로 대신한다.
	 */
	@Size(max = 100)
	private String name;

	/**
	 * 보기 개수. 주면 답장에서 1~이 숫자(①~)를 찾아 번호별로 센다. 설문이 아니면 비운다.
	 */
	@Min(1)
	@Max(9)
	private Integer choiceCount;

	/** 받은 사람 폰에 알림도 울릴지. 끄면 알림함에만 한 줄 쌓인다. */
	private boolean push;

	/** 알림 제목·본문. 비우면 기본 문구("빵별일지 팀이 편지를 보냈어요 💌"). */
	@Size(max = 100)
	private String pushTitle;

	@Size(max = 200)
	private String pushBody;
}
