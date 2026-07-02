package sweetgirl.croissant.admin.service;

import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.entity.ModerationAppeal;
import sweetgirl.croissant.abuse.enums.ModerationAppealStatus;
import sweetgirl.croissant.abuse.repository.ModerationAppealRepository;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.response.AdminModerationAppealResponse;
import sweetgirl.croissant.admin.exception.AdminException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminModerationAppealQueryService {

	private final ModerationAppealRepository moderationAppealRepository;

	public Page<AdminModerationAppealResponse> getAppeals(String status, int page, int size) {
		ModerationAppealStatus parsedStatus = toAppealStatus(status);
		PageRequest pageRequest = PageRequest.of(page, size);
		Page<ModerationAppeal> appeals = parsedStatus != null
			? moderationAppealRepository.findByStatus(parsedStatus, pageRequest)
			: moderationAppealRepository.findAllBy(pageRequest);
		return appeals.map(AdminModerationAppealResponse::from);
	}

	private ModerationAppealStatus toAppealStatus(String status) {
		if (status == null) {
			return null;
		}
		try {
			return ModerationAppealStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new AdminException(AdminErrorCode.APPEAL_INVALID_STATUS);
		}
	}
}
