package kr.fishingstory.user.query.infrastructure.mybatis;

import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import kr.fishingstory.user.query.dto.CurrentUser;

@Mapper
public interface UserQueryMapper {
	@Select("SELECT access_token_generation FROM fishingstory.users WHERE user_id = #{userId}")
	Optional<Long> findAccessTokenGeneration(@Param("userId") long userId);

	@Select("""
			SELECT u.user_id, u.nickname, a.provider, a.email, u.role
			FROM fishingstory.users u
			JOIN fishingstory.user_auth a ON a.user_id = u.user_id
			WHERE u.user_id = #{userId}
			  AND u.status = 'ACTIVE'
			  AND a.provider = 'EMAIL'
			""")
	Optional<CurrentUser> findCurrentEmailUser(@Param("userId") long userId);
}
