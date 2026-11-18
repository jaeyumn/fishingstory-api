package kr.fishingstory.magazine.command.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name="magazines", schema="fishingstory")
public class Magazine {
    @Id @Column(name="magazine_id") private Long id;
    @Column(name="studio_id",nullable=false) private UUID studioId;
    @Column(name="source_revision",nullable=false) private int sourceRevision;
    @Column(nullable=false) private long revision;
    @Column(nullable=false,length=200) private String title;
    @Column(nullable=false,columnDefinition="text") private String content;
    @Column(nullable=false,length=30) private String category;
    @Column(nullable=false,length=30) private String subcategory;
    @Column(name="images_json",nullable=false,columnDefinition="text") private String imagesJson;
    @Column(nullable=false) private boolean published;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Column(name="published_at") private Instant publishedAt;
    protected Magazine() { }
    public Magazine(long id, UUID studioId, int sourceRevision, String title, String content,
            String category, String subcategory, String imagesJson, Instant now) {
        this.id=id; this.studioId=studioId; this.createdAt=now; this.revision=0;
        change(sourceRevision,title,content,category,subcategory,imagesJson,now);
    }
    public void change(int sourceRevision,String title,String content,String category,String subcategory,String imagesJson,Instant now) {
        this.sourceRevision=sourceRevision;this.title=title;this.content=content;this.category=category;
        this.subcategory=subcategory;this.imagesJson=imagesJson;this.updatedAt=now;this.revision++;
    }
    public void publication(boolean value,Instant now) {
        published=value;publishedAt=value?now:null;updatedAt=now;revision++;
    }
    public long id(){return id;} public long revision(){return revision;}
    public int sourceRevision(){return sourceRevision;} public boolean published(){return published;}
    public boolean matches(int sourceRevision,String title,String content,String category,String subcategory,String imagesJson) {
        return this.sourceRevision==sourceRevision && this.title.equals(title) && this.content.equals(content)
            && this.category.equals(category) && this.subcategory.equals(subcategory) && this.imagesJson.equals(imagesJson);
    }
}
