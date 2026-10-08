package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.entity.MemberTutorialCompletion;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.MemberTutorialCompletionRepository;
import sweetgirl.croissant.member.service.MemberReader;

@ExtendWith(MockitoExtension.class)
class TutorialRecordQueryServiceTest {
	@Mock
	MemberReader memberReader;
	@Mock
	TutorialTemplateQueryService templates;
	@Mock
	MemberTutorialCompletionRepository completions;
	@Mock
	BreadRecordRepository records;
	@InjectMocks
	TutorialRecordQueryService service;

	@Test
	void deletedRecordStillReportsCompletion() {
		when(completions.findById(1L)).thenReturn(Optional.of(new MemberTutorialCompletion(1L, 10L, "bbangdaengi-v1")));
		assertThat(service.getState(1L).recordDeleted()).isTrue();
		assertThat(service.getState(1L).completed()).isTrue();
	}

	@Test
	void requiresAnActiveSession() {
		when(memberReader.getMember(null)).thenThrow(new IllegalStateException("unauthorized"));
		assertThatThrownBy(() -> service.getState(null)).hasMessage("unauthorized");
	}
}
