package kr.fishingstory.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTests {
	MockMvc mvc;
	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new SampleController())
				.setControllerAdvice(new GlobalExceptionHandler()).build();
	}
	@Test
	void enumControlsStatusMessageAndRetryHeader() throws Exception {
		mvc.perform(get("/sample/limited"))
				.andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.code").value("EMAIL_RATE_LIMITED"))
				.andExpect(jsonPath("$.message").value(ErrorCode.EMAIL_RATE_LIMITED.message()))
				.andExpect(jsonPath("$.retryAfterSeconds").value(60));
	}
	@Test
	void includesFieldNamesWithoutEchoingRejectedValues() throws Exception {
		mvc.perform(post("/sample/input").contentType("application/json").content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("name"))
				.andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
				.andExpect(jsonPath("$.retryAfterSeconds").doesNotExist());
	}
	@Test
	void unreadableBodyUsesSameTemplate() throws Exception {
		mvc.perform(post("/sample/input").contentType("application/json").content("{"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}
	@Test
	void preservesMethodNotAllowedAndAllowHeader() throws Exception {
		mvc.perform(post("/sample/limited"))
				.andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow"))
				.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}
	@Test
	void infrastructureOutageIs503() throws Exception {
		mvc.perform(get("/sample/unavailable"))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
	}
	@Test
	void unexpectedFailureIs500AndNeverLeaksExceptionMessage() throws Exception {
		mvc.perform(get("/sample/unexpected"))
				.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-detail"))));
	}

	@RestController
	static class SampleController {
		@GetMapping("/sample/limited")
		public void limited() { throw new ApiException(ErrorCode.EMAIL_RATE_LIMITED, 60L); }
		@GetMapping("/sample/unavailable")
		public void unavailable() { throw new DataAccessResourceFailureException("private-detail"); }
		@GetMapping("/sample/unexpected")
		public void unexpected() { throw new IllegalStateException("private-detail"); }
		@PostMapping("/sample/input")
		public void input(@Valid @RequestBody Input input) { }
	}
	record Input(@NotBlank String name) { }
}
