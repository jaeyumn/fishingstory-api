-- Registry writes are migrations (plus the explicitly enabled dev seeder); there are no physical FKs.
-- Run in each migration transaction, including future data migrations and changes to parent rows.
DO $$
BEGIN
    IF to_regclass('fishingstory.fishing_point_aliases') IS NULL THEN
        RETURN;
    END IF;
    IF EXISTS (
        SELECT 1 FROM fishingstory.fishing_zones z
        LEFT JOIN fishingstory.fishing_regions r ON r.region_id = z.region_id
        WHERE r.region_id IS NULL OR r.is_demo <> z.is_demo
    ) OR EXISTS (
        SELECT 1 FROM fishingstory.fishing_points p
        LEFT JOIN fishingstory.fishing_zones z ON z.zone_id = p.zone_id
        WHERE z.zone_id IS NULL OR z.is_demo <> p.is_demo
    ) OR EXISTS (
        SELECT 1 FROM fishingstory.fishing_point_aliases a
        LEFT JOIN fishingstory.fishing_points p ON p.point_id = a.point_id
        WHERE p.point_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Fishing point registry needs existing region, zone and point references with consistent demo flags; review the migration before retrying';
    END IF;
END;
$$;
