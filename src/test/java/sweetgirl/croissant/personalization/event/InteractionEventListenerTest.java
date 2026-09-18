package sweetgirl.croissant.personalization.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import sweetgirl.croissant.course.event.CourseStoreAddedEvent;
import sweetgirl.croissant.personalization.entity.UserStoreInteraction;
import sweetgirl.croissant.personalization.enums.InteractionType;
import sweetgirl.croissant.personalization.repository.UserStoreInteractionRepository;
import sweetgirl.croissant.store.event.StoreUnsavedEvent;

@ExtendWith(MockitoExtension.class)
class InteractionEventListenerTest {

	private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 18, 10, 30);

	@Mock
	private UserStoreInteractionRepository userStoreInteractionRepository;
	@InjectMocks
	private InteractionEventListener listener;

	@Test
	@DisplayName("저장 해제 → UNSAVE 신호 한 행(가게 단위, 사건 시각 = 해제 시각)")
	void onStoreUnsaved_recordsUnsave() {
		listener.onStoreUnsaved(new StoreUnsavedEvent(7L, 1042L, AT));

		UserStoreInteraction saved = savedInteraction();
		assertThat(saved.getMemberId()).isEqualTo(7L);
		assertThat(saved.getStoreId()).isEqualTo(1042L);
		assertThat(saved.getMenuId()).isNull();
		assertThat(saved.getType()).isEqualTo(InteractionType.UNSAVE.name());
		assertThat(saved.getContext()).isNull();
		assertThat(saved.getOccurredAt()).isEqualTo(AT);
	}

	@Test
	@DisplayName("담기 → 담은 회원의 COURSE_ADD, 추천에서 담았으면 context 가 RECO")
	void onCourseStoreAdded_recordsAddWithContext() {
		listener.onCourseStoreAdded(new CourseStoreAddedEvent(7L, 367L, 2368L, 57L, AT));

		UserStoreInteraction saved = savedInteraction();
		assertThat(saved.getMemberId()).isEqualTo(7L);
		assertThat(saved.getType()).isEqualTo(InteractionType.COURSE_ADD.name());
		assertThat(saved.getContext()).isEqualTo(InteractionEventListener.CONTEXT_RECOMMENDATION);
		assertThat(saved.getOccurredAt()).isEqualTo(AT);
	}

	@Test
	@DisplayName("추천이 아닌 담기는 context 없이")
	void onCourseStoreAdded_withoutRecommendation_hasNoContext() {
		listener.onCourseStoreAdded(new CourseStoreAddedEvent(7L, 367L, 2368L, null, AT));

		assertThat(savedInteraction().getContext()).isNull();
	}

	@Test
	@DisplayName("같은 사건이 이미 있거나(유니크 위반) 저장이 실패해도 예외를 밖으로 내지 않는다 — 신호 손실이 요청 실패보다 낫다")
	void record_swallowsFailures() {
		given(userStoreInteractionRepository.save(any()))
			.willThrow(new DataIntegrityViolationException("duplicate"))
			.willThrow(new RuntimeException("db down"));

		assertThatCode(() -> listener.onStoreUnsaved(new StoreUnsavedEvent(7L, 1042L, AT))).doesNotThrowAnyException();
		assertThatCode(() -> listener.onStoreUnsaved(new StoreUnsavedEvent(7L, 1042L, AT))).doesNotThrowAnyException();
	}

	private UserStoreInteraction savedInteraction() {
		ArgumentCaptor<UserStoreInteraction> captor = ArgumentCaptor.forClass(UserStoreInteraction.class);
		then(userStoreInteractionRepository).should().save(captor.capture());
		return captor.getValue();
	}
}
