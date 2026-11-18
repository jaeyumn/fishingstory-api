package kr.fishingstory.auth.api;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import kr.fishingstory.auth.application.SignupEmailVerificationService;
import kr.fishingstory.auth.infrastructure.valkey.SignupVerificationStore;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SignupEmailVerificationControllerTests {
	SignupEmailVerificationService service;
	MockMvc mvc;
	@BeforeEach
	void setup() {
		service = mock(SignupEmailVerificationService.class);
		mvc = MockMvcBuilders.standaloneSetup(new SignupEmailVerificationController(service))
				.setControllerAdvice(new kr.fishingstory.common.error.GlobalExceptionHandler()).build();
	}
	@Test
	void returnsGrantWithoutEchoingCodeAndUsesSameEmailNormalization() throws Exception {
		when(service.verify("fi.sher+boat@example.com", "001234"))
				.thenReturn(new SignupEmailVerificationService.Grant("test-token", Instant.parse("2026-09-13T13:00:00Z")));
		mvc.perform(post("/api/auth/signup/email-code/verify").contentType("application/json")
				.content("{\"email\":\" Fi.Sher+Boat@Example.COM \",\"code\":\"001234\"}"))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.signupToken").value("test-token"))
				.andExpect(jsonPath("$.expiresAt").exists()).andExpect(jsonPath("$.code").doesNotExist());
		verify(service).verify("fi.sher+boat@example.com", "001234");
	}
	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"email\":\"bad\",\"code\":\"001234\"}",
			"{\"email\":\"fisher@example.com\",\"code\":\"12345\"}",
			"{\"email\":\"fisher@example.com\",\"code\":\"１２３４５６\"}",
			"{\"email\":\"fisher@example.com\",\"code\":null}"})
	void malformedInputNeverAttemptsCodeComparison(String body) throws Exception {
		mvc.perform(post("/api/auth/signup/email-code/verify").contentType("application/json").content(body))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(service);
	}
	@ParameterizedTest
	@ValueSource(strings = {"EMAIL_CODE_MISMATCH", "EMAIL_CODE_ATTEMPTS_EXCEEDED", "EMAIL_CODE_INVALID_OR_EXPIRED"})
	void returnsSafeVerificationError(String code) throws Exception {
		when(service.verify(anyString(), anyString())).thenThrow(new SignupVerificationStore.Rejected(kr.fishingstory.common.error.ErrorCode.valueOf(code)));
		mvc.perform(post("/api/auth/signup/email-code/verify").contentType("application/json")
				.content("{\"email\":\"fisher@example.com\",\"code\":\"001234\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.signupToken").doesNotExist());
	}
	@Test
	void storageFailureNeverReturnsGrantOrExceptionDetails() throws Exception {
		when(service.verify(anyString(), anyString())).thenThrow(new IllegalStateException("private-storage-detail"));
		mvc.perform(post("/api/auth/signup/email-code/verify").contentType("application/json")
				.content("{\"email\":\"fisher@example.com\",\"code\":\"001234\"}"))
				.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.signupToken").doesNotExist())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-storage-detail"))));
	}
}
