package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.store.code.SavedStoreErrorCode;
import sweetgirl.croissant.store.entity.SavedStore;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.event.StoreSavedEvent;
import sweetgirl.croissant.store.event.StoreUnsavedEvent;
import sweetgirl.croissant.store.exception.SavedStoreException;
import sweetgirl.croissant.store.repository.SavedStoreRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class SavedStoreCommandServiceTest {

	@Mock
	SavedStoreRepository savedStoreRepository;
	@Mock
	StoreRepository storeRepository;
	@Mock
	MemberRepository memberRepository;
	@Mock
	ApplicationEventPublisher eventPublisher;

	@InjectMocks
	SavedStoreCommandService savedStoreCommandService;

	// ───────────────────────── saveStore ─────────────────────────

	@Test
	@DisplayName("saveStore: 찜 추가 성공")
	void saveStore_success() {
		Long memberId = 1L;
		Long storeId = 10L;

		given(savedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)).willReturn(false);
		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(mock(Member.class)));
		given(storeRepository.findById(storeId)).willReturn(Optional.of(mock(Store.class)));
		given(savedStoreRepository.save(any(SavedStore.class))).willAnswer(invocation -> invocation.getArgument(0));

		savedStoreCommandService.saveStore(memberId, storeId);

		then(savedStoreRepository).should().save(any(SavedStore.class));
		// 배치 전에 해제되면 행이 사라지므로 SAVE 를 이벤트로 먼저 남긴다(#663) — 시각은 created_at(목이라 지금 시각)
		ArgumentCaptor<StoreSavedEvent> event = ArgumentCaptor.forClass(StoreSavedEvent.class);
		then(eventPublisher).should().publishEvent(event.capture());
		assertThat(event.getValue().memberId()).isEqualTo(memberId);
		assertThat(event.getValue().storeId()).isEqualTo(storeId);
		assertThat(event.getValue().savedAt()).isNotNull();
	}

	@Test
	@DisplayName("saveStore: 이미 찜한 가게 → SAVED_STORE_ALREADY")
	void saveStore_duplicate_throwsSavedStoreAlready() {
		Long memberId = 1L;
		Long storeId = 10L;

		given(savedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)).willReturn(true);

		assertThatThrownBy(() -> savedStoreCommandService.saveStore(memberId, storeId))
			.isInstanceOf(SavedStoreException.class)
			.hasMessageContaining(SavedStoreErrorCode.SAVED_STORE_ALREADY.getMessage());
	}

	// ───────────────────────── unsaveStore ─────────────────────────

	@Test
	@DisplayName("unsaveStore: 찜 해제 성공")
	void unsaveStore_success() {
		Long memberId = 1L;
		Long storeId = 10L;

		given(savedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)).willReturn(true);

		savedStoreCommandService.unsaveStore(memberId, storeId);

		then(savedStoreRepository).should().deleteByMemberIdAndStoreId(memberId, storeId);
		// 하드 삭제라 흔적이 없다 — 개인화가 UNSAVE 신호로 받도록 이벤트를 남긴다(#663)
		ArgumentCaptor<StoreUnsavedEvent> event = ArgumentCaptor.forClass(StoreUnsavedEvent.class);
		then(eventPublisher).should().publishEvent(event.capture());
		assertThat(event.getValue().memberId()).isEqualTo(memberId);
		assertThat(event.getValue().storeId()).isEqualTo(storeId);
		assertThat(event.getValue().unsavedAt()).isNotNull();
	}

	@Test
	@DisplayName("unsaveStore: 찜한 가게 없음 → SAVED_STORE_NOT_FOUND")
	void unsaveStore_notFound_throwsSavedStoreNotFound() {
		Long memberId = 1L;
		Long storeId = 10L;

		given(savedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)).willReturn(false);

		assertThatThrownBy(() -> savedStoreCommandService.unsaveStore(memberId, storeId))
			.isInstanceOf(SavedStoreException.class)
			.hasMessageContaining(SavedStoreErrorCode.SAVED_STORE_NOT_FOUND.getMessage());
	}
}
