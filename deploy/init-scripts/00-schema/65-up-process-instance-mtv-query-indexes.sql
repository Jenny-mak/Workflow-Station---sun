-- Main Table View SQL query path: indexes for function_unit_code + start_time and JSONB variables.
-- Supports DB-authoritative filter/sort/page (no in-memory 5000 cap).

CREATE INDEX IF NOT EXISTS idx_up_pi_fu_code_start_time
    ON up_process_instance (function_unit_code, start_time DESC NULLS LAST);

CREATE INDEX IF NOT EXISTS idx_up_pi_variables_gin
    ON up_process_instance USING GIN (variables jsonb_path_ops);

COMMENT ON INDEX idx_up_pi_fu_code_start_time IS
    'Portal Main Table View listing by FU ordered by start_time';
COMMENT ON INDEX idx_up_pi_variables_gin IS
    'Accelerate JSONB containment / key lookups on process variables for Main Table View filters';
