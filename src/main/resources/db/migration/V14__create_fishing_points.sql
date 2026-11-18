-- The registry is empty in every environment until reviewed data is registered separately.
-- Demo rows are opt-in dev data; public queries exclude them outside dev.
CREATE TABLE fishingstory.fishing_regions (
    region_id BIGINT PRIMARY KEY CHECK (region_id > 0),
    name VARCHAR(80) NOT NULL UNIQUE,
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    is_demo BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_fishing_regions_name CHECK (name = btrim(name) AND name <> '' AND name !~ '[[:cntrl:]]')
);

CREATE TABLE fishingstory.fishing_zones (
    zone_id BIGINT PRIMARY KEY CHECK (zone_id > 0),
    region_id BIGINT NOT NULL CHECK (region_id > 0),
    name VARCHAR(80) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    is_demo BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (region_id, name),
    CONSTRAINT ck_fishing_zones_name CHECK (name = btrim(name) AND name <> '' AND name !~ '[[:cntrl:]]')
);

CREATE TABLE fishingstory.fishing_points (
    point_id BIGINT PRIMARY KEY CHECK (point_id > 0),
    zone_id BIGINT NOT NULL CHECK (zone_id > 0),
    name VARCHAR(120) NOT NULL,
    water_type VARCHAR(20) NOT NULL CHECK (water_type IN ('SEA', 'FRESHWATER')),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    coordinate public.geography(Point, 4326) GENERATED ALWAYS AS
        (CASE WHEN longitude BETWEEN -180 AND 180 AND latitude BETWEEN -90 AND 90
            THEN public.ST_SetSRID(public.ST_MakePoint(longitude, latitude), 4326)::public.geography
         END) STORED NOT NULL,
    address VARCHAR(300),
    description VARCHAR(2000),
    publication_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'HIDDEN')),
    is_demo BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_fishing_points_name CHECK (name = btrim(name) AND name <> '' AND name !~ '[[:cntrl:]]')
);

CREATE TABLE fishingstory.fishing_point_aliases (
    alias_id BIGINT PRIMARY KEY CHECK (alias_id > 0),
    point_id BIGINT NOT NULL CHECK (point_id > 0),
    alias VARCHAR(120) NOT NULL,
    UNIQUE (point_id, alias),
    CONSTRAINT ck_fishing_point_aliases_name CHECK (alias = btrim(alias) AND alias <> '' AND alias !~ '[[:cntrl:]]')
);

CREATE INDEX idx_fishing_points_published_name
    ON fishingstory.fishing_points (name COLLATE "C", point_id)
    WHERE publication_status = 'PUBLISHED';
CREATE INDEX idx_fishing_points_zone ON fishingstory.fishing_points (zone_id);

COMMENT ON TABLE fishingstory.fishing_points IS
    'Single operator-managed registry. Registration does not guarantee access, fishing permission or safety.';
COMMENT ON COLUMN fishingstory.fishing_points.coordinate IS
    'Generated from checked longitude (X) and latitude (Y); not a catch-report location.';
