package sweetgirl.dataops.autoapply;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

class AutomaticApplyReadinessQueryServiceTest {
	@SuppressWarnings("unchecked")
	@Test void disabled_or_unavailable_writer_does_not_enable_permission_recovery() {
		ObjectProvider<AutomaticApplyWriter> provider = mock(ObjectProvider.class);
		var service = new AutomaticApplyReadinessQueryService(provider);
		assertThat(service.hoursPermissionsReady()).isFalse();
		var writer = mock(AutomaticApplyWriter.class);
		when(provider.getIfAvailable()).thenReturn(writer);
		when(writer.hoursPermissionsReady()).thenThrow(new DataAccessResourceFailureException("unavailable"));
		assertThat(service.hoursPermissionsReady()).isFalse();
		doReturn(true).when(writer).hoursPermissionsReady();
		assertThat(service.hoursPermissionsReady()).isTrue();
		verify(writer, never()).writer();
	}
}
