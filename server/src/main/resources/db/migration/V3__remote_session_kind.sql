-- Remote sessions carry either a VNC screen or an interactive shell.
-- Existing rows are VNC.
ALTER TABLE public.remote_session
    ADD COLUMN kind character varying(255);

UPDATE public.remote_session SET kind = 'VNC';

ALTER TABLE public.remote_session
    ALTER COLUMN kind SET NOT NULL;
