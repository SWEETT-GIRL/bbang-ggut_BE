package sweetgirl.croissant.admin.bulkimport.loader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import sweetgirl.croissant.admin.bulkimport.code.BulkImportErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

@Component
public class DongListLoader {

	private static final String CSV_PATH = "/dong/dong_list.csv";

	public List<DongRow> load() {
		try (InputStream is = DongListLoader.class.getResourceAsStream(CSV_PATH)) {
			if (is == null) {
				throw new CustomException(BulkImportErrorCode.DONG_LIST_NOT_FOUND);
			}
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(is, StandardCharsets.UTF_8))) {
				List<DongRow> rows = new ArrayList<>();
				String line = reader.readLine();
				if (line == null) {
					return rows;
				}
				while ((line = reader.readLine()) != null) {
					String[] cols = line.split(",", -1);
					if (cols.length < 3) {
						continue;
					}
					rows.add(new DongRow(cols[0].trim(), cols[1].trim(), cols[2].trim()));
				}
				return rows;
			}
		} catch (IOException e) {
			throw new CustomException(BulkImportErrorCode.DONG_LIST_PARSE_ERROR, e);
		}
	}
}
