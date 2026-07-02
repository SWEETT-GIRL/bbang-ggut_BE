package sweetgirl.croissant.store.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.naver.client.NaverLocalSearchClient;
import sweetgirl.croissant.store.dto.response.NaverPlaceSearchResult;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreAddReportQueryService {

	private final NaverLocalSearchClient naverLocalSearchClient;

	public List<NaverPlaceSearchResult> searchNaverPlaces(String keyword) {
		return naverLocalSearchClient.search(keyword);
	}

}
