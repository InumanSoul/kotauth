-- The SCIM wire dialect this key's client speaks. Selection is explicit rather than
-- sniffed from headers: a misread User-Agent would silently change how a payload is
-- interpreted, and the operator configuring the connection already knows the answer.
-- 'rfc' is a pass-through, so every existing key keeps its current behaviour.
ALTER TABLE api_keys
    ADD COLUMN scim_dialect VARCHAR(16) NOT NULL DEFAULT 'rfc';
