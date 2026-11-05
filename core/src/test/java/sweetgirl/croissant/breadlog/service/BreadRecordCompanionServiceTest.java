package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.service.CommunityNotificationService;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordCompanion;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordCompanionRepository;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.SocialMemberReader;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("BreadRecordCompanionService")
class BreadRecordCompanionServiceTest {

	private static final Long OWNER = 1L;
	private static final Long RECORD = 10L;

	@Mock
	private BreadRecordCompanionRepository companionRepository;
	@Mock
	private MemberFollowRepository memberFollowRepository;
	@Mock
	private CourseMemberRepository courseMemberRepository;
	@Mock
	private MemberBlockRepository memberBlockRepository;
	@Mock
	private SocialMemberReader socialMemberReader;
	@Mock
	private MemberReader memberReader;
	@Mock
	private CharacterFaceReader characterFaceReader;
	@Mock
	private CommunityNotificationService communityNotificationService;

	@InjectMocks
	private BreadRecordCompanionService companionService;

	private Member member(Long id) {
		Member member = Member.builder().nickname("회원" + id).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private BreadRecord record() {
		BreadRecord record = BreadRecord.builder().member(member(OWNER)).storeName("빵집").build();
		ReflectionTestUtils.setField(record, "id", RECORD);
		return record;
	}

	private void following(Long memberId) {
		lenient().when(socialMemberReader.findSocial(memberId)).thenReturn(Optional.of(member(memberId)));
		lenient().when(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(OWNER, memberId)).thenReturn(true);
	}

	@Test
	@DisplayName("null 이면(구버전 앱) 손대지 않는다")
	void nullKeepsAsIs() {
		companionService.replace(record(), null);

		then(companionRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("팔로우 관계인 사람을 새로 태그하면 저장하고 알린다")
	void tagsFollowing() {
		BreadRecord record = record();
		following(2L);
		given(companionRepository.findAllByIdRecordId(RECORD)).willReturn(List.of());
		given(companionRepository.findMemberIdsTaggedSince(eq(OWNER), any())).willReturn(List.of());

		companionService.replace(record, List.of(2L));

		ArgumentCaptor<List<BreadRecordCompanion>> captor = ArgumentCaptor.forClass(List.class);
		then(companionRepository).should().saveAll(captor.capture());
		assertThat(captor.getValue()).hasSize(1);
		assertThat(captor.getValue().get(0).getId().getMemberId()).isEqualTo(2L);
		ArgumentCaptor<List<Member>> notified = ArgumentCaptor.forClass(List.class);
		then(communityNotificationService).should().notifyCompanionTag(eq(record), notified.capture());
		assertThat(notified.getValue()).extracting(Member::getId).containsExactly(2L);
	}

	@Test
	@DisplayName("팔로우도 아니고 일행도 아니면 400 COMPANION400_NOT_RELATED")
	void notRelated() {
		given(socialMemberReader.findSocial(2L)).willReturn(Optional.of(member(2L)));
		given(companionRepository.findAllByIdRecordId(RECORD)).willReturn(List.of());
		given(courseMemberRepository.existsSharedActiveCourse(OWNER, 2L)).willReturn(false);

		assertThatThrownBy(() -> companionService.replace(record(), List.of(2L)))
			.isInstanceOf(BreadLogException.class)
			.hasMessage(BreadLogErrorCode.COMPANION_NOT_RELATED.getMessage());
	}

	@Test
	@DisplayName("빵투어 일행이면 팔로우가 아니어도 된다")
	void courseMateCounts() {
		given(socialMemberReader.findSocial(2L)).willReturn(Optional.of(member(2L)));
		given(companionRepository.findAllByIdRecordId(RECORD)).willReturn(List.of());
		given(courseMemberRepository.existsSharedActiveCourse(OWNER, 2L)).willReturn(true);
		given(companionRepository.findMemberIdsTaggedSince(eq(OWNER), any())).willReturn(List.of());

		companionService.replace(record(), List.of(2L));

		then(companionRepository).should().saveAll(anyList());
	}

	@Test
	@DisplayName("차단 관계면 403, 나 자신이면 400")
	void blockedAndSelf() {
		given(socialMemberReader.findSocial(2L)).willReturn(Optional.of(member(2L)));
		given(companionRepository.findAllByIdRecordId(RECORD)).willReturn(List.of());
		given(memberBlockRepository.existsBetween(OWNER, 2L)).willReturn(true);

		assertThatThrownBy(() -> companionService.replace(record(), List.of(2L)))
			.hasMessage(BreadLogErrorCode.COMPANION_BLOCKED.getMessage());
		assertThatThrownBy(() -> companionService.replace(record(), List.of(OWNER)))
			.hasMessage(BreadLogErrorCode.COMPANION_SELF.getMessage());
	}

	@Test
	@DisplayName("스스로 뺀 사람을 다시 넣으면 400 COMPANION400_REMOVED")
	void removedCannotBeReTagged() {
		BreadRecord record = record();
		BreadRecordCompanion removed = BreadRecordCompanion.of(record, member(2L), LocalDateTime.now());
		removed.removeBySelf(LocalDateTime.now());
		given(companionRepository.findAllByIdRecordId(RECORD)).willReturn(List.of(removed));

		assertThatThrownBy(() -> companionService.replace(record, List.of(2L)))
			.hasMessage(BreadLogErrorCode.COMPANION_REMOVED.getMessage());
	}

	@Test
	@DisplayName("오늘 태그한 사람과 합쳐 5명을 넘기면 429 — 같은 사람은 한 번만 센다")
	void dailyLimit() {
		following(6L);
		following(7L);
		given(companionRepository.findAllByIdRecordId(RECORD)).willReturn(List.of());
		given(companionRepository.findMemberIdsTaggedSince(eq(OWNER), any())).willReturn(List.of(2L, 3L, 4L, 5L));

		companionService.replace(record(), List.of(6L));
		assertThatThrownBy(() -> companionService.replace(record(), List.of(6L, 7L)))
			.hasMessage(BreadLogErrorCode.COMPANION_DAILY_LIMIT.getMessage());
	}

	@Test
	@DisplayName("빠진 사람의 행은 지우고(다시 넣을 수 있게), 남은 사람은 다시 알리지 않는다")
	void untagDeletesRow() {
		BreadRecord record = record();
		BreadRecordCompanion kept = BreadRecordCompanion.of(record, member(2L), LocalDateTime.now());
		BreadRecordCompanion dropped = BreadRecordCompanion.of(record, member(3L), LocalDateTime.now());
		given(companionRepository.findAllByIdRecordId(RECORD)).willReturn(List.of(kept, dropped));

		companionService.replace(record, List.of(2L));

		then(companionRepository).should().delete(dropped);
		then(companionRepository).should(never()).delete(kept);
		then(communityNotificationService).should().notifyCompanionTag(record, List.of());
	}

	@Test
	@DisplayName("스스로 빼기: removed_at 을 채우고, 태그된 적 없으면 404")
	void removeBySelf() {
		BreadRecordCompanion companion = BreadRecordCompanion.of(record(), member(2L), LocalDateTime.now());
		given(companionRepository.findByIdRecordIdAndIdMemberId(RECORD, 2L)).willReturn(Optional.of(companion));
		given(companionRepository.findByIdRecordIdAndIdMemberId(RECORD, 3L)).willReturn(Optional.empty());

		companionService.removeBySelf(RECORD, 2L);

		assertThat(companion.isRemoved()).isTrue();
		assertThatThrownBy(() -> companionService.removeBySelf(RECORD, 3L))
			.hasMessage(BreadLogErrorCode.COMPANION_NOT_TAGGED.getMessage());
	}

	@Test
	@DisplayName("상세용 목록은 얼굴을 붙인다")
	void companionsOf() {
		BreadRecordCompanion companion = BreadRecordCompanion.of(record(), member(2L), LocalDateTime.now());
		given(companionRepository.findActiveByRecordId(RECORD)).willReturn(List.of(companion));
		given(characterFaceReader.facesOf(List.of(2L))).willReturn(Map.of(2L, new CharacterFace("CROISSANT", "k")));

		assertThat(companionService.companionsOf(RECORD))
			.singleElement()
			.satisfies(response -> {
				assertThat(response.memberId()).isEqualTo(2L);
				assertThat(response.characterCode()).isEqualTo("CROISSANT");
			});
	}
}
