package sweetgirl.croissant.store.service;

import java.time.LocalDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.code.SavedStoreErrorCode;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.SavedStore;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.event.StoreSavedEvent;
import sweetgirl.croissant.store.event.StoreUnsavedEvent;
import sweetgirl.croissant.store.exception.SavedStoreException;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.SavedStoreRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class SavedStoreCommandService {

	private final SavedStoreRepository savedStoreRepository;
	private final MemberReader memberReader;
	private final StoreRepository storeRepository;
	private final MemberRepository memberRepository;
	private final ApplicationEventPublisher eventPublisher;

	public void saveStore(Long memberId, Long storeId) {
		memberReader.requireLoggedIn(memberId);
		if (savedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)) {
			throw new SavedStoreException(SavedStoreErrorCode.SAVED_STORE_ALREADY);
		}
		Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		SavedStore saved = savedStoreRepository.save(SavedStore.create(member, store));
		// 배치도 읽지만, 배치 전에 해제되면 행이 사라져 UNSAVE 만 남는다 — 이벤트로 SAVE 를 먼저 남겨 짝을 맞춘다(#663)
		LocalDateTime savedAt = saved != null && saved.getCreatedAt() != null ? saved.getCreatedAt()
			: LocalDateTime.now();
		eventPublisher.publishEvent(new StoreSavedEvent(memberId, storeId, savedAt));
	}

	public void unsaveStore(Long memberId, Long storeId) {
		memberReader.requireLoggedIn(memberId);
		if (!savedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)) {
			throw new SavedStoreException(SavedStoreErrorCode.SAVED_STORE_NOT_FOUND);
		}
		savedStoreRepository.deleteByMemberIdAndStoreId(memberId, storeId);
		// 하드 삭제라 흔적이 없다 — 개인화가 UNSAVE 신호로 받는다(#663). 커밋 뒤 비동기, 실패해도 해제는 끝난다
		eventPublisher.publishEvent(new StoreUnsavedEvent(memberId, storeId, LocalDateTime.now()));
	}
}
