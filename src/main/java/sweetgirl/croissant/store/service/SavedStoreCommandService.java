package sweetgirl.croissant.store.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.store.code.SavedStoreErrorCode;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.SavedStore;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.SavedStoreException;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.SavedStoreRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class SavedStoreCommandService {

	private final SavedStoreRepository savedStoreRepository;
	private final StoreRepository storeRepository;
	private final MemberRepository memberRepository;

	public void saveStore(Long memberId, Long storeId) {
		if (savedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)) {
			throw new SavedStoreException(SavedStoreErrorCode.SAVED_STORE_ALREADY);
		}
		Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		savedStoreRepository.save(SavedStore.create(member, store));
	}

	public void unsaveStore(Long memberId, Long storeId) {
		if (!savedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)) {
			throw new SavedStoreException(SavedStoreErrorCode.SAVED_STORE_NOT_FOUND);
		}
		savedStoreRepository.deleteByMemberIdAndStoreId(memberId, storeId);
	}
}
