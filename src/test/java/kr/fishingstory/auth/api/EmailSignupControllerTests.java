package kr.fishingstory.auth.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import kr.fishingstory.auth.application.EmailSignupService;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.common.error.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmailSignupControllerTests {
	static final String BODY = """
			{"email":"fisher@example.com","signupToken":"%s","password":"Password1",
			 "passwordConfirmation":"Password1","nickname":"Fisher","ageConfirmed":true,"agreedTermsId":12}
			""".formatted("A".repeat(43));
	EmailSignupService service;
	MockMvc mvc;
	@BeforeEach
	void setup() {
		service = mock(EmailSignupService.class);
		mvc = MockMvcBuilders.standaloneSetup(new EmailSignupController(service))
				.setControllerAdvice(new GlobalExceptionHandler()).build();
	}
	@ParameterizedTest
	@ValueSource(strings = {"false", "null"})
	void missingOrFalseAgeConfirmationNeverReachesService(String value) throws Exception {
		mvc.perform(post("/api/auth/signup/email").contentType("application/json").content(BODY.replace("true", value)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(service);
	}
	@Test
	void invalidInputAndConfirmationNeverReachesServiceOrEchoesSecrets() throws Exception {
		for (String body : new String[] {"{}", BODY.replace("Password1", "short"),
				BODY.replace("\"passwordConfirmation\":\"Password1\"", "\"passwordConfirmation\":\"Different1\""),
				BODY.replace("\"agreedTermsId\":12", "\"agreedTermsId\":null")}) {
			mvc.perform(post("/api/auth/signup/email").contentType("application/json").content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
					.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Password1"))));
		}
		verifyNoInteractions(service);
	}
	@ParameterizedTest
	@ValueSource(strings = {"SIGNUP_VERIFICATION_INVALID_OR_EXPIRED", "SIGNUP_TERMS_CHANGED", "SIGNUP_TERMS_UNAVAILABLE",
			"EMAIL_ALREADY_REGISTERED", "NICKNAME_ALREADY_USED"})
	void mapsSignupErrorsThroughSharedContract(String name) throws Exception {
		ErrorCode code = ErrorCode.valueOf(name);
		when(service.signup(any())).thenThrow(new ApiException(code));
		mvc.perform(post("/api/auth/signup/email").contentType("application/json").content(BODY))
				.andExpect(status().is(code.status().value())).andExpect(jsonPath("$.code").value(name))
				.andExpect(header().string("Cache-Control", "no-store"));
	}
}
