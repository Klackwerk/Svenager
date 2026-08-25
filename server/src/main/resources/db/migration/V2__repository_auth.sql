-- Repository credentials: explicit auth type plus HTTPS username/token.
-- Existing deploy keys keep working as SSH_KEY.
ALTER TABLE public.ansible_repository
    ADD COLUMN auth_type character varying(255),
    ADD COLUMN auth_username character varying(190),
    ADD COLUMN auth_secret_enc text;

UPDATE public.ansible_repository
    SET auth_type = CASE WHEN deploy_key_private_enc IS NOT NULL THEN 'SSH_KEY' ELSE 'NONE' END;

ALTER TABLE public.ansible_repository
    ALTER COLUMN auth_type SET NOT NULL;
