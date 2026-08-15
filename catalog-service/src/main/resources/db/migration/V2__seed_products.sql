-- Phase 8: seeds ~1000 realistic-looking products for the checkout UI
-- to browse against (the catalog otherwise held a single test row from
-- earlier live verification). Generated via generate_series rather than
-- 1000 literal INSERT statements — same result, far less to maintain.
-- No idempotency guard needed: Flyway only runs each versioned
-- migration once per schema, ever.
INSERT INTO product (name, description, category_id, price, active, created_at, updated_at)
SELECT
    initcap(adjectives.word) || ' ' || initcap(nouns.word) || ' ' || (100 + n),
    'A fine ' || adjectives.word || ' ' || nouns.word || ', item #' || (100 + n) || ' in the catalog.',
    1 + (n % 10),
    round((5 + (n % 496) + (n % 3) * 0.33)::numeric, 2),
    TRUE,
    now(),
    now()
FROM generate_series(1, 1000) AS n
CROSS JOIN LATERAL (
    SELECT (ARRAY['sturdy','compact','elegant','rugged','lightweight','premium','classic','modern','durable','sleek'])[1 + (n % 10)] AS word
) AS adjectives
CROSS JOIN LATERAL (
    SELECT (ARRAY['backpack','lamp','chair','mug','keyboard','notebook','speaker','jacket','bottle','headphones','desk','shelf','blanket','wallet','watch','sneakers','tent','bicycle','camera','toolkit'])[1 + (n % 20)] AS word
) AS nouns;
