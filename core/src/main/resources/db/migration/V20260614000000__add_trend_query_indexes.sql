CREATE INDEX idx_famous_menu_region3rd
    ON famous_menu_by_region (region3rd);

CREATE INDEX idx_hot_store_ranking_menu_rank
    ON hot_store_ranking (famous_menu_by_region_id, rank);

CREATE INDEX idx_store_image_store_status
    ON store_image_file (store_id, processing_status);
