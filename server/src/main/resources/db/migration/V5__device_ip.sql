-- The address the agent reports for itself; last_ip stays the address the
-- server saw the check-in from (usually a NAT gateway or proxy hop).
ALTER TABLE public.device
    ADD COLUMN ip character varying(64);
