package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.store.code.SavedStoreErrorCode;
import sweetgirl.croissant.store.entity.SavedStore;
import sweetgirl.croissant.store.entity.Store;
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

		savedStoreCommandService.saveStore(memberId, storeId);

		then(savedStoreRepository).should().save(any(SavedStore.class));
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
