package kr.fishingstory;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.catchreport.image.ImageStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** prod 프로필의 노출 경로를 실제 Security·MVC 구성으로 확인한다. 운영 값 대신 테스트 키와 컨테이너를 사용한다. */
@SpringBootTest(properties = {"SNOWFLAKE_WORKER_ID=1", "R2_ENABLED=false"})
@ActiveProfiles({"test", "prod"})
@AutoConfigureMockMvc
@Import(FishingstoryApiApplicationTests.DatabaseConfiguration.class)
class ProductionProfileTests {
	@Autowired MockMvc mvc;
	@Autowired AccessTokenIssuer tokens;
	@Autowired ApplicationContext context;

	@DynamicPropertySource
	static void signingKeys(DynamicPropertyRegistry registry) throws NoSuchAlgorithmException {
		var generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair pair = generator.generateKeyPair();
		registry.add("JWT_PRIVATE_KEY_BASE64", () -> base64Pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
		registry.add("JWT_PUBLIC_KEY_BASE64", () -> base64Pem("PUBLIC KEY", pair.getPublic().getEncoded()));
	}

	@Test
	void hidesApiDocumentationAndLocalCheckEvenFromSignedInMembers() throws Exception {
		String access = "Bearer " + tokens.issue(1, "USER", UUID.randomUUID().toString()).value();
		for (String path : List.of("/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html", "/test")) {
			mvc.perform(get(path)).andExpect(status().isUnauthorized());
			mvc.perform(get(path).header("Authorization", access)).andExpect(status().isNotFound());
		}
		// An explicit R2_ENABLED=false keeps photo storage off without failing startup.
		assertThat(context.getBeansOfType(ImageStorage.class)).isEmpty();
	}

	private static String base64Pem(String type, byte[] der) {
		String pem = "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
				+ "\n-----END " + type + "-----\n";
		return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
	}
}
