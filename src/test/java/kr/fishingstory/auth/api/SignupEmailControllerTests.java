package kr.fishingstory.auth.api;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import kr.fishingstory.auth.application.SignupEmailCodeDelivery;
import kr.fishingstory.auth.application.SignupEmailRequestService;
import kr.fishingstory.mail.EmailSender;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SignupEmailControllerTests {
	SignupEmailRequestService service;
	MockMvc mvc;
	@BeforeEach
	void setup() {
		service = mock(SignupEmailRequestService.class);
		mvc = MockMvcBuilders.standaloneSetup(new SignupEmailController(service)).setControllerAdvice(new kr.fishingstory.common.error.GlobalExceptionHandler()).build();
	}
	@Test
	void normalizesEmailPreservesDotsAndPlusAndIgnoresSpoofedForwardedIp() throws Exception {
		when(service.request("fi.sher+boat@example.com", "127.0.0.1")).thenReturn(result(EmailSender.Status.ACCEPTED));
		mvc.perform(post("/api/auth/signup/email-code").contentType("application/json")
				.header("X-Forwarded-For", "192.0.2.99").content("{\"email\":\"  Fi.Sher+Boat@Example.COM  \"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"))
				.andExpect(jsonPath("$.retryAfterSeconds").value(58)).andExpect(jsonPath("$.expiresAt").exists())
				.andExpect(jsonPath("$.code").doesNotExist()).andExpect(header().string("Cache-Control", "no-store"));
		verify(service).request("fi.sher+boat@example.com", "127.0.0.1");
	}
	@Test
	void returnsUnknownWithoutClaimingSuccessfulDelivery() throws Exception {
		when(service.request(anyString(), anyString())).thenReturn(result(EmailSender.Status.UNKNOWN));
		mvc.perform(post("/api/auth/signup/email-code").contentType("application/json").content("{\"email\":\"fisher@example.com\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"));
	}
	@Test
	void rejectsDefiniteFailure() throws Exception {
		when(service.request(anyString(), anyString())).thenReturn(result(EmailSender.Status.REJECTED));
		mvc.perform(post("/api/auth/signup/email-code").contentType("application/json").content("{\"email\":\"fisher@example.com\"}"))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EMAIL_SEND_FAILED"))
				.andExpect(header().string("Retry-After", "58"));
	}
	@Test
	void rateLimitRoundsWaitUpToWholeSeconds() throws Exception {
		when(service.request(anyString(), anyString())).thenThrow(new SignupEmailRequestService.Limited(Duration.ofMillis(1501)));
		mvc.perform(post("/api/auth/signup/email-code").contentType("application/json").content("{\"email\":\"fisher@example.com\"}"))
				.andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "2"))
				.andExpect(jsonPath("$.retryAfterSeconds").value(2));
	}
	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"email\":null}", "{\"email\":\" \"}", "{\"email\":\"bad-address\"}", "{"})
	void invalidRequestsNeverReachQuotaOrSender(String body) throws Exception {
		mvc.perform(post("/api/auth/signup/email-code").contentType("application/json").content(body))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(service);
	}
	@Test
	void infrastructureFailureDoesNotLeakDetails() throws Exception {
		when(service.request(anyString(), anyString())).thenThrow(new IllegalStateException("test-only-sensitive-detail"));
		mvc.perform(post("/api/auth/signup/email-code").contentType("application/json").content("{\"email\":\"fisher@example.com\"}"))
				.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-only-sensitive-detail"))));
	}
	private SignupEmailRequestService.Result result(EmailSender.Status status) {
		return new SignupEmailRequestService.Result(new SignupEmailCodeDelivery.Outcome(status, Instant.parse("2026-09-13T12:00:00Z")), 58);
	}
}
