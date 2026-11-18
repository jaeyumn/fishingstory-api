package kr.fishingstory.catchreport.image;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class R2ImageStorageTests {
	@ParameterizedTest
	@ValueSource(strings = {"http://r2.example.test", "https:/missing-host", "https://r2.example.test/path",
			"https://user@r2.example.test", "https://r2.example.test?query", "https://r2.example.test/#fragment"})
	void refusesInvalidEndpointBeforeConstructingAClient(String endpoint) {
		assertThatThrownBy(() -> new R2ImageStorage(endpoint, "test-access", "test-secret", "test-bucket"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("R2 endpoint must be an HTTPS origin");
	}
}
