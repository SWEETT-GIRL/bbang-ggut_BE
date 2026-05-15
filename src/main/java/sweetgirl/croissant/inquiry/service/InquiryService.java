package sweetgirl.croissant.inquiry.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.inquiry.dto.request.InquiryCreateRequest;
import sweetgirl.croissant.inquiry.dto.response.InquiryCreateResponse;
import sweetgirl.croissant.inquiry.entity.Inquiry;
import sweetgirl.croissant.inquiry.repository.InquiryRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;

@Service
@Transactional
@RequiredArgsConstructor
public class InquiryService {

	private final InquiryRepository inquiryRepository;
	private final MemberReader memberReader;

	public InquiryCreateResponse createInquiry(Long memberId, InquiryCreateRequest request) {
		Member member = memberReader.getMember(memberId);
		Inquiry inquiry = inquiryRepository.save(Inquiry.builder()
			.member(member)
			.category(request.category())
			.title(request.title())
			.content(request.content())
			.build());

		return InquiryCreateResponse.from(inquiry);
	}
}
