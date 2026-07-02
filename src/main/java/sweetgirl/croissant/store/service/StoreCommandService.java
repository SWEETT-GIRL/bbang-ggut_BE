package sweetgirl.croissant.store.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.AddressParser;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.dto.request.KakaoStoreRegisterRequest;
import sweetgirl.croissant.store.dto.response.KakaoStoreRegisterResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.SubscribedStore;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class StoreCommandService {

	private final StoreRepository storeRepository;
	private final SubscribedStoreRepository subscribedStoreRepository;
	private final MemberReader memberReader;

	public KakaoStoreRegisterResponse registerKakaoStore(KakaoStoreRegisterRequest request) {
		Optional<Store> storeByKakaoPlaceId = storeRepository.findByKakaoPlaceId(request.kakaoPlaceId());
		if (storeByKakaoPlaceId.isPresent()) {
			return new KakaoStoreRegisterResponse(storeByKakaoPlaceId.get().getId());
		}

		Optional<Store> storeByIdentity = findActiveStoreBySameNameAndAddress(request);
		if (storeByIdentity.isPresent()) {
			Store store = storeByIdentity.get();
			storeRepository.updateKakaoPlaceMetadata(
				store.getId(),
				request.kakaoPlaceId(),
				request.phone(),
				Double.parseDouble(request.x()),
				Double.parseDouble(request.y()));
			return new KakaoStoreRegisterResponse(store.getId());
		}

		String[] addr = AddressParser.parse(request.addressName() != null ? request.addressName() : request.address());
		storeRepository.insertIfNotExistsByKakaoPlaceId(
			request.name(), request.address(),
			addr[0], addr[1], addr[2],
			Double.parseDouble(request.x()), Double.parseDouble(request.y()),
			request.phone(), request.kakaoPlaceId(),
			null);
		Long storeId = storeRepository.findByKakaoPlaceId(request.kakaoPlaceId())
			.map(Store::getId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		return new KakaoStoreRegisterResponse(storeId);
	}

	public void subscribeStore(Long storeId, Long memberId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		if (subscribedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)) {
			throw new StoreException(StoreErrorCode.ALREADY_SUBSCRIBED);
		}
		subscribedStoreRepository.save(SubscribedStore.builder()
			.member(memberReader.getMember(memberId))
			.store(store)
			.build());
	}

	public void unsubscribeStore(Long storeId, Long memberId) {
		if (!storeRepository.existsById(storeId)) {
			throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
		}
		SubscribedStore subscribedStore = subscribedStoreRepository.findByMemberIdAndStoreId(memberId, storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.NOT_SUBSCRIBED));
		subscribedStoreRepository.delete(subscribedStore);
	}

	private Optional<Store> findActiveStoreBySameNameAndAddress(KakaoStoreRegisterRequest request) {
		String compactName = compactName(request.name());
		String normalizedAddress = normalizeAddressForDuplicate(request.address());
		if (compactName.isBlank() || normalizedAddress.isBlank()) {
			return Optional.empty();
		}
		return storeRepository.findActiveStoresByCompactName(compactName)
			.stream()
			.filter(store -> normalizedAddress.equals(normalizeAddressForDuplicate(store.getAddress())))
			.findFirst();
	}

	private String compactName(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase().replaceAll("\\s+", "");
	}

	private String normalizeAddressForDuplicate(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase()
			.replaceAll("(?:지하)?\\d+층", "")
			.replaceAll("\\d+호", "")
			.replaceAll("[^0-9a-zA-Z가-힣ㄱ-ㅎㅏ-ㅣ]", "");
	}
}
