package kr.fishingstory.auth.api;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import kr.fishingstory.auth.application.EmailLoginCommand;
import kr.fishingstory.auth.application.EmailLoginService;
import kr.fishingstory.common.error.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmailLoginControllerTests {
	EmailLoginService service;
	MockMvc mvc;
	@BeforeEach
	void setup() {
		service = mock(EmailLoginService.class);
		mvc = MockMvcBuilders.standaloneSetup(new EmailLoginController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
	}
	@Test
	void normalizesEmailPreservesPasswordAndUsesConnectionIp() throws Exception {
		when(service.login(any(), eq("192.0.2.1"))).thenReturn(new EmailLoginService.Tokens("Bearer", "test-access",
				Instant.now().plusSeconds(900), "test-refresh", Instant.now().plusSeconds(2592000)));
		mvc.perform(post("/api/auth/login/email").contentType("application/json")
				.content("{\"email\":\" Fi.Sher+Boat@Example.COM \",\"password\":\" Password1 \"}")
				.header("X-Forwarded-For", "198.51.100.1").header("Forwarded", "for=198.51.100.2")
				.with(request -> { request.setRemoteAddr("192.0.2.1"); return request; }))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
		verify(service).login(new EmailLoginCommand("fi.sher+boat@example.com", " Password1 "), "192.0.2.1");
	}
	@Test
	void malformedInputNeverReachesService() throws Exception {
		for (String body : new String[] {"{}", "{\"email\":\"bad\",\"password\":\"Password1\"}",
				"{\"email\":\"fisher@example.com\",\"password\":\"\"}",
				"{\"email\":\"fisher@example.com\",\"password\":\"" + "a".repeat(129) + "\"}"}) {
			mvc.perform(post("/api/auth/login/email").contentType("application/json").content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		verifyNoInteractions(service);
	}
}
