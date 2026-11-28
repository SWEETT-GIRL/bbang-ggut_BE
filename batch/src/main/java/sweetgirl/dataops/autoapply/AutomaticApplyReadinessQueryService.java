package sweetgirl.dataops.autoapply;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AutomaticApplyReadinessQueryService {
	private final ObjectProvider<AutomaticApplyWriter> automaticWriter;

	public boolean hoursPermissionsReady() {
		AutomaticApplyWriter writer = automaticWriter.getIfAvailable();
		if (writer == null) return false;
		try {
			return writer.hoursPermissionsReady();
		} catch (DataAccessException unavailable) {
			return false; // Do not consume a recovery attempt while the serving DB cannot be checked.
		}
	}
}
