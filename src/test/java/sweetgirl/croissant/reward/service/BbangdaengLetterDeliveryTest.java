package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.reward.dto.response.BbangdaengLetterOpenResponse;
import sweetgirl.croissant.reward.dto.response.BbangdaengLetterResponse;
import sweetgirl.croissant.reward.entity.BbangdaengLetter;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.BbangdaengLetterRepository;

/**
 * 빵댕이 편지의 배달 규칙을 실제 DB 위에서 주를 넘겨 가며 본다.
 * 월요일은 2026-10-05 · 10-12 · 10-19 … 이다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:bbangdaeng-letter-delivery-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BbangdaengLetterDeliveryTest {

	private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private BbangdaengLetterService bbangdaengLetterService;
	@Autowired
	private BbangdaengLetterRepository letterRepository;
	@Autowired
	private MemberRepository memberRepository;
	@Autowired
	private CrumbGrantService crumbGrantService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void seedLetters() {
		for (int seq = 1; seq <= 3; seq++) {
			letterRepository.save(BbangdaengLetter.write(seq, seq + "번 글", LocalDateTime.now()));
		}
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.update("DELETE FROM member_bbangdaeng_letters");
		jdbcTemplate.update("DELETE FROM bbangdaeng_letters");
		jdbcTemplate.update("DELETE FROM crumb_ledger");
		jdbcTemplate.update("DELETE FROM member_crumb_wallet");
	}

	/** 그 날에 가입한 회원. */
	private Long memberJoinedOn(LocalDate day) {
		return memberRepository.save(Member.registering("신난 두쫀쿠", day.atTime(12, 0))).getId();
	}

	/** 배달된 글 — 안 연 편지는 응답에 글이 없어 DB 에서 어떤 글이 갔는지 본다. */
	private List<String> bodies(Long memberId, LocalDate today) {
		return deliveredBodies(bbangdaengLetterService.getLetters(memberId, today));
	}

	private List<String> deliveredBodies(List<BbangdaengLetterResponse> letters) {
		return letters.stream()
			.map(letter -> jdbcTemplate.queryForObject(
				"SELECT b.body FROM member_bbangdaeng_letters m JOIN bbangdaeng_letters b ON b.id = m.letter_id"
					+ " WHERE m.id = ?",
				String.class, letter.id()))
			.toList();
	}

	@Test
	@DisplayName("가입한 주의 첫 통은 가입 당일엔 안 보이고 다음 날부터 보인다")
	void firstLetterVisibleFromNextDay() {
		LocalDate joined = MONDAY.plusDays(2);
		Long memberId = memberJoinedOn(joined);

		bbangdaengLetterService.deliverIfDue(memberId, joined);

		assertThat(bodies(memberId, joined)).isEmpty();
		assertThat(bbangdaengLetterService.summaryOf(memberId, joined).waitingCount()).isZero();
		assertThat(bodies(memberId, joined.plusDays(1))).containsExactly("1번 글");
		assertThat(bbangdaengLetterService.summaryOf(memberId, joined.plusDays(1)).waitingCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("일요일에 가입하면 월요일에 첫 통과 새 주의 통이 함께 보인다")
	void joinedOnSunday() {
		LocalDate sunday = MONDAY.plusDays(6);
		Long memberId = memberJoinedOn(sunday);

		bbangdaengLetterService.deliverIfDue(memberId, sunday);
		bbangdaengLetterService.deliverIfDue(memberId, sunday.plusDays(1));

		assertThat(bodies(memberId, sunday.plusDays(1))).containsExactly("2번 글", "1번 글");
	}

	@Test
	@DisplayName("매주 들어온 가입 3 주 차 회원은 3 통을 순서대로 받았다")
	void threeWeeks() {
		Long memberId = memberJoinedOn(MONDAY.minusDays(3));

		for (int week = 0; week < 3; week++) {
			bbangdaengLetterService.deliverIfDue(memberId, MONDAY.plusWeeks(week));
		}

		assertThat(bodies(memberId, MONDAY.plusWeeks(2))).containsExactly("3번 글", "2번 글", "1번 글");
	}

	@Test
	@DisplayName("같은 주에 여러 번 들어와도 한 통뿐이다")
	void oncePerWeek() {
		Long memberId = memberJoinedOn(MONDAY.minusDays(3));

		bbangdaengLetterService.deliverIfDue(memberId, MONDAY);
		bbangdaengLetterService.deliverIfDue(memberId, MONDAY.plusDays(3));
		bbangdaengLetterService.deliverIfDue(memberId, MONDAY.plusDays(6));

		assertThat(bodies(memberId, MONDAY.plusDays(6))).hasSize(1);
	}

	@Test
	@DisplayName("10 주 만에 들어오면 안 연 편지는 가장 최근 4 주의 4 통뿐이고, 글은 건너뛰지 않고 이어진다")
	void capsAtFourMostRecentWeeks() {
		Long memberId = memberJoinedOn(MONDAY.minusDays(3));
		bbangdaengLetterService.deliverIfDue(memberId, MONDAY);

		LocalDate tenWeeksLater = MONDAY.plusWeeks(10);
		bbangdaengLetterService.deliverIfDue(memberId, tenWeeksLater);

		List<BbangdaengLetterResponse> letters = bbangdaengLetterService.getLetters(memberId, tenWeeksLater);
		assertThat(letters).extracting(BbangdaengLetterResponse::weekStart).containsExactly(
			tenWeeksLater, tenWeeksLater.minusWeeks(1), tenWeeksLater.minusWeeks(2), tenWeeksLater.minusWeeks(3));
		// 첫 주에 받고 안 연 1 번 글이 가장 앞 주로 옮겨지고, 이어서 2 · 3 번, 묶음이 끝나 다시 1 번이다
		assertThat(deliveredBodies(letters)).containsExactly("1번 글", "3번 글", "2번 글", "1번 글");
		assertThat(bbangdaengLetterService.summaryOf(memberId, tenWeeksLater).waitingCount()).isEqualTo(4);
	}

	@Test
	@DisplayName("연 편지는 그 주에 그대로 남고, 상한 때문에 건너뛴 옛 주를 나중에 메우지 않는다")
	void doesNotBackfillSkippedWeeks() {
		Long memberId = memberJoinedOn(MONDAY.minusDays(3));
		bbangdaengLetterService.deliverIfDue(memberId, MONDAY);
		LocalDate tenWeeksLater = MONDAY.plusWeeks(10);
		bbangdaengLetterService.deliverIfDue(memberId, tenWeeksLater);
		bbangdaengLetterService.getLetters(memberId, tenWeeksLater)
			.forEach(letter -> bbangdaengLetterService.open(memberId, letter.id(), tenWeeksLater));

		bbangdaengLetterService.deliverIfDue(memberId, tenWeeksLater.plusWeeks(1));

		List<BbangdaengLetterResponse> letters = bbangdaengLetterService.getLetters(
			memberId, tenWeeksLater.plusWeeks(1));
		assertThat(letters).hasSize(5);
		assertThat(letters.get(0).weekStart()).isEqualTo(tenWeeksLater.plusWeeks(1));
		assertThat(letters.get(0).opened()).isFalse();
		assertThat(letters.subList(1, 5)).allMatch(BbangdaengLetterResponse::opened);
	}

	@Test
	@DisplayName("목록에는 연 편지의 글만 실린다 — 안 연 편지 글이 미리보기로 새지 않게")
	void listHidesBodyUntilOpened() {
		Long memberId = memberJoinedOn(MONDAY.minusDays(3));
		bbangdaengLetterService.deliverIfDue(memberId, MONDAY);
		Long letterId = bbangdaengLetterService.getLetters(memberId, MONDAY).get(0).id();

		assertThat(bbangdaengLetterService.getLetters(memberId, MONDAY).get(0).body()).isNull();

		bbangdaengLetterService.open(memberId, letterId, MONDAY);

		assertThat(bbangdaengLetterService.getLetters(memberId, MONDAY).get(0).body()).isEqualTo("1번 글");
	}

	@Test
	@DisplayName("리워드가 생기기 전에 가입한 회원도 이번 주 한 통부터 받는다 — 밀린 주를 거슬러 주지 않는다")
	void existingMemberStartsThisWeek() {
		Long memberId = memberJoinedOn(MONDAY.minusWeeks(30));

		bbangdaengLetterService.deliverIfDue(memberId, MONDAY);

		assertThat(bodies(memberId, MONDAY)).containsExactly("1번 글");
	}

	@Test
	@DisplayName("편지를 처음 열 때만 +10 이고, 다시 열면 alreadyOpened 다")
	void openOnce() {
		Long memberId = memberJoinedOn(MONDAY.minusDays(3));
		bbangdaengLetterService.deliverIfDue(memberId, MONDAY);
		Long letterId = bbangdaengLetterService.getLetters(memberId, MONDAY).get(0).id();

		BbangdaengLetterOpenResponse first = bbangdaengLetterService.open(memberId, letterId, MONDAY);
		BbangdaengLetterOpenResponse second = bbangdaengLetterService.open(memberId, letterId, MONDAY);

		assertThat(first.alreadyOpened()).isFalse();
		assertThat(first.body()).isEqualTo("1번 글");
		assertThat(first.rewards()).singleElement().satisfies(reward -> assertThat(reward.amount()).isEqualTo(10));
		assertThat(second.alreadyOpened()).isTrue();
		assertThat(second.rewards()).isEmpty();
		assertThat(crumbGrantService.balanceOf(memberId)).isEqualTo(10);
		assertThat(bbangdaengLetterService.summaryOf(memberId, MONDAY).waitingCount()).isZero();
	}

	@Test
	@DisplayName("남의 편지와 아직 안 보이는 편지는 열 수 없다")
	void cannotOpenOthersOrInvisible() {
		LocalDate joined = MONDAY.plusDays(2);
		Long owner = memberJoinedOn(joined);
		Long other = memberJoinedOn(MONDAY.minusDays(3));
		bbangdaengLetterService.deliverIfDue(owner, joined);
		Long letterId = jdbcTemplate.queryForObject(
			"SELECT id FROM member_bbangdaeng_letters WHERE member_id = ?", Long.class, owner);

		assertThatThrownBy(() -> bbangdaengLetterService.open(other, letterId, joined.plusDays(1)))
			.isInstanceOf(RewardException.class);
		assertThatThrownBy(() -> bbangdaengLetterService.open(owner, letterId, joined))
			.isInstanceOf(RewardException.class);
		assertThat(crumbGrantService.balanceOf(owner)).isZero();
	}

	@Test
	@DisplayName("끈 글은 새로 배달하지 않는다")
	void skipsInactiveLetter() {
		BbangdaengLetter first = letterRepository.findAllByOrderBySeqAsc().get(0);
		first.revise(first.getBody(), false);
		letterRepository.save(first);
		Long memberId = memberJoinedOn(MONDAY.minusDays(3));

		bbangdaengLetterService.deliverIfDue(memberId, MONDAY);

		assertThat(bodies(memberId, MONDAY)).containsExactly("2번 글");
	}
}
