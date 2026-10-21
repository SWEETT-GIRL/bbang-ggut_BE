package sweetgirl.croissant.letter.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.letter.code.WelcomeLetterMessage;
import sweetgirl.croissant.letter.entity.LetterMessage;
import sweetgirl.croissant.letter.entity.LetterThread;
import sweetgirl.croissant.letter.enums.LetterCategory;
import sweetgirl.croissant.letter.enums.LetterSender;
import sweetgirl.croissant.letter.repository.LetterMessageRepository;
import sweetgirl.croissant.letter.repository.LetterThreadRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

/**
 * 새 회원의 편지함에 인사 한 통을 미리 넣어둔다.
 *
 * <p>편지함이 있다는 걸 알리는 가장 확실한 자리가 편지함 자신이다 — 벨에 점이 켜진 채로
 * 앱을 시작하니 사용자가 먼저 찾아 들어오지 않아도 된다.
 */
@Service
@RequiredArgsConstructor
public class WelcomeLetterService {

	private final LetterThreadRepository letterThreadRepository;
	private final LetterMessageRepository letterMessageRepository;
	private final MemberRepository memberRepository;

	/**
	 * 인사 한 통. 대화방이 이미 있으면 아무 것도 하지 않는다.
	 *
	 * <p>게스트로 쓰던 사람이 소셜 로그인을 하면 {@code GuestPromotionService}가 같은 Member를
	 * 회원으로 승격시킨다 — memberId가 그대로라 게스트 때 받은 인사 위에 같은 인사가 또 쌓인다.
	 * 대화방 유무로 거르면 그 경우만 정확히 빠지고, 탈퇴 후 재가입은 Member가 새로 생기므로
	 * 그대로 다시 받는다.
	 */
	@Transactional
	public void sendWelcome(Long memberId) {
		if (letterThreadRepository.findByMemberId(memberId).isPresent()) {
			return;
		}
		Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId).orElse(null);
		if (member == null) {
			return;
		}

		LocalDateTime now = LocalDateTime.now();
		LetterThread thread = letterThreadRepository.save(LetterThread.create(member, now));

		// 어드민이 먼저 건 말은 OUTREACH — 사용자가 올린 문의와 목록에서 갈려 보여야 한다
		letterMessageRepository.save(
			LetterMessage.create(thread, LetterSender.ADMIN, WelcomeLetterMessage.BODY, LetterCategory.OUTREACH));

		/* 넣자마자 닫는다. 편지가 한 통이라도 들어 있으면 앱은 진행 중인 대화로 보고
		   "어떤 이야기인지 골라주세요"를 띄우지 않는다(LetterThread.reopen 참고). 인사는
		   답을 기다리는 상담이 아니라 인사라서, 닫아둬야 사용자가 처음 말을 걸 때 유형을 고른다. */
		thread.resolve(now);
	}
}
