-- V70: One identifier namespace per workspace, enforced by the database.
--
-- Usernames and emails were unique each on their own, so one user's username could equal another
-- user's email. Sign-in by "username or email" cannot tell those two apart. The application
-- refuses such a pair on write (IdentifierCollisionCheck), but it reads before it writes, so two
-- concurrent writes could both pass it.
--
-- user_identifiers holds every username and email in a workspace under one primary key, and a
-- trigger on users keeps it current, so every write path is covered. The loser of a concurrent
-- write waits on the key, then fails with unique_violation.
--
-- Pairs that already collide are left alone rather than failing the migration: sign-in already
-- refuses to guess between them, and an administrator renames one. The identifier stays with the
-- user who holds it as a username, and passes to the other user once the first lets it go.

CREATE TABLE user_identifiers (
    tenant_id  INTEGER NOT NULL,
    identifier TEXT    NOT NULL,
    user_id    INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (tenant_id, identifier)
);

CREATE INDEX idx_user_identifiers_user ON user_identifiers (user_id);

INSERT INTO user_identifiers (tenant_id, identifier, user_id)
SELECT tenant_id, lower(username), id FROM users;

INSERT INTO user_identifiers (tenant_id, identifier, user_id)
SELECT tenant_id, lower(email), id FROM users WHERE email <> ''
ON CONFLICT (tenant_id, identifier) DO NOTHING;

-- Claims [value] for [uid]. A value another user holds is a unique_violation.
CREATE FUNCTION kauth_claim_identifier(tid INTEGER, uid INTEGER, value TEXT) RETURNS void AS $$
DECLARE
    holder INTEGER;
BEGIN
    IF value IS NULL OR value = '' THEN
        RETURN;
    END IF;
    INSERT INTO user_identifiers (tenant_id, identifier, user_id)
    VALUES (tid, value, uid)
    ON CONFLICT (tenant_id, identifier) DO NOTHING;
    IF NOT FOUND THEN
        SELECT user_id INTO holder FROM user_identifiers WHERE tenant_id = tid AND identifier = value;
        IF holder IS DISTINCT FROM uid THEN
            RAISE EXCEPTION 'That username or email address is already in use in this workspace.'
                USING ERRCODE = 'unique_violation', CONSTRAINT = 'user_identifiers_pkey';
        END IF;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Lets [uid] go of [value], handing it to another user who still uses it (a pair that predates V70).
CREATE FUNCTION kauth_release_identifier(tid INTEGER, uid INTEGER, value TEXT) RETURNS void AS $$
BEGIN
    DELETE FROM user_identifiers WHERE tenant_id = tid AND identifier = value AND user_id = uid;
    IF FOUND THEN
        INSERT INTO user_identifiers (tenant_id, identifier, user_id)
        SELECT tenant_id, value, id
        FROM users
        WHERE tenant_id = tid AND id <> uid AND (lower(username) = value OR lower(email) = value)
        ORDER BY id
        LIMIT 1
        ON CONFLICT (tenant_id, identifier) DO NOTHING;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION kauth_users_identifiers() RETURNS trigger AS $$
DECLARE
    old_value TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM kauth_release_identifier(OLD.tenant_id, OLD.id, lower(OLD.username));
        PERFORM kauth_release_identifier(OLD.tenant_id, OLD.id, lower(OLD.email));
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        FOREACH old_value IN ARRAY ARRAY[lower(OLD.username), lower(OLD.email)] LOOP
            IF old_value <> lower(NEW.username) AND old_value <> lower(NEW.email) THEN
                PERFORM kauth_release_identifier(NEW.tenant_id, NEW.id, old_value);
            END IF;
        END LOOP;
    END IF;

    -- On update only a value that changed is claimed, so a pair from before V70 can still edit
    -- everything except the value it shares.
    IF TG_OP = 'INSERT' OR lower(NEW.username) IS DISTINCT FROM lower(OLD.username) THEN
        PERFORM kauth_claim_identifier(NEW.tenant_id, NEW.id, lower(NEW.username));
    END IF;
    IF TG_OP = 'INSERT' OR lower(NEW.email) IS DISTINCT FROM lower(OLD.email) THEN
        PERFORM kauth_claim_identifier(NEW.tenant_id, NEW.id, lower(NEW.email));
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER users_claim_identifiers
    AFTER INSERT OR UPDATE OF username, email ON users
    FOR EACH ROW EXECUTE FUNCTION kauth_users_identifiers();

CREATE TRIGGER users_release_identifiers
    BEFORE DELETE ON users
    FOR EACH ROW EXECUTE FUNCTION kauth_users_identifiers();
