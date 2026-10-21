CREATE TABLE monthly_report_asset (
 id varchar(250) PRIMARY KEY, asset_version varchar(60) NOT NULL,
 kind varchar(10) NOT NULL CHECK (kind IN ('COVER','TYPE')),
 character_code varchar(30) NOT NULL, planet_code varchar(30) NOT NULL,
 variant varchar(10) NOT NULL, image_key varchar(500) NOT NULL UNIQUE
);
