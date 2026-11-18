package kr.fishingstory.magazine.image;

public interface MagazineImageStorage {
    void put(String key,byte[] png);
    byte[] get(String key,int maxBytes);
}
