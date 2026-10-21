ALTER TABLE course_image_file RENAME TO course_image_files;

ALTER TABLE course_like RENAME TO course_likes;
ALTER INDEX idx_course_like_course_id RENAME TO idx_course_likes_course_id;
