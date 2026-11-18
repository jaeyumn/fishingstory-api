package kr.fishingstory.user.domain;

import java.time.Duration;
import java.time.Instant;

public final class WithdrawalPolicy {
	public static final Duration GRACE_PERIOD = Duration.ofDays(30);
	private WithdrawalPolicy() { }
	public static boolean canCancel(Instant requestedAt, Instant now) {
		return requestedAt != null && now.isBefore(requestedAt.plus(GRACE_PERIOD));
	}
}
