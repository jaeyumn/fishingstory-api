package kr.fishingstory.common.id;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import kr.fishingstory.common.error.ApiException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnowflakeIdGeneratorTests {
	@Test
	void separatesWorkersAndUsesIncreasingSequenceWithinSameMillisecond() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(SnowflakeIdGenerator.EPOCH + 1000), ZoneOffset.UTC);
		var first = new SnowflakeIdGenerator(1, clock);
		var second = new SnowflakeIdGenerator(2, clock);
		long id = first.nextId();
		assertThat(first.nextId()).isEqualTo(id + 1);
		assertThat(id >> 22).isEqualTo(1000);
		assertThat((id >> 12) & 1023).isEqualTo(1);
		assertThat(second.nextId()).isNotEqualTo(id);
	}
	@Test
	void sequenceExhaustionNeverWrapsAndNewMillisecondRecovers() {
		AtomicLong millis = new AtomicLong(SnowflakeIdGenerator.EPOCH + 1000);
		Clock clock = mock(Clock.class);
		when(clock.millis()).thenAnswer(call -> millis.get());
		var ids = new SnowflakeIdGenerator(0, clock);
		long last = 0;
		for (int i = 0; i < 4096; i++) last = ids.nextId();
		assertThatThrownBy(ids::nextId).isInstanceOf(ApiException.class);
		assertThatThrownBy(ids::nextId).isInstanceOf(ApiException.class);
		millis.incrementAndGet();
		assertThat(ids.nextId()).isGreaterThan(last);
	}
	@Test
	void clockRollbackFailsUntilClockRecoversWithoutReusingSequence() {
		Clock clock = mock(Clock.class);
		long time = SnowflakeIdGenerator.EPOCH + 1000;
		when(clock.millis()).thenReturn(time, time - 1, time, time + 1);
		var ids = new SnowflakeIdGenerator(0, clock);
		long first = ids.nextId();
		assertThatThrownBy(ids::nextId).isInstanceOf(ApiException.class);
		assertThat(ids.nextId()).isEqualTo(first + 1);
		assertThat(ids.nextId()).isGreaterThan(first + 1);
	}
	@Test
	void rejectsInvalidWorkerAndOutOfRangeClock() {
		assertThatThrownBy(() -> new SnowflakeIdGenerator(-1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SnowflakeIdGenerator(1024)).isInstanceOf(IllegalArgumentException.class);
		for (long time : new long[] {SnowflakeIdGenerator.EPOCH - 1, SnowflakeIdGenerator.EPOCH + (1L << 41)}) {
			var ids = new SnowflakeIdGenerator(0, Clock.fixed(Instant.ofEpochMilli(time), ZoneOffset.UTC));
			assertThatThrownBy(ids::nextId).isInstanceOf(ApiException.class);
		}
	}
	@Test
	void concurrentCallsNeverDuplicateIds() throws Exception {
		var ids = new SnowflakeIdGenerator(3, Clock.fixed(Instant.ofEpochMilli(SnowflakeIdGenerator.EPOCH + 1000), ZoneOffset.UTC));
		try (var executor = Executors.newFixedThreadPool(8)) {
			var tasks = new ArrayList<Callable<Long>>();
			for (int i = 0; i < 1000; i++) tasks.add(ids::nextId);
			var values = new ArrayList<Long>();
			for (var future : executor.invokeAll(tasks)) values.add(future.get());
			assertThat(values).doesNotHaveDuplicates().hasSize(1000);
		}
	}
}
