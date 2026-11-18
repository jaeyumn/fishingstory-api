package kr.fishingstory.catchreport.query.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.catchreport.query.dto.SpeciesCatalogItem;
import kr.fishingstory.catchreport.query.infrastructure.mybatis.SpeciesCatalogMapper;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.user.query.infrastructure.mybatis.UserQueryMapper;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SpeciesCatalogService {
    private final UserQueryMapper users;
    private final SpeciesCatalogMapper catalog;
    private final ObjectMapper json;
    public SpeciesCatalogService(UserQueryMapper users, SpeciesCatalogMapper catalog, ObjectMapper json) {
        this.users = users;
        this.catalog = catalog;
        this.json = json;
    }
    public Catalog find(long userId) {
        checkUser(userId);
        var aliases = catalog.aliases().stream().collect(Collectors.groupingBy(
                SpeciesCatalogItem.Alias::speciesId, Collectors.mapping(SpeciesCatalogItem.Alias::alias,
                        Collectors.toList())));
        var items = catalog.catalog().stream().map(row -> row.withAliases(
                aliases.getOrDefault(row.speciesId(), List.of()))).toList();
        try {
            String version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(items)));
            return new Catalog(version, items);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
    public Detail detail(long userId, long speciesId) {
        checkUser(userId);
        if (speciesId <= 0) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        var item = catalog.detail(userId, speciesId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return new Detail(item.withAliases(catalog.speciesAliases(speciesId)),
                catalog.description(speciesId), List.copyOf(catalog.regulations(speciesId)));
    }
    private void checkUser(long userId) {
        if (users.findCurrentEmailUser(userId).isEmpty()) throw new ApiException(ErrorCode.ACCESS_DENIED);
    }
    public record Catalog(String version, List<SpeciesCatalogItem> items) { }
    public record Detail(SpeciesCatalogItem species, SpeciesCatalogItem.Description description,
            List<SpeciesCatalogItem.Regulation> regulations) { }
}
