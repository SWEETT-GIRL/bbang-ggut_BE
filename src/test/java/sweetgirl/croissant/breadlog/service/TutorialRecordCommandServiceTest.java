package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.dto.request.TutorialRecordEditRequest;
import sweetgirl.croissant.breadlog.dto.request.TutorialRecordRequest;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.MemberTutorialCompletion;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.breadlog.repository.MemberTutorialCompletionRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.service.WorldCommandService;

@ExtendWith(MockitoExtension.class)
class TutorialRecordCommandServiceTest {
	@Mock
	MemberRepository memberRepository;
	@Mock
	MemberRestrictionReader restrictions;
	@Mock
	MemberTutorialCompletionRepository completions;
	@Mock
	BreadRecordRepository records;
	@Mock
	BreadRecordPhotoRepository photos;
	@Mock
	BreadRecordTagRepository tags;
	@Mock
	WorldCommandService world;
	@Mock
	MemberWorldRepository memberWorldRepository;
	@InjectMocks
	TutorialRecordCommandService service;
	Member member;

	@BeforeEach
	void setup() {
		member = Member.builder().nickname("체험").build();
		ReflectionTestUtils.setField(member, "id", 1L);
		ReflectionTestUtils.setField(member, "memberType", "GUEST");
		ReflectionTestUtils.setField(member, "agreeToTerms", true);
		ReflectionTestUtils.setField(member, "agreeToPrivacy", true);
		ReflectionTestUtils.setField(member, "agreeToAgeOver14", true);
		ReflectionTestUtils.setField(service, "enabled", true);
		when(memberRepository.findActiveForUpdate(1L)).thenReturn(Optional.of(member));
	}

	private TutorialRecordRequest request(String code) {
		return new TutorialRecordRequest(code, "croissant", LocalDateTime.now(), "첫 기록", .5, .7, null, null);
	}

	@Test
	void guestCreatesOneRealRecordWithoutNormalWritePermission() {
		when(completions.findById(1L)).thenReturn(Optional.empty());
		when(records.saveAndFlush(any())).thenAnswer(invocation -> {
			BreadRecord record = invocation.getArgument(0);
			ReflectionTestUtils.setField(record, "id", 10L);
			assertThat(record.isTutorial()).isTrue();
			assertThat(record.getStore()).isNull();
			assertThat(record.isPublic()).isFalse();
			return record;
		});
		when(records.countByMemberIdAndDeletedAtIsNull(1L)).thenReturn(1L);
		assertThat(service.complete(1L, request(TutorialRecordTemplate.CODE)).created()).isTrue();
		verify(restrictions).validateNotRestricted(1L);
		verify(restrictions, never()).validateCanWrite(any());
		verify(photos).save(argThat(photo -> TutorialRecordTemplate.PHOTO_KEY.equals(photo.getAwsKey())));
		verify(completions).save(any());
		verify(memberWorldRepository).raiseMaxRecordCount(1L, 1L);
	}

	@Test
	void retryReturnsDeletedCompletionWithoutRegrantEvenWhenDisabled() {
		ReflectionTestUtils.setField(service, "enabled", false);
		when(completions.findById(1L)).thenReturn(Optional.of(new MemberTutorialCompletion(1L, 10L, "old")));
		when(records.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.empty());
		var response = service.complete(1L, request("unknown"));
		assertThat(response.created()).isFalse();
		assertThat(response.recordDeleted()).isTrue();
		assertThat(response.recordId()).isEqualTo(10L);
		verify(records, never()).saveAndFlush(any());
	}

	@Test
	void invalidTemplateCannotCreateRecord() {
		assertThatThrownBy(() -> service.complete(1L, request("forged"))).isInstanceOf(BreadLogException.class);
		verify(records, never()).saveAndFlush(any());
	}

	@Test
	void noConsentCannotCreateRecord() {
		ReflectionTestUtils.setField(member, "agreeToTerms", false);
		assertThatThrownBy(() -> service.complete(1L, request(TutorialRecordTemplate.CODE)))
			.isInstanceOf(BreadLogException.class);
	}

	@Test
	void restrictionIsNotBypassed() {
		doThrow(new IllegalStateException("restricted")).when(restrictions).validateNotRestricted(1L);
		assertThatThrownBy(() -> service.complete(1L, request(TutorialRecordTemplate.CODE)))
			.hasMessage("restricted");
		verify(records, never()).saveAndFlush(any());
	}

	@Test
	void editChangesOnlyAllowedFields() {
		BreadRecord record = BreadRecord.builder().member(member).storeName("빵댕이 빵집").build();
		record.markTutorial(TutorialRecordTemplate.CODE);
		when(records.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(record));
		when(tags.findAllWithChipsByRecordId(10L)).thenReturn(List.of());
		service.edit(1L, 10L, new TutorialRecordEditRequest("메모 수정", "bagel", null));
		assertThat(record.getMemo()).isEqualTo("메모 수정");
		assertThat(record.getTowerBread()).isEqualTo("bagel");
		assertThat(record.getStoreName()).isEqualTo("빵댕이 빵집");
	}

	@Test
	void cannotEditNormalRecordThroughTutorial() {
		when(records.findByIdAndDeletedAtIsNull(10L))
			.thenReturn(Optional.of(BreadRecord.builder().member(member).build()));
		assertThatThrownBy(() -> service.edit(1L, 10L, new TutorialRecordEditRequest("", "bagel", null)))
			.isInstanceOf(BreadLogException.class);
	}
}
