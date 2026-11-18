package kr.fishingstory.auth.application;

import java.time.Duration;
import java.time.Instant;
import jakarta.validation.Validator;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import kr.fishingstory.auth.domain.UserAuth;
import kr.fishingstory.auth.infrastructure.jpa.SignupMemberStore;
import kr.fishingstory.auth.infrastructure.valkey.SignupVerificationStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.common.id.SnowflakeIdGenerator;
import kr.fishingstory.terms.domain.UserTermsAgreement;
import kr.fishingstory.user.domain.Nickname;
import kr.fishingstory.user.domain.User;

@Service
public class EmailSignupService {
	private static final Logger log = LoggerFactory.getLogger(EmailSignupService.class);
	private final SignupVerificationStore verification;
	private final SignupMemberStore members;
	private final SnowflakeIdGenerator ids;
	private final PasswordEncoder passwords;
	private final Validator validator;
	private final TransactionTemplate transaction;

	public EmailSignupService(SignupVerificationStore verification, SignupMemberStore members,
			SnowflakeIdGenerator ids, PasswordEncoder passwords, Validator validator, PlatformTransactionManager manager) {
		this.verification = verification;
		this.members = members;
		this.ids = ids;
		this.passwords = passwords;
		this.validator = validator;
		this.transaction = new TransactionTemplate(manager);
		// 외부 트랜잭션이 있어도 이 가입의 커밋 완료를 확인한 뒤에만 Valkey를 정리한다.
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public Result signup(EmailSignupCommand command) {
		if (command == null || !validator.validate(command).isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		Nickname nickname = Nickname.from(command.nickname());
		requireVerification(command);
		// 느린 비밀번호 해싱 동안 DB 트랜잭션·연결을 점유하지 않는다.
		String hash = passwords.encode(command.password());
		Result result;
		try {
			result = transaction.execute(status -> {
				var terms = members.findCurrentTermsOfService().orElseThrow(() -> new ApiException(ErrorCode.SIGNUP_TERMS_UNAVAILABLE));
				if (!terms.requiresConsent() || !terms.required()) throw new ApiException(ErrorCode.SIGNUP_TERMS_UNAVAILABLE);
				if (terms.id() != command.agreedTermsId()) throw new ApiException(ErrorCode.SIGNUP_TERMS_CHANGED);
				// 해싱·약관 확인 중 만료된 인증을 거절한다. 이 확인을 통과한 DB 작업은 진행 중 만료되어도 커밋한다.
				Instant expiry = requireVerification(command);
				Instant now = Instant.now();
				long userId = ids.nextId();
				members.insert(new User(userId, nickname.value(), nickname.key(), now),
						new UserAuth(ids.nextId(), userId, command.email(), hash, expiry.minus(Duration.ofHours(1)), now),
						new UserTermsAgreement(ids.nextId(), userId, terms.id(), now));
				return new Result(userId, nickname.value());
			});
		} catch (RuntimeException failure) {
			throw translateFailure(failure);
		}
		if (result == null) throw new IllegalStateException("Signup transaction returned no result");
		try {
			verification.consume(command.email(), command.signupToken());
		} catch (RuntimeException failure) {
			// 회원 생성은 이미 커밋됐다. 예외 원문에 이메일·토큰이 포함될 수 있어 출력하지 않는다.
			log.warn("Signup verification cleanup failed after member commit; grant will expire at its original deadline");
		}
		return result;
	}

	private Instant requireVerification(EmailSignupCommand command) {
		return verification.findValid(command.email(), command.signupToken())
				.orElseThrow(() -> new ApiException(ErrorCode.SIGNUP_VERIFICATION_INVALID_OR_EXPIRED));
	}

	private RuntimeException translateFailure(RuntimeException failure) {
		if (failure instanceof CannotCreateTransactionException || failure instanceof TransactionTimedOutException) {
			return new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException constraint) {
				if ("uq_user_auth_email".equals(constraint.getConstraintName())) return new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
				if ("uq_users_nickname_key".equals(constraint.getConstraintName())) return new ApiException(ErrorCode.NICKNAME_ALREADY_USED);
			}
		}
		return failure;
	}

	public record Result(long userId, String nickname) { }
}
