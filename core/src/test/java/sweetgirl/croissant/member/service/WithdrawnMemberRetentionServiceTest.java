package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.entity.ModerationAppeal;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.AbuseReportReasonType;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.enums.ModerationTargetType;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.abuse.repository.ModerationActionRepository;
import sweetgirl.croissant.abuse.repository.ModerationAppealRepository;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.enums.DeviceOs;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberBlock;
import sweetgirl.croissant.member.entity.MemberRestriction;
import sweetgirl.croissant.member.entity.OAuth;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.enums.MemberRestrictionType;
import sweetgirl.croissant.member.enums.OAuthProvider;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.member.repository.OAuthRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:withdrawn-retention;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class WithdrawnMemberRetentionServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 4, 40);

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private WithdrawnMemberRetentionService retentionService;
	@Autowired
	private MemberRepository memberRepository;
	@Autowired
	private OAuthRepository oAuthRepository;
	@Autowired
	private RefreshTokenRepository refreshTokenRepository;
	@Autowired
	private FcmTokenRepository fcmTokenRepository;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private AbuseReportRepository abuseReportRepository;
	@Autowired
	private ModerationActionRepository moderationActionRepository;
	@Autowired
	private ModerationAppealRepository moderationAppealRepository;
	@Autowired
	private MemberRestrictionRepository memberRestrictionRepository;
	@Autowired
	private MemberBlockRepository memberBlockRepository;

	@Test
	@DisplayName("30일 넘은 탈퇴 회원의 남은 닉네임·이메일·토큰을 지우고, providerId 와 행은 남긴다")
	void anonymizeExpired_clearsLeftoverPersonalInfo() {
		Member expired = saveUser("옛날탈퇴", NOW.minusDays(31));
		OAuth oAuth = saveOAuth(expired, "hashed-1", "old@example.com");
		saveRefreshToken(expired);
		saveFcmToken(expired);

		WithdrawnMemberRetentionService.Result result = retentionService.anonymizeExpired(NOW);
		entityManager.clear();

		assertThat(result).isEqualTo(new WithdrawnMemberRetentionService.Result(1, 1, 1, 1));
		Member reloaded = memberRepository.findById(expired.getId()).orElseThrow();
		assertThat(reloaded.getNickname()).isEqualTo(Member.WITHDRAWN_NICKNAME);
		OAuth reloadedOAuth = oAuthRepository.findById(oAuth.getId()).orElseThrow();
		assertThat(reloadedOAuth.getEmail()).isNull();
		assertThat(reloadedOAuth.getProviderId()).isEqualTo("hashed-1");
		assertThat(refreshTokenRepository.findAllByMember(reloaded)).allMatch(token -> token.getRevokedAt() != null);
		assertThat(fcmTokenRepository.findByMemberAndIsActive(reloaded, true)).isEmpty();
	}

	@Test
	@DisplayName("탈퇴 30일 안 된 회원·활동 중인 회원·병합된 게스트·재가입으로 옮겨 간 OAuth 는 건드리지 않는다")
	void anonymizeExpired_leavesOthersAlone() {
		Member recent = saveUser("최근탈퇴", NOW.minusDays(29));
		Member active = saveUser("활동중", null);
		Member mergedGuest = saveUser("게스트123", NOW.minusDays(60));
		mergedGuest.markAsGuest();
		saveOAuth(active, "hashed-2", "active@example.com");
		saveRefreshToken(active);

		WithdrawnMemberRetentionService.Result result = retentionService.anonymizeExpired(NOW);
		entityManager.clear();

		assertThat(result).isEqualTo(new WithdrawnMemberRetentionService.Result(0, 0, 0, 0));
		assertThat(memberRepository.findById(recent.getId()).orElseThrow().getNickname()).isEqualTo("최근탈퇴");
		assertThat(memberRepository.findById(active.getId()).orElseThrow().getNickname()).isEqualTo("활동중");
		assertThat(memberRepository.findById(mergedGuest.getId()).orElseThrow().getNickname()).isEqualTo("게스트123");
	}

	@Test
	@DisplayName("탈퇴 1년이 지난 사람의 신고 · 조치 · 이의제기 · 제재 · 차단은 지우고, 1년 안 된 사람 것은 남긴다")
	void purgeExpiredModerationRecords() {
		Member old = saveUser("옛날탈퇴", NOW.minusDays(366));
		Member recent = saveUser("최근탈퇴", NOW.minusDays(100));
		Member other = saveUser("활동중", null);
		saveModerationTrail(old, other);
		saveModerationTrail(recent, other);

		WithdrawnMemberRetentionService.ModerationPurge purge = retentionService.purgeExpiredModerationRecords(NOW);
		entityManager.clear();

		assertThat(purge).isEqualTo(new WithdrawnMemberRetentionService.ModerationPurge(1, 1, 1, 1, 1));
		assertThat(abuseReportRepository.count()).isEqualTo(1);
		assertThat(moderationActionRepository.count()).isEqualTo(1);
		assertThat(moderationAppealRepository.count()).isEqualTo(1);
		assertThat(memberRestrictionRepository.count()).isEqualTo(1);
		assertThat(memberBlockRepository.count()).isEqualTo(1);
		assertThat(memberRestrictionRepository.findAll())
			.allMatch(row -> row.getMember().getId().equals(recent.getId()));
	}

	/** 탈퇴자가 신고당하고 → 조치(작성 제한) → 이의제기, 그리고 탈퇴자가 남을 차단한 흔적. */
	private void saveModerationTrail(Member withdrawn, Member other) {
		AbuseReport report = abuseReportRepository.save(AbuseReport.builder().reporter(other)
			.targetType(AbuseReportTargetType.MEMBER_PROFILE).targetId(withdrawn.getId()).reportedMember(withdrawn)
			.reasonType(AbuseReportReasonType.SPAM).build());
		ModerationAction action = moderationActionRepository.save(new ModerationAction(report,
			ModerationTargetType.MEMBER_PROFILE, withdrawn.getId(), withdrawn, AbuseReportAction.RESTRICT_MEMBER,
			"스팸"));
		moderationAppealRepository.save(ModerationAppeal.builder().moderationAction(action).member(withdrawn)
			.content("억울해요").build());
		memberRestrictionRepository.save(MemberRestriction.builder().member(withdrawn)
			.restrictionType(MemberRestrictionType.WRITE_RESTRICTED).reason("스팸").build());
		memberBlockRepository.save(MemberBlock.builder().blocker(withdrawn).blockedMember(other).build());
		entityManager.flush();
	}

	private Member saveUser(String nickname, LocalDateTime deletedAt) {
		Member member = memberRepository.save(Member.builder().nickname(nickname).build());
		if (deletedAt != null) {
			member.softDelete();
			entityManager.flush();
			entityManager.createNativeQuery("UPDATE member SET deleted_at = :deletedAt WHERE id = :id")
				.setParameter("deletedAt", deletedAt)
				.setParameter("id", member.getId())
				.executeUpdate();
		}
		return member;
	}

	private OAuth saveOAuth(Member member, String providerId, String email) {
		return oAuthRepository.save(OAuth.builder()
			.member(member).provider(OAuthProvider.KAKAO).providerId(providerId).email(email).build());
	}

	private void saveRefreshToken(Member member) {
		refreshTokenRepository.save(RefreshToken.builder()
			.member(member).deviceId("device-" + member.getId()).token("token-" + member.getId())
			.expiredAt(NOW.plusDays(14)).build());
	}

	private void saveFcmToken(Member member) {
		fcmTokenRepository.save(FcmToken.builder()
			.member(member).deviceId("device-" + member.getId()).deviceOs(DeviceOs.IOS)
			.token("fcm-" + member.getId()).build());
	}
}
