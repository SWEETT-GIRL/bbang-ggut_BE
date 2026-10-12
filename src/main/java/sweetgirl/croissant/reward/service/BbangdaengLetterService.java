package sweetgirl.croissant.reward.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.response.BbangdaengLetterOpenResponse;
import sweetgirl.croissant.reward.dto.response.BbangdaengLetterResponse;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Letter;
import sweetgirl.croissant.reward.entity.BbangdaengLetter;
import sweetgirl.croissant.reward.entity.MemberBbangdaengLetter;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.BbangdaengLetterRepository;
import sweetgirl.croissant.reward.repository.MemberBbangdaengLetterRepository;

/**
 * 빵댕이 편지 — 월요일마다 한 통, 열면 부스러기를 받는다.
 *
 * <p>배치로 미리 만들지 않는다. 회원이 들어왔을 때 이번 주 편지가 없으면 그때 만든다({@link #deliverIfDue}).
 *
 * <p>오래 안 들어온 회원에게 편지가 끝없이 쌓이지 않게, 안 연 편지는 4 통까지만 두고 <b>늘 가장 최근 주에 놓는다.</b>
 * 넘치는 옛 편지를 버리고 새 글을 주는 대신, 안 연 편지의 주만 앞으로 옮긴다 — 그래야 글이 순서대로 이어지고
 * 못 받은 주 때문에 건너뛰는 글이 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class BbangdaengLetterService {

	static final int LIST_SIZE = 12;

	private final BbangdaengLetterRepository letterRepository;
	private final MemberBbangdaengLetterRepository memberLetterRepository;
	private final CrumbGrantService crumbGrantService;
	private final RewardSettings rewardSettings;
	private final MemberReader memberReader;

	/**
	 * 이번 주 편지가 아직 없으면 배달한다. 요약 조회가 읽기 전에 부른다.
	 *
	 * <p>이번 주 편지가 이미 있으면 할 일이 없다 — 대부분의 호출은 이 확인 한 번으로 끝난다.
	 * 만들어야 할 때만 지갑 행을 잠가, 나란히 들어온 요청이 같은 주의 편지를 두 번 만들지 않게 한다.
	 */
	public void deliverIfDue(Long memberId) {
		deliverIfDue(memberId, LocalDate.now(CrumbGrantService.ZONE));
	}

	/** @param today 한국 날짜. 테스트가 주를 넘겨 볼 수 있게 받는다 */
	void deliverIfDue(Long memberId, LocalDate today) {
		Member member = memberReader.getMember(memberId);
		if (member.isGuest()) {
			return;
		}
		LocalDate thisWeek = weekStartOf(today);
		if (memberLetterRepository.existsByMemberIdAndWeekStart(memberId, thisWeek)) {
			return;
		}
		crumbGrantService.serialize(memberId);
		if (memberLetterRepository.existsByMemberIdAndWeekStart(memberId, thisWeek)) {
			return;
		}
		List<BbangdaengLetter> bodies = letterRepository.findAllByActiveTrueOrderBySeqAsc();
		if (bodies.isEmpty()) {
			return;
		}
		deliver(member, thisWeek, bodies);
	}

	/**
	 * 이번 주까지의 편지를 채운다.
	 *
	 * <p>새 편지는 마지막으로 받은 주 <b>뒤의</b> 주에만 생긴다 — 상한 때문에 건너뛴 옛 주를 나중에 메우지 않는다.
	 * 안 연 편지와 새 주를 합쳐 가장 최근 주부터 상한만큼 자리를 잡고, 안 연 편지(먼저 쓴 글)를 앞자리에,
	 * 새 글을 뒷자리에 놓는다. 그래서 안 연 편지는 앞으로만 옮겨지고 글의 순서가 주의 순서와 늘 같다.
	 */
	private void deliver(Member member, LocalDate thisWeek, List<BbangdaengLetter> bodies) {
		Long memberId = member.getId();
		int maxUnread = rewardSettings.get(RewardSettingKey.LETTER_MAX_UNREAD);
		List<MemberBbangdaengLetter> received = memberLetterRepository.findAllByMemberIdOrderByWeekStartAsc(memberId);
		List<MemberBbangdaengLetter> unopened = new ArrayList<>(
			received.stream().filter(letter -> !letter.isOpened()).toList());

		// 편지를 처음 받는 회원은 이번 주부터다 — 리워드가 생기기 전의 주까지 거슬러 밀린 편지를 주지 않는다
		LocalDate firstNewWeek = received.isEmpty()
			? thisWeek
			: received.get(received.size() - 1).getWeekStart().plusWeeks(1);
		List<LocalDate> candidateWeeks = new ArrayList<>(
			unopened.stream().map(MemberBbangdaengLetter::getWeekStart).toList());
		for (LocalDate week = firstNewWeek; !week.isAfter(thisWeek); week = week.plusWeeks(1)) {
			candidateWeeks.add(week);
		}
		int slotCount = Math.min(maxUnread, candidateWeeks.size());
		List<LocalDate> slots = candidateWeeks.subList(candidateWeeks.size() - slotCount, candidateWeeks.size());

		// 상한을 줄였다면 넘치는 옛 편지는 지운다
		while (unopened.size() > slotCount) {
			memberLetterRepository.delete(unopened.remove(0));
		}
		// 뒤의 것부터 옮겨야 (회원, 주) 유니크에 서로 부딪히지 않는다
		for (int index = unopened.size() - 1; index >= 0; index--) {
			if (!unopened.get(index).getWeekStart().equals(slots.get(index))) {
				unopened.get(index).moveTo(slots.get(index));
				memberLetterRepository.flush();
			}
		}

		// 남은 자리에 새 글을 이어 준다. 글은 지금까지 받은 통 수에서 이어진다
		LocalDate joinedOn = registeredOn(member);
		LocalDateTime now = LocalDateTime.now();
		int receivedCount = received.size();
		for (LocalDate week : slots.subList(unopened.size(), slots.size())) {
			BbangdaengLetter body = bodies.get(receivedCount % bodies.size());
			memberLetterRepository.save(MemberBbangdaengLetter.deliver(
				memberId, body.getId(), week, visibleFrom(week, joinedOn), now));
			receivedCount++;
		}
	}

	/**
	 * 보이기 시작하는 날. 가입한 주의 편지만 가입 다음 날이다 — 첫날에 환영 선물·룰렛·출석에 편지까지 겹치지 않게.
	 */
	private LocalDate visibleFrom(LocalDate week, LocalDate joinedOn) {
		LocalDate dayAfterJoin = joinedOn.plusDays(1);
		return weekStartOf(joinedOn).equals(week) && dayAfterJoin.isAfter(week) ? dayAfterJoin : week;
	}

	private LocalDate registeredOn(Member member) {
		LocalDateTime registeredAt = member.getRegisteredAt() != null
			? member.getRegisteredAt()
			: member.getCreatedAt();
		return registeredAt.toLocalDate();
	}

	/** 그 날이 든 주의 월요일. */
	static LocalDate weekStartOf(LocalDate day) {
		return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	/** 지금 보이는 편지 — 최근 것부터 12 통까지. */
	@Transactional(readOnly = true)
	public List<BbangdaengLetterResponse> getLetters(Long memberId) {
		return getLetters(memberId, LocalDate.now(CrumbGrantService.ZONE));
	}

	@Transactional(readOnly = true)
	List<BbangdaengLetterResponse> getLetters(Long memberId, LocalDate today) {
		if (memberReader.getMember(memberId).isGuest()) {
			return List.of();
		}
		List<MemberBbangdaengLetter> letters = memberLetterRepository
			.findByMemberIdAndVisibleFromLessThanEqualOrderByWeekStartDesc(
				memberId, today, PageRequest.of(0, LIST_SIZE));
		Map<Long, BbangdaengLetter> bodyById = letterRepository
			.findAllById(letters.stream().map(MemberBbangdaengLetter::getLetterId).toList()).stream()
			.collect(Collectors.toMap(BbangdaengLetter::getId, Function.identity()));
		return letters.stream()
			.map(letter -> new BbangdaengLetterResponse(
				letter.getId(), letter.getWeekStart(), bodyById.get(letter.getLetterId()).getBody(),
				letter.isOpened()))
			.toList();
	}

	/** 요약에 싣는 편지 상태. 아직 안 보이는 편지(가입 당일의 첫 통)는 세지 않는다. */
	@Transactional(readOnly = true)
	public Letter summaryOf(Long memberId) {
		return summaryOf(memberId, LocalDate.now(CrumbGrantService.ZONE));
	}

	@Transactional(readOnly = true)
	Letter summaryOf(Long memberId, LocalDate today) {
		List<MemberBbangdaengLetter> waiting = memberLetterRepository
			.findByMemberIdAndOpenedAtIsNullAndVisibleFromLessThanEqualOrderByWeekStartDesc(memberId, today);
		return new Letter(waiting.size(), waiting.isEmpty() ? null : waiting.get(0).getId());
	}

	/** 편지를 연다. 처음 열 때만 부스러기를 받는다. */
	public BbangdaengLetterOpenResponse open(Long memberId, Long memberLetterId) {
		return open(memberId, memberLetterId, LocalDate.now(CrumbGrantService.ZONE));
	}

	BbangdaengLetterOpenResponse open(Long memberId, Long memberLetterId, LocalDate today) {
		memberReader.requireLoggedIn(memberId);
		MemberBbangdaengLetter letter = memberLetterRepository.findByIdAndMemberId(memberLetterId, memberId)
			.orElseThrow(() -> new RewardException(RewardErrorCode.REWARD_LETTER_NOT_FOUND));
		if (letter.getVisibleFrom().isAfter(today)) {
			// 아직 배달되지 않은 것으로 본다 — 앱이 목록에서 본 적 없는 편지다
			throw new RewardException(RewardErrorCode.REWARD_LETTER_NOT_FOUND);
		}
		String body = letterRepository.findById(letter.getLetterId())
			.map(BbangdaengLetter::getBody)
			.orElseThrow(() -> new RewardException(RewardErrorCode.REWARD_LETTER_NOT_FOUND));

		if (memberLetterRepository.open(memberLetterId, memberId, today, LocalDateTime.now()) == 0) {
			return new BbangdaengLetterOpenResponse(body, true, List.of());
		}
		GrantResult result = crumbGrantService.grant(
			memberId, CrumbReason.LETTER, rewardSettings.get(RewardSettingKey.LETTER), "letter:" + memberLetterId,
			RewardRefType.LETTER, memberLetterId);
		return new BbangdaengLetterOpenResponse(body, false,
			result.isGranted() ? List.of(RewardItemResponse.crumbs(result, CrumbReason.LETTER)) : List.of());
	}
}
