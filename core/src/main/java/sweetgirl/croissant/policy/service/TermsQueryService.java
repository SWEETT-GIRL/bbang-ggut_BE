package sweetgirl.croissant.policy.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import sweetgirl.croissant.policy.dto.response.TermsResponse;
import sweetgirl.croissant.policy.enums.TermsType;

@Service
@Transactional(readOnly = true)
public class TermsQueryService {

	public List<TermsResponse> getTerms() {
		return Arrays.stream(TermsType.values())
			.map(TermsResponse::from)
			.toList();
	}
}
