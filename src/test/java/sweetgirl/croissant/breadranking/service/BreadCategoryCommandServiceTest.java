package sweetgirl.croissant.breadranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.service.BreadCategoryClassifier;
import sweetgirl.croissant.breadranking.code.BreadRankingErrorCode;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryCreateResult;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.breadranking.exception.BreadRankingException;
import sweetgirl.croissant.breadranking.repository.BreadCategoryRepository;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.review.service.ObjectionableContentFilter;

@ExtendWith(MockitoExtension.class)
class BreadCategoryCommandServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private MemberReader memberReader;

	@Mock
	private BreadCategoryRepository categoryRepository;

	@Mock
	private BreadCategoryClassifier categoryClassifier;

	@Mock
	private ObjectionableContentFilter contentFilter;

	@InjectMocks
	private BreadCategoryCommandService service;

	@Test
	@DisplayName("새 이름이면 만든다 — 분류기로 세분류를 맞춰 대분류를 정하고, 만든 회원을 남긴다")
	void create_newName() {
		given(categoryRepository.findByNameKey("트러플소금빵")).willReturn(Optional.empty());
		given(categoryClassifier.classify("트러플 소금빵")).willReturn("소금빵");
		given(categoryRepository.save(any(BreadCategory.class))).willAnswer(invocation -> {
			BreadCategory category = invocation.getArgument(0);
			ReflectionTestUtils.setField(category, "id", 200L);
			return category;
		});

		BreadCategoryCreateResult result = service.create(MEMBER_ID, " 트러플 소금빵 ");

		assertThat(result.created()).isTrue();
		assertThat(result.category().name()).isEqualTo("트러플 소금빵");
		assertThat(result.category().breadGroup()).isEqualTo(BreadGroup.빵.name());
		assertThat(result.category().isDefault()).isFalse();
		verify(categoryRepository).save(any(BreadCategory.class));
	}

	@Test
	@DisplayName("같은 이름(공백·기호 무시)이 있으면 새로 만들지 않고 그걸 돌려준다")
	void create_duplicateReturnsExisting() {
		BreadCategory existing = BreadCategory.custom("트러플 소금빵", BreadGroup.빵, 3L);
		ReflectionTestUtils.setField(existing, "id", 200L);
		given(categoryRepository.findByNameKey("트러플소금빵")).willReturn(Optional.of(existing));

		BreadCategoryCreateResult result = service.create(MEMBER_ID, "트러플소금빵!");

		assertThat(result.created()).isFalse();
		assertThat(result.category().id()).isEqualTo(200L);
		verify(categoryRepository, never()).save(any());
	}

	@Test
	@DisplayName("분류기가 빵·기타로 물러서면 대분류 없이 만든다")
	void create_withoutGroupWhenUnclassified() {
		given(categoryRepository.findByNameKey("우리집빵")).willReturn(Optional.empty());
		given(categoryClassifier.classify("우리집빵")).willReturn("빵");
		given(categoryRepository.save(any(BreadCategory.class))).willAnswer(invocation -> invocation.getArgument(0));

		BreadCategoryCreateResult result = service.create(MEMBER_ID, "우리집빵");

		assertThat(result.category().breadGroup()).isNull();
	}

	@Test
	@DisplayName("2자 미만·15자 초과·기호뿐인 이름은 400")
	void create_invalidName() {
		for (String name : new String[] {"빵", "열여섯글자가넘는아주긴빵종류이름", "!!!", "", null}) {
			assertThatThrownBy(() -> service.create(MEMBER_ID, name))
				.as(String.valueOf(name))
				.isInstanceOf(BreadRankingException.class)
				.extracting("errorCode")
				.isEqualTo(BreadRankingErrorCode.BREAD_CATEGORY_INVALID_NAME);
		}
		verify(categoryRepository, never()).save(any());
	}

	@Test
	@DisplayName("욕설·개인정보가 들어 있으면 400")
	void create_rejectedName() {
		given(contentFilter.containsObjectionableContent("나쁜말빵")).willReturn(true);

		assertThatThrownBy(() -> service.create(MEMBER_ID, "나쁜말빵"))
			.isInstanceOf(BreadRankingException.class)
			.extracting("errorCode")
			.isEqualTo(BreadRankingErrorCode.BREAD_CATEGORY_NAME_REJECTED);
		verify(categoryRepository, never()).save(any());
	}

	@Test
	@DisplayName("게스트는 만들 수 없다")
	void create_guest() {
		willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED)).given(memberReader)
			.requireLoggedIn(MEMBER_ID);

		assertThatThrownBy(() -> service.create(MEMBER_ID, "트러플 소금빵")).isInstanceOf(MemberException.class);
		verify(categoryRepository, never()).findByNameKey(any());
	}
}
