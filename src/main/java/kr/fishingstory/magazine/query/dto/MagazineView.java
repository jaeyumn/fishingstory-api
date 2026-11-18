package kr.fishingstory.magazine.query.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class MagazineView {
    private MagazineView() { }
    public record ImageRef(UUID imageId,String altText) { }
    public record Raw(long magazineId,String studioId,int sourceRevision,long revision,String title,String content,
            String category,String subcategory,String imagesJson,boolean published,Instant createdAt,Instant updatedAt,Instant publishedAt) {
        public Detail detail(ObjectMapper json){return new Detail(Long.toString(magazineId),studioId,sourceRevision,revision,title,content,
            category,subcategory,List.of(json.readValue(imagesJson,ImageRef[].class)),published,createdAt,updatedAt,publishedAt);}
    }
    public record Detail(String magazineId,String studioId,int sourceRevision,long revision,String title,String content,
            String category,String subcategory,List<ImageRef> images,boolean published,Instant createdAt,Instant updatedAt,Instant publishedAt) { }
    public record Item(String magazineId,String title,String category,String subcategory,Instant publishedAt) { }
    public record Page(List<Item> items,boolean hasNext) { }
}
