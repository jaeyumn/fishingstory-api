package kr.fishingstory.mail;

import java.time.Instant;

public interface EmailSender {
	Result send(Message message);
	default void validateConfiguration() { }
	default Result send(Message message, java.util.function.BooleanSupplier stillCurrent) {
		return stillCurrent.getAsBoolean() ? send(message) : new Result(Status.REJECTED, null);
	}

	record Message(String to, String subject, String text, String operationId, Instant expiresAt) { }
	enum Status { ACCEPTED, REJECTED, UNKNOWN }
	record Result(Status status, String providerId) { }
}
