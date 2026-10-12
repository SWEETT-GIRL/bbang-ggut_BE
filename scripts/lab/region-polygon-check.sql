-- Synthetic geometry checks only. Run on postgres-lab; all fixtures roll back.
BEGIN;
CREATE TEMP TABLE polygon_test_boundary(code text, geom geometry(MultiPolygon,4326));
INSERT INTO polygon_test_boundary VALUES
 ('left',ST_Multi(ST_GeomFromText('POLYGON((0 0,1 0,1 1,0 1,0 0))',4326))),
 ('right',ST_Multi(ST_GeomFromText('POLYGON((1 0,2 0,2 1,1 1,1 0))',4326))),
 ('hole',ST_Multi(ST_GeomFromText('POLYGON((3 0,6 0,6 3,3 3,3 0),(4 1,4 2,5 2,5 1,4 1))',4326))),
 ('islands',ST_GeomFromText('MULTIPOLYGON(((7 0,8 0,8 1,7 1,7 0)),((9 0,10 0,10 1,9 1,9 0)))',4326));
DO $$
BEGIN
 IF (SELECT count(*) FROM polygon_test_boundary WHERE ST_Covers(geom,ST_SetSRID(ST_Point(.5,.5),4326)))<>1 THEN RAISE EXCEPTION 'interior'; END IF;
 IF (SELECT count(*) FROM polygon_test_boundary WHERE ST_Covers(geom,ST_SetSRID(ST_Point(1,.5),4326)))<>2 THEN RAISE EXCEPTION 'shared edge must remain ambiguous'; END IF;
 IF EXISTS(SELECT 1 FROM polygon_test_boundary WHERE ST_Covers(geom,ST_SetSRID(ST_Point(4.5,1.5),4326))) THEN RAISE EXCEPTION 'hole'; END IF;
 IF (SELECT count(*) FROM polygon_test_boundary WHERE ST_Covers(geom,ST_SetSRID(ST_Point(9.5,.5),4326)))<>1 THEN RAISE EXCEPTION 'second island'; END IF;
 IF EXISTS(SELECT 1 FROM polygon_test_boundary WHERE ST_Covers(geom,ST_SetSRID(ST_Point(20,20),4326))) THEN RAISE EXCEPTION 'outside'; END IF;
 IF ST_IsValid(ST_GeomFromText('POLYGON((0 0,1 1,1 0,0 1,0 0))',4326)) THEN RAISE EXCEPTION 'invalid polygon detection'; END IF;
 IF ST_Distance(ST_Transform(ST_Transform(ST_SetSRID(ST_Point(127,37.3),4326),5186),4326)::geography,ST_SetSRID(ST_Point(127,37.3),4326)::geography)>.01 THEN RAISE EXCEPTION 'CRS round trip'; END IF;
END $$;
SELECT 'PASS: interior, shared edge ambiguity, hole, multipolygon, outside, invalid geometry, CRS round trip';
ROLLBACK;
