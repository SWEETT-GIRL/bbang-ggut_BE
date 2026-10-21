WITH cand AS ((SELECT s.id FROM store s WHERE s.status = 'ACTIVE' AND s.name_normalized LIKE '%' || :name0 || '%'
   UNION SELECT a.store_id AS id FROM store_name_alias a WHERE a.alias_normalized LIKE '%' || :name0 || '%')
INTERSECT (SELECT s.id FROM store s WHERE s.status = 'ACTIVE' AND s.name_normalized LIKE '%' || :name1 || '%'
   UNION SELECT a.store_id AS id FROM store_name_alias a WHERE a.alias_normalized LIKE '%' || :name1 || '%'))
SELECT t.id, t.popularity_score, t.name_score, t.alias_score, t.region_score, t.address_score,
       t.distance_m,
       (greatest(t.name_score, t.alias_score) + t.region_score + t.address_score)
       * (1 + :popularityFactor * ln(1 + t.popularity_score) + 0) AS score
FROM (
  SELECT s.id, s.popularity_score,
         greatest(CASE WHEN s.name_normalized = :whole THEN :nameExact WHEN s.name_normalized LIKE :whole || '%' THEN :namePrefix WHEN s.name_normalized LIKE '%' || :whole || '%' THEN :nameContains ELSE 0 END,
                  CASE WHEN s.name_normalized = :name0 THEN :nameExact WHEN s.name_normalized LIKE :name0 || '%' THEN :namePrefix WHEN s.name_normalized LIKE '%' || :name0 || '%' THEN :nameContains ELSE 0 END,
                  CASE WHEN s.name_normalized = :name1 THEN :nameExact WHEN s.name_normalized LIKE :name1 || '%' THEN :namePrefix WHEN s.name_normalized LIKE '%' || :name1 || '%' THEN :nameContains ELSE 0 END) AS name_score,
         COALESCE((SELECT max(greatest(CASE WHEN a.alias_normalized = :whole THEN :aliasExact WHEN a.alias_normalized LIKE :whole || '%' THEN :aliasPrefix WHEN a.alias_normalized LIKE '%' || :whole || '%' THEN :aliasContains ELSE 0 END, CASE WHEN a.alias_normalized = :name0 THEN :aliasExact WHEN a.alias_normalized LIKE :name0 || '%' THEN :aliasPrefix WHEN a.alias_normalized LIKE '%' || :name0 || '%' THEN :aliasContains ELSE 0 END, CASE WHEN a.alias_normalized = :name1 THEN :aliasExact WHEN a.alias_normalized LIKE :name1 || '%' THEN :aliasPrefix WHEN a.alias_normalized LIKE '%' || :name1 || '%' THEN :aliasContains ELSE 0 END))
                   FROM store_name_alias a WHERE a.store_id = s.id AND (a.alias_normalized LIKE '%' || :whole || '%' OR a.alias_normalized LIKE '%' || :name0 || '%' OR a.alias_normalized LIKE '%' || :name1 || '%')), 0) AS alias_score,
         0 AS region_score,
         0 AS address_score,
         CAST(NULL AS double precision) AS distance_m
  FROM store s
  JOIN cand ON cand.id = s.id
  WHERE s.status = 'ACTIVE'
) t
ORDER BY score DESC, t.popularity_score DESC, t.id ASC
LIMIT :limit OFFSET :offset