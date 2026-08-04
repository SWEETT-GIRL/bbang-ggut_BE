package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.dto.request.StorePlanUpsertRequest;
import sweetgirl.croissant.breadlog.dto.response.StorePlanResponse;
import sweetgirl.croissant.breadlog.entity.StorePlan;
import sweetgirl.croissant.breadlog.entity.StorePlanId;
import sweetgirl.croissant.breadlog.repository.StorePlanRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class StorePlanServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	MemberRestrictionReader memberRestrictionReader;
	@Mock
	StoreRepository storeRepository;
	@Mock
	StorePlanRepository storePlanRepository;

	@InjectMocks
	StorePlanService storePlanService;

	@Test
	@DisplayName("upsert: 계획이 없으면 새로 저장한다")
	void upsert_newPlan_savesPlan() {
		Member member = member(7L);
		Store store = store(10L);
		given(memberReader.getMember(7L)).willReturn(member);
		given(storeRepository.findById(10L)).willReturn(Optional.of(store));
		given(storePlanRepository.findById(new StorePlanId(7L, 10L))).willReturn(Optional.empty());
		given(storePlanRepository.save(any())).willAnswer(invocation -> {
			StorePlan plan = invocation.getArgument(0);
			ReflectionTestUtils.setField(plan, "createdAt", LocalDateTime.of(2026, 8, 4, 12, 0));
			return plan;
		});

		StorePlanResponse result = storePlanService.upsert(
			7L, 10L, new StorePlanUpsertRequest("  소금빵 꼭 먹기  "));

		assertThat(result.memo()).isEqualTo("소금빵 꼭 먹기");
		verify(storePlanRepository).save(argThat(plan -> plan.getMember() == member && plan.getStore() == store));
	}

	@Test
	@DisplayName("upsert: 기존 계획이 있으면 메모만 갱신한다")
	void upsert_existingPlan_updatesMemo() {
		Member member = member(7L);
		Store store = store(10L);
		StorePlan plan = StorePlan.builder().member(member).store(store).memo("기존").build();
		given(memberReader.getMember(7L)).willReturn(member);
		given(storeRepository.findById(10L)).willReturn(Optional.of(store));
		given(storePlanRepository.findById(new StorePlanId(7L, 10L))).willReturn(Optional.of(plan));

		StorePlanResponse result = storePlanService.upsert(7L, 10L, new StorePlanUpsertRequest("새 메모"));

		assertThat(result.memo()).isEqualTo("새 메모");
	}

	private Member member(Long id) {
		Member member = Member.builder().nickname("테스터").build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private Store store(Long id) {
		Store store = Store.builder().name("밀도 성수").address("서울 성동구").build();
		ReflectionTestUtils.setField(store, "id", id);
		return store;
	}
}
