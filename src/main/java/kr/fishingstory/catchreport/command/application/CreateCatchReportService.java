package kr.fishingstory.catchreport.command.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import kr.fishingstory.catchreport.command.domain.CatchReport;
import kr.fishingstory.catchreport.command.domain.CatchReportSpecies;
import kr.fishingstory.catchreport.command.domain.FishSpecies;
import kr.fishingstory.catchreport.command.domain.UserFishCollection;
import kr.fishingstory.catchreport.command.infrastructure.jpa.CatchReportCommandStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.common.id.SnowflakeIdGenerator;
import kr.fishingstory.user.infrastructure.jpa.UserCommandStore;

@Service
public class CreateCatchReportService {
	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private final UserCommandStore users;
	private final CatchReportCommandStore reports;
	private final SnowflakeIdGenerator ids;
	private final TransactionTemplate transaction;

	public CreateCatchReportService(UserCommandStore users, CatchReportCommandStore reports,
			SnowflakeIdGenerator ids, PlatformTransactionManager manager) {
		this.users = users;
		this.reports = reports;
		this.ids = ids;
		this.transaction = new TransactionTemplate(manager);
	}

	public Result create(long userId, CreateCatchReportCommand command) {
		return create(userId, null, command);
	}

	public Result create(long userId, String requestKey, CreateCatchReportCommand command) {
		if (command == null || (requestKey != null && !requestKey.matches("[0-9a-f]{32}"))) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		if (command.fishingDate().isAfter(LocalDate.now(SERVICE_ZONE))) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		Result result = transaction.execute(status -> {
			var user = users.lock(userId).orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED));
			if (!"ACTIVE".equals(user.status())) throw new ApiException(ErrorCode.ACCESS_DENIED);
			// 회원 행 잠금 뒤 확인하므로 같은 키의 동시 재시도도 조황을 하나만 만든다.
			if (requestKey != null) {
				var existing = reports.findRequested(userId, requestKey);
				if (existing.isPresent()) return replay(existing.get(), command);
			}
			long reportId = ids.nextId();
			Instant now = Instant.now();
			var selectedIds = command.species().stream().map(CreateCatchReportCommand.SpeciesSelection::speciesId)
					.filter(java.util.Objects::nonNull).toList();
			var master = reports.findActiveSpecies(selectedIds).stream()
					.collect(Collectors.toMap(FishSpecies::id, Function.identity()));
			if (master.size() != selectedIds.size()
					|| master.values().stream().anyMatch(species -> !species.supports(command.waterType()))) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR);
			}
			reports.insert(new CatchReport(reportId, userId, requestKey, command.catchStatus(), command.waterType(),
					command.fishingDate(), command.fishingTime(), command.memo(), now));
			var events = new ArrayList<CollectionEvent>();
			for (int index = 0; index < command.species().size(); index++) {
				var selection = command.species().get(index);
				reports.insert(new CatchReportSpecies(ids.nextId(), reportId, selection.speciesId(),
						selection.customName(), selection.maxLengthCm(), selection.maxWeightG(), index, now));
				if (selection.speciesId() == null) continue;
				FishSpecies species = master.get(selection.speciesId());
				var collection = reports.lockCollection(userId, species.id());
				if (collection.isEmpty()) {
					var acquired = new UserFishCollection(ids.nextId(), userId, species.id(),
							selection.maxLengthCm(), command.fishingDate(), now);
					acquired.recordWeight(selection.maxWeightG());
					reports.insert(acquired);
					events.add(new CollectionEvent("NEW_SPECIES", species.id(), species.name(), selection.maxLengthCm(), selection.maxWeightG()));
				} else {
					boolean lengthChanged = collection.get().record(selection.maxLengthCm(), command.fishingDate(), now);
					boolean weightChanged = collection.get().recordWeight(selection.maxWeightG());
					if (lengthChanged || weightChanged) events.add(new CollectionEvent(
							lengthChanged ? "NEW_MAX_LENGTH" : "NEW_MAX_WEIGHT", species.id(), species.name(),
							lengthChanged ? selection.maxLengthCm() : null,
							weightChanged ? selection.maxWeightG() : null));
				}
			}
			reports.flush();
			return new Result(reportId, command.catchStatus(), "PRIVATE", "PRIVATE", now, List.copyOf(events));
		});
		if (result == null) throw new IllegalStateException("Catch report transaction returned no result");
		return result;
	}

	private Result replay(CatchReport report, CreateCatchReportCommand command) {
		var saved = reports.findReportSpecies(report.id()).stream()
				.sorted(Comparator.comparingInt(CatchReportSpecies::sortOrder)).toList();
		var requested = command.species();
		boolean same = report.hasContent(command.catchStatus(), command.waterType(), command.fishingDate(),
				command.fishingTime(), command.memo()) && saved.size() == requested.size();
		for (int index = 0; same && index < saved.size(); index++) {
			var stored = saved.get(index);
			var item = requested.get(index);
			same = Objects.equals(stored.speciesId(), item.speciesId())
					&& Objects.equals(stored.customName(), item.customName())
					&& sameLength(stored.maxLengthCm(), item.maxLengthCm())
					&& sameLength(stored.maxWeightG(), item.maxWeightG());
		}
		// 같은 키의 다른 내용은 이미 저장된 조황을 덮거나 새로 만들지 않는다.
		if (!same) throw new ApiException(ErrorCode.STATE_CONFLICT);
		// 도감 연출은 최초 응답에서만 제공한다. 재시도 응답은 저장된 조황만 확인한다.
		return new Result(report.id(), report.catchStatus(), report.visibility(), report.locationVisibility(),
				report.createdAt(), List.of());
	}

	private static boolean sameLength(BigDecimal stored, BigDecimal requested) {
		return stored == null ? requested == null : requested != null && stored.compareTo(requested) == 0;
	}

	public record Result(long reportId, String catchStatus, String visibility,
			String locationVisibility, Instant createdAt, java.util.List<CollectionEvent> collectionEvents) { }
	public record CollectionEvent(String type, long speciesId, String speciesName,
			java.math.BigDecimal maxLengthCm, java.math.BigDecimal maxWeightG) { }
}
