package sweetgirl.croissant.policy.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import sweetgirl.croissant.policy.dto.response.TermsResponse;
import sweetgirl.croissant.policy.enums.TermsType;

@Service
public class TermsService {

	public List<TermsResponse> getTerms() {
		return Arrays.stream(TermsType.values())
			.map(TermsResponse::from)
			.toList();
	}
}
