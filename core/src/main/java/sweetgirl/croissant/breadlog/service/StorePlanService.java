package sweetgirl.croissant.breadlog.service;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.request.StorePlanUpsertRequest;
import sweetgirl.croissant.breadlog.dto.response.StorePlanResponse;
import sweetgirl.croissant.breadlog.entity.StorePlan;
import sweetgirl.croissant.breadlog.entity.StorePlanId;
import sweetgirl.croissant.breadlog.repository.StorePlanRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.TrendingSignal;
import sweetgirl.croissant.store.event.StoreTrendingSignalEvent;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class StorePlanService {

	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final StoreRepository storeRepository;
	private final StorePlanRepository storePlanRepository;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional(readOnly = true)
	public List<StorePlanResponse> getPlans(Long memberId) {
		memberReader.getMember(memberId);
		return storePlanRepository.findAllByMemberId(memberId).stream()
			.map(StorePlanResponse::from)
			.toList();
	}

	public StorePlanResponse upsert(Long memberId, Long storeId, StorePlanUpsertRequest request) {
		Member member = memberReader.getMember(memberId);
		memberRestrictionReader.validateCanWrite(memberId);
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		String memo = request.memo().strip();
		StorePlanId id = new StorePlanId(memberId, storeId);
		StorePlan plan = storePlanRepository.findById(id)
			.map(existing -> {
				existing.updateMemo(memo);
				return existing;
			})
			.orElseGet(() -> {
				// 새로 담을 때만 관심 신호다 — 메모 수정은 새 행동이 아니다
				eventPublisher.publishEvent(new StoreTrendingSignalEvent(memberId, storeId, TrendingSignal.PLAN));
				return storePlanRepository.save(StorePlan.builder()
					.member(member)
					.store(store)
					.memo(memo)
					.build());
			});
		return StorePlanResponse.from(plan);
	}

	public void delete(Long memberId, Long storeId) {
		memberReader.requireLoggedIn(memberId);
		memberReader.getMember(memberId);
		storePlanRepository.findById(new StorePlanId(memberId, storeId))
			.ifPresent(storePlanRepository::delete);
	}
}
