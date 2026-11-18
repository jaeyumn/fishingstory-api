CREATE TABLE fishingstory.magazines (
    magazine_id BIGINT PRIMARY KEY,
    studio_id UUID NOT NULL UNIQUE,
    source_revision INTEGER NOT NULL CHECK(source_revision > 0),
    revision BIGINT NOT NULL DEFAULT 1 CHECK(revision > 0),
    title VARCHAR(200) NOT NULL CHECK(char_length(btrim(title)) BETWEEN 1 AND 200),
    content TEXT NOT NULL CHECK(char_length(btrim(content)) BETWEEN 1 AND 100000),
    category VARCHAR(30) NOT NULL CHECK(category IN ('encyclopedia','seasonal')),
    subcategory VARCHAR(30) NOT NULL,
    images_json TEXT NOT NULL DEFAULT '[]',
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    CHECK (NOT published OR published_at IS NOT NULL)
);
CREATE INDEX idx_magazines_published ON fishingstory.magazines(published_at DESC,magazine_id DESC) WHERE published;
CREATE TABLE fishingstory.magazine_versions (
    magazine_id BIGINT NOT NULL, revision BIGINT NOT NULL, source_revision INTEGER NOT NULL,
    title VARCHAR(200) NOT NULL, content TEXT NOT NULL, category VARCHAR(30) NOT NULL,
    subcategory VARCHAR(30) NOT NULL, images_json TEXT NOT NULL, published BOOLEAN NOT NULL,
    published_at TIMESTAMPTZ, recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(magazine_id,revision)
);
CREATE TABLE fishingstory.magazine_images (
    image_id UUID PRIMARY KEY, owner_id BIGINT NOT NULL,
    source_hash CHAR(64) NOT NULL, object_key VARCHAR(200) NOT NULL UNIQUE,
    bytes INTEGER NOT NULL CHECK(bytes BETWEEN 1 AND 5242880),
    width INTEGER NOT NULL CHECK(width BETWEEN 1 AND 2400),
    height INTEGER NOT NULL CHECK(height BETWEEN 1 AND 2400),
    created_at TIMESTAMPTZ NOT NULL
);
