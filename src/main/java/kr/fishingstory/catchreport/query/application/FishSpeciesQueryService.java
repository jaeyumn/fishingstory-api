package kr.fishingstory.catchreport.query.application;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import kr.fishingstory.catchreport.query.dto.FishSpeciesItem;
import kr.fishingstory.catchreport.query.infrastructure.mybatis.FishSpeciesQueryMapper;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.user.query.infrastructure.mybatis.UserQueryMapper;

@Service
public class FishSpeciesQueryService {
	private final UserQueryMapper users;
	private final FishSpeciesQueryMapper species;

	public FishSpeciesQueryService(UserQueryMapper users, FishSpeciesQueryMapper species) {
		this.users = users;
		this.species = species;
	}

	public List<FishSpeciesItem> find(long userId, String waterType, String input) {
		if (!("SEA".equals(waterType) || "FRESHWATER".equals(waterType))) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		if (users.findCurrentEmailUser(userId).isEmpty()) throw new ApiException(ErrorCode.ACCESS_DENIED);
		if (input != null && input.indexOf('\0') >= 0) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		String query = input == null || input.isBlank() ? null : input.strip().toLowerCase(Locale.ROOT);
		return List.copyOf(species.find(waterType, query));
	}
}
