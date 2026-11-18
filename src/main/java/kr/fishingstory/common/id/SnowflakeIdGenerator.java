package kr.fishingstory.common.id;

import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

@Component
public class SnowflakeIdGenerator {
	static final long EPOCH = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
	private final long workerId;
	private final Clock clock;
	private long lastMillis = -1;
	private long sequence;

	@Autowired
	public SnowflakeIdGenerator(@Value("${app.id.worker-id}") long workerId) {
		this(workerId, Clock.systemUTC());
	}
	SnowflakeIdGenerator(long workerId, Clock clock) {
		if (workerId < 0 || workerId > 1023) throw new IllegalArgumentException("Worker ID must be between 0 and 1023");
		this.workerId = workerId;
		this.clock = clock;
	}

	/** Worker ID는 동시에 실행하는 인스턴스마다 달라야 한다. 시계 역행·시퀀스 소진 시 대기 없이 실패한다. */
	public synchronized long nextId() {
		long now = clock.millis();
		long elapsed = now - EPOCH;
		if (elapsed < 0 || elapsed >= (1L << 41) || now < lastMillis
				|| (now == lastMillis && sequence == 4095)) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
		sequence = now == lastMillis ? sequence + 1 : 0;
		lastMillis = now;
		return (elapsed << 22) | (workerId << 12) | sequence;
	}
}
