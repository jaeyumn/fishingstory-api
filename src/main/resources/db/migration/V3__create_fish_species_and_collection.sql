CREATE TABLE fishingstory.fish_species (
    species_id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    name_key VARCHAR(100) NOT NULL,
    scientific_name VARCHAR(200) NOT NULL,
    water_type VARCHAR(20) NOT NULL,
    display_order INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_fish_species_name_key UNIQUE (name_key),
    CONSTRAINT uq_fish_species_scientific_name UNIQUE (scientific_name),
    CONSTRAINT uq_fish_species_display_order UNIQUE (display_order),
    CONSTRAINT ck_fish_species_name CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT ck_fish_species_name_key CHECK (name_key = lower(name)),
    CONSTRAINT ck_fish_species_scientific_name CHECK (scientific_name ~ '[^[:space:]]'),
    CONSTRAINT ck_fish_species_water_type CHECK (water_type IN ('SEA', 'FRESHWATER', 'BOTH')),
    CONSTRAINT ck_fish_species_display_order CHECK (display_order >= 0)
);

CREATE TABLE fishingstory.fish_species_alias (
    alias_id BIGINT PRIMARY KEY,
    species_id BIGINT NOT NULL,
    alias VARCHAR(100) NOT NULL,
    alias_key VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_fish_species_alias_key UNIQUE (alias_key),
    CONSTRAINT uq_fish_species_alias_species UNIQUE (species_id, alias_key),
    CONSTRAINT ck_fish_species_alias CHECK (alias ~ '[^[:space:]]'),
    CONSTRAINT ck_fish_species_alias_key CHECK (alias_key = lower(alias))
);

CREATE INDEX idx_fish_species_alias_species
    ON fishingstory.fish_species_alias (species_id);

CREATE TABLE fishingstory.catch_report_species (
    report_species_id BIGINT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    species_id BIGINT NOT NULL,
    max_length_cm NUMERIC(5, 1),
    sort_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_catch_report_species UNIQUE (report_id, species_id),
    CONSTRAINT uq_catch_report_species_order UNIQUE (report_id, sort_order),
    CONSTRAINT ck_catch_report_species_length CHECK (
        max_length_cm IS NULL OR (max_length_cm > 0 AND max_length_cm <= 1000.0)
    ),
    CONSTRAINT ck_catch_report_species_order CHECK (sort_order >= 0)
);

CREATE INDEX idx_catch_report_species_species_report
    ON fishingstory.catch_report_species (species_id, report_id);

CREATE TABLE fishingstory.user_fish_collection (
    collection_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    species_id BIGINT NOT NULL,
    max_length_cm NUMERIC(5, 1),
    first_fishing_date DATE NOT NULL,
    last_fishing_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_user_fish_collection UNIQUE (user_id, species_id),
    CONSTRAINT ck_user_fish_collection_length CHECK (
        max_length_cm IS NULL OR (max_length_cm > 0 AND max_length_cm <= 1000.0)
    ),
    CONSTRAINT ck_user_fish_collection_dates CHECK (first_fishing_date <= last_fishing_date)
);

CREATE INDEX idx_user_fish_collection_user_recent
    ON fishingstory.user_fish_collection (user_id, last_fishing_date DESC, species_id);

-- 1~9999는 Migration 관리 Master ID 영역이다. 애플리케이션 Snowflake ID와 분리한다.
INSERT INTO fishingstory.fish_species
    (species_id, name, name_key, scientific_name, water_type, display_order)
VALUES
    (1001, '감성돔', '감성돔', 'Acanthopagrus schlegelii', 'SEA', 10),
    (1002, '참돔', '참돔', 'Pagrus major', 'SEA', 20),
    (1003, '조피볼락', '조피볼락', 'Sebastes schlegelii', 'SEA', 30),
    (1004, '농어', '농어', 'Lateolabrax japonicus', 'SEA', 40),
    (1005, '고등어', '고등어', 'Scomber japonicus', 'SEA', 50),
    (2001, '붕어', '붕어', 'Carassius auratus', 'FRESHWATER', 110),
    (2002, '잉어', '잉어', 'Cyprinus carpio', 'FRESHWATER', 120);

INSERT INTO fishingstory.fish_species_alias
    (alias_id, species_id, alias, alias_key)
VALUES
    (3001, 1003, '우럭', '우럭');
