-- Pagination indexes for Completed Tasks (ACT_HI_TASKINST by assignee + end time)
-- and My Requests (up_process_instance by initiator + status + start time).
-- Partial index on historic tasks matches WHERE END_TIME_ IS NOT NULL.

CREATE INDEX IF NOT EXISTS idx_hi_taskinst_assignee_end
    ON act_hi_taskinst (assignee_, end_time_ DESC)
    WHERE end_time_ IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_up_pi_user_status_start
    ON up_process_instance (start_user_id, status, start_time DESC);
