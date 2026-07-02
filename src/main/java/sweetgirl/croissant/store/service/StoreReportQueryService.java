package sweetgirl.croissant.store.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.kakao.client.KakaoLocalClient;
import sweetgirl.croissant.store.dto.response.AddressSearchResult;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreReportQueryService {

	private final KakaoLocalClient kakaoLocalClient;

	public List<AddressSearchResult> searchAddress(String searchKeyword) {
		return kakaoLocalClient.searchByKeywordForAddress(searchKeyword).stream()
			.map(AddressSearchResult::from)
			.toList();
	}
}
