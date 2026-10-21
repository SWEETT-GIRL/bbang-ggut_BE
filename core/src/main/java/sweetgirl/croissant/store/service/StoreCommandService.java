package sweetgirl.croissant.store.service;

import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.AddressParser;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.dto.request.KakaoStoreRegisterRequest;
import sweetgirl.croissant.store.dto.request.NaverStoreRegisterRequest;
import sweetgirl.croissant.store.dto.response.KakaoStoreRegisterResponse;
import sweetgirl.croissant.store.dto.response.NaverStoreRegisterResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.SubscribedStore;
import sweetgirl.croissant.store.event.StoreSearchNameChangedEvent;
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
	private final ApplicationEventPublisher eventPublisher;

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
		// ON CONFLICT DO NOTHING 이라 새로 안 생겼을 수도 있다 — 그래도 지우는 것은 안전하다(다음 검색이 다시 채운다)
		eventPublisher.publishEvent(StoreSearchNameChangedEvent.of(request.name()));
		Long storeId = storeRepository.findByKakaoPlaceId(request.kakaoPlaceId())
			.map(Store::getId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		return new KakaoStoreRegisterResponse(storeId);
	}

	/**
	 * 네이버 검색 결과에서 고른 가게를 바로 등록한다. 제보(승인 대기)와 달리 즉시 storeId를
	 * 돌려주므로 사용자는 그 자리에서 코스에 담을 수 있다.
	 * 이미 있는 가게면(같은 네이버 장소이거나 이름·주소가 같으면) 새로 만들지 않고 그것을 쓴다.
	 */
	public NaverStoreRegisterResponse registerNaverStore(NaverStoreRegisterRequest request) {
		// 네이버 지역검색은 link에 place id가 없는 가게가 많아 naverPlaceId가 자주 null이다.
		// null을 파생 쿼리(findByNaverPlaceId)에 넣으면 IS NULL로 번역돼 place id 없는
		// 가게 수만 건이 통째로 반환된다(IncorrectResultSizeDataAccessException) — 반드시 갈라 다룬다.
		String naverPlaceId = request.naverPlaceId() != null && !request.naverPlaceId().isBlank()
			? request.naverPlaceId() : null;

		if (naverPlaceId != null) {
			Optional<Store> byNaverPlaceId = storeRepository.findByNaverPlaceId(naverPlaceId);
			if (byNaverPlaceId.isPresent()) {
				return new NaverStoreRegisterResponse(byNaverPlaceId.get().getId());
			}
		}

		Optional<Store> storeByIdentity = findActiveStoreBySameNameAndAddress(request.name(), request.address());
		if (storeByIdentity.isPresent()) {
			Store store = storeByIdentity.get();
			// 카카오로 들어와 있던 가게라면 네이버 장소만 이어 붙인다(이미 있으면 건드리지 않음).
			if (naverPlaceId != null) {
				storeRepository.attachNaverPlaceId(store.getId(), naverPlaceId);
			}
			return new NaverStoreRegisterResponse(store.getId());
		}

		String[] addr = AddressParser.parse(request.address());
		storeRepository.insertIfNotExistsByNaverPlaceId(
			request.name(), request.address(),
			addr[0], addr[1], addr[2],
			request.longitude(), request.latitude(),
			request.phone(), naverPlaceId,
			null);
		eventPublisher.publishEvent(StoreSearchNameChangedEvent.of(request.name()));

		// place id가 없으면 방금 넣은 행을 이름·주소로 되찾는다 — null로 place id 조회는 금물.
		Long storeId = (naverPlaceId != null
			? storeRepository.findByNaverPlaceId(naverPlaceId)
			: findActiveStoreBySameNameAndAddress(request.name(), request.address()))
			.map(Store::getId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		return new NaverStoreRegisterResponse(storeId);
	}

	public void subscribeStore(Long storeId, Long memberId) {
		memberReader.requireLoggedIn(memberId);
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
		memberReader.requireLoggedIn(memberId);
		if (!storeRepository.existsById(storeId)) {
			throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
		}
		SubscribedStore subscribedStore = subscribedStoreRepository.findByMemberIdAndStoreId(memberId, storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.NOT_SUBSCRIBED));
		subscribedStoreRepository.delete(subscribedStore);
	}

	private Optional<Store> findActiveStoreBySameNameAndAddress(KakaoStoreRegisterRequest request) {
		return findActiveStoreBySameNameAndAddress(request.name(), request.address());
	}

	private Optional<Store> findActiveStoreBySameNameAndAddress(String name, String address) {
		String compactName = compactName(name);
		String normalizedAddress = normalizeAddressForDuplicate(address);
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
