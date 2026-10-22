package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordImportDraftResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordCompanion;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordCompanionRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("BreadRecordImportService")
class BreadRecordImportServiceTest {

	private static final Long OWNER = 1L;
	private static final Long ME = 2L;
	private static final Long RECORD = 10L;

	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private BreadRecordPhotoRepository photoRepository;
	@Mock
	private BreadRecordTagRepository tagRepository;
	@Mock
	private BreadRecordCompanionRepository companionRepository;
	@Mock
	private BreadRecordVisibilityPolicy visibilityPolicy;
	@Mock
	private MemberReader memberReader;
	@Mock
	private MemberRestrictionReader memberRestrictionReader;
	@Mock
	private S3Service s3Service;

	@InjectMocks
	private BreadRecordImportService importService;

	private Member member(Long id) {
		Member member = Member.builder().nickname("회원" + id).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private BreadRecord record() {
		BreadRecord record = BreadRecord.builder().member(member(OWNER)).storeName("밀도")
			.eatenAt(LocalDateTime.of(2026, 10, 1, 12, 0)).towerBread("소금빵").lovedBreads(List.of("소금빵")).build();
		ReflectionTestUtils.setField(record, "id", RECORD);
		return record;
	}

	@Test
	@DisplayName("태그된 사람이면 사진을 내 경로로 복사한 새 키와 가게 · 빵을 초안으로 준다")
	void draft() {
		BreadRecord record = record();
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD)).willReturn(Optional.of(record));
		given(companionRepository.findByIdRecordIdAndIdMemberId(RECORD, ME))
			.willReturn(Optional.of(BreadRecordCompanion.of(record, member(ME), LocalDateTime.now())));
		BreadRecordPhoto photo = BreadRecordPhoto.builder().record(record).name("a").mimeType(MimeType.IMAGE_JPEG)
			.awsKey("bread-records/members/1/images/a").sortOrder(0).width(300).height(400).build();
		given(photoRepository.findByRecordIdInOrderByRecordIdAscSortOrderAscIdAsc(List.of(RECORD)))
			.willReturn(List.of(photo));
		BreadRecordTag tag = BreadRecordTag.builder().record(record).photo(photo).name("소금빵").positionX(0.5)
			.positionY(0.4).build();
		given(tagRepository.findAllWithChipsByRecordId(RECORD)).willReturn(List.of(tag));

		BreadRecordImportDraftResponse draft = importService.draft(ME, RECORD);

		then(memberReader).should().requireLoggedIn(ME);
		then(memberRestrictionReader).should().validateCanWrite(ME);
		then(visibilityPolicy).should().validateCanView(ME, record);
		then(s3Service).should().copyObject(eq("bread-records/members/1/images/a"),
			startsWith("bread-records/members/2/images/"));
		assertThat(draft.sourceRecordId()).isEqualTo(RECORD);
		assertThat(draft.storeName()).isEqualTo("밀도");
		assertThat(draft.towerBread()).isEqualTo("소금빵");
		assertThat(draft.photos()).singleElement().satisfies(copied -> {
			assertThat(copied.awsKey()).startsWith("bread-records/members/2/images/");
			assertThat(copied.awsKey()).isNotEqualTo("bread-records/members/1/images/a");
			assertThat(copied.contentType()).isEqualTo("image/jpeg");
			assertThat(copied.width()).isEqualTo(300);
		});
		assertThat(draft.tags()).singleElement().satisfies(copiedTag -> {
			assertThat(copiedTag.photoSortOrder()).isEqualTo(0);
			assertThat(copiedTag.name()).isEqualTo("소금빵");
			assertThat(copiedTag.positionX()).isEqualTo(0.5);
		});
	}

	@Test
	@DisplayName("태그된 사람이 아니거나 스스로 뺐으면 403 — 사진을 복사하지 않는다")
	void notCompanion() {
		BreadRecord record = record();
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD)).willReturn(Optional.of(record));
		BreadRecordCompanion removed = BreadRecordCompanion.of(record, member(ME), LocalDateTime.now());
		removed.removeBySelf(LocalDateTime.now());
		given(companionRepository.findByIdRecordIdAndIdMemberId(RECORD, ME)).willReturn(Optional.of(removed));

		assertThatThrownBy(() -> importService.draft(ME, RECORD))
			.isInstanceOf(BreadLogException.class)
			.hasMessage(BreadLogErrorCode.IMPORT_NOT_COMPANION.getMessage());
		then(s3Service).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("볼 수 없는 기록이면 403 그대로 — 사진을 복사하지 않는다")
	void notVisible() {
		BreadRecord record = record();
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(RECORD)).willReturn(Optional.of(record));
		willThrow(new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_VISIBLE))
			.given(visibilityPolicy).validateCanView(ME, record);

		assertThatThrownBy(() -> importService.draft(ME, RECORD))
			.hasMessage(BreadLogErrorCode.BREAD_RECORD_NOT_VISIBLE.getMessage());
		then(s3Service).should(never()).copyObject(anyString(), any());
	}
}
