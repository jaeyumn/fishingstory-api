package kr.fishingstory.magazine.query.infrastructure.mybatis;

import java.util.List;
import org.apache.ibatis.annotations.*;
import kr.fishingstory.magazine.query.dto.MagazineView;

@Mapper
public interface MagazineQueryMapper {
    @Select("SELECT EXISTS(SELECT 1 FROM fishingstory.users WHERE user_id=#{userId} AND status='ACTIVE' AND (NOT #{admin} OR role='ADMIN'))")
    boolean allowed(@Param("userId") long userId,@Param("admin") boolean admin);
    @Select("SELECT magazine_id,cast(studio_id as text) AS studio_id,source_revision,revision,title,content,category,subcategory,images_json,published,created_at,updated_at,published_at FROM fishingstory.magazines WHERE magazine_id=#{id} AND (published OR #{admin})")
    MagazineView.Raw detail(@Param("id") long id,@Param("admin") boolean admin);
    @Select("SELECT magazine_id,cast(studio_id as text) AS studio_id,source_revision,revision,title,content,category,subcategory,images_json,published,created_at,updated_at,published_at FROM fishingstory.magazines WHERE studio_id=cast(#{id} as uuid)")
    MagazineView.Raw bySource(@Param("id") String id);
    @Select("SELECT cast(magazine_id as text) AS magazine_id,title,category,subcategory,published_at FROM fishingstory.magazines WHERE published ORDER BY published_at DESC,magazine_id DESC LIMIT #{limit} OFFSET #{offset}")
    List<MagazineView.Item> list(@Param("limit") int limit,@Param("offset") long offset);
}
