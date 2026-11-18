package kr.fishingstory.conditions;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FishingConditionsAccessMapper {
	@Select("SELECT EXISTS (SELECT 1 FROM fishingstory.users WHERE user_id=#{userId} AND status='ACTIVE')")
	boolean active(@Param("userId") long userId);
}
