package kr.fishingstory.catchreport.command.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import kr.fishingstory.catchreport.command.domain.CatchReportSpecies;
import kr.fishingstory.catchreport.command.domain.FishSpecies;
import kr.fishingstory.catchreport.command.domain.UserFishCollection;
import kr.fishingstory.catchreport.command.infrastructure.jpa.CatchReportCommandStore;
import kr.fishingstory.catchreport.command.infrastructure.jpa.CatchReportImageStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.common.id.SnowflakeIdGenerator;
import kr.fishingstory.user.infrastructure.jpa.UserCommandStore;

@Service
public class ChangeCatchReportService {
	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private final UserCommandStore users;
	private final CatchReportCommandStore reports;
	private final CatchReportImageStore images;
	private final SnowflakeIdGenerator ids;
	private final TransactionTemplate transaction;

	public ChangeCatchReportService(UserCommandStore users, CatchReportCommandStore reports,
			CatchReportImageStore images,
			SnowflakeIdGenerator ids, PlatformTransactionManager manager) {
		this.users = users;
		this.reports = reports;
		this.images = images;
		this.ids = ids;
		this.transaction = new TransactionTemplate(manager);
	}

	public void update(long userId, long reportId, CreateCatchReportCommand command) {
		if (command == null || command.fishingDate().isAfter(LocalDate.now(SERVICE_ZONE))) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		transaction.executeWithoutResult(status -> {
			lockActiveUser(userId);
			var report = reports.findMine(userId, reportId)
					.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			var previous = reports.findReportSpecies(reportId);
			Set<Long> previousIds = previous.stream().map(CatchReportSpecies::speciesId)
					.filter(java.util.Objects::nonNull)
					.collect(Collectors.toSet());
			var selectedIds = command.species().stream()
					.map(CreateCatchReportCommand.SpeciesSelection::speciesId).filter(java.util.Objects::nonNull).toList();
			Map<Long, FishSpecies> master = reports.findSpecies(selectedIds).stream()
					.collect(Collectors.toMap(FishSpecies::id, Function.identity()));
			if (master.size() != selectedIds.size() || command.species().stream().anyMatch(item -> {
				if (item.speciesId() == null) return false;
				FishSpecies species = master.get(item.speciesId());
				return species == null || !species.matchesWater(command.waterType())
						|| (!previousIds.contains(item.speciesId()) && !species.supports(command.waterType()));
			})) throw new ApiException(ErrorCode.VALIDATION_ERROR);

			Instant now = Instant.now();
			report.update(command.catchStatus(), command.waterType(), command.fishingDate(),
					command.fishingTime(), command.memo(), now);
			previous.forEach(reports::delete);
			reports.flush();
			for (int index = 0; index < command.species().size(); index++) {
				var item = command.species().get(index);
				reports.insert(new CatchReportSpecies(ids.nextId(), reportId, item.speciesId(),
						item.customName(), item.maxLengthCm(), item.maxWeightG(), index, now));
			}
			reports.flush();
			Set<Long> affected = new HashSet<>(previousIds);
			affected.addAll(selectedIds);
			for (long speciesId : affected) rebuild(userId, speciesId, now);
			reports.flush();
		});
	}

	public void changeVisibility(long userId, long reportId, String visibility) {
		if (!"PRIVATE".equals(visibility) && !"PUBLIC".equals(visibility)) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		transaction.executeWithoutResult(status -> {
			lockActiveUser(userId);
			var report = reports.findMine(userId, reportId)
					.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			report.changeVisibility(visibility, Instant.now());
			reports.flush();
		});
	}

	public void delete(long userId, long reportId) {
		transaction.executeWithoutResult(status -> {
			lockActiveUser(userId);
			var report = reports.findMine(userId, reportId)
					.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			List<CatchReportSpecies> selected = reports.findReportSpecies(reportId);
			Set<Long> affected = selected.stream().map(CatchReportSpecies::speciesId)
					.filter(java.util.Objects::nonNull)
					.collect(Collectors.toSet());
			selected.forEach(reports::delete);
			Instant now = Instant.now();
			images.allForReport(reportId).forEach(image -> image.markForDelete(now));
			reports.delete(report);
			reports.flush();
			images.flush();
			for (long speciesId : affected) rebuild(userId, speciesId, now);
			reports.flush();
		});
	}

	private void lockActiveUser(long userId) {
		var user = users.lock(userId).orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED));
		if (!"ACTIVE".equals(user.status())) throw new ApiException(ErrorCode.ACCESS_DENIED);
	}

	private void rebuild(long userId, long speciesId, Instant now) {
		Object[] summary = reports.collectionSummary(userId, speciesId);
		var current = reports.lockCollection(userId, speciesId);
		if (((Number) summary[0]).longValue() == 0) {
			current.ifPresent(reports::delete);
			return;
		}
		LocalDate first = (LocalDate) summary[1];
		LocalDate last = (LocalDate) summary[2];
		BigDecimal maximum = (BigDecimal) summary[3];
		if (current.isPresent()) {
			current.get().replace(maximum, first, last, now);
			current.get().replaceWeight((BigDecimal) summary[4]);
		}
		else {
			var collection = new UserFishCollection(ids.nextId(), userId, speciesId, maximum, first, now);
			collection.replace(maximum, first, last, now);
			collection.replaceWeight((BigDecimal) summary[4]);
			reports.insert(collection);
		}
	}
}
