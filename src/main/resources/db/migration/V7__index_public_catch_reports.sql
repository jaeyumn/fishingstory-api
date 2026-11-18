CREATE INDEX idx_catch_reports_public_recent
    ON fishingstory.catch_reports (fishing_date DESC, created_at DESC, report_id DESC)
    WHERE visibility = 'PUBLIC';
