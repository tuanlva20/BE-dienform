-- Rename column text to option_text in question_option table
ALTER TABLE question_option CHANGE COLUMN text option_text varchar(255) NOT NULL;

-- Add missing columns to question_option table if not exists
ALTER TABLE question_option 
    ADD COLUMN IF NOT EXISTS is_row boolean DEFAULT false,
    ADD COLUMN IF NOT EXISTS parent_option_id varchar(36),
    ADD CONSTRAINT IF NOT EXISTS fk_parent_option 
        FOREIGN KEY (parent_option_id) REFERENCES question_option(id);

-- Add JSON column to question table if not exists
ALTER TABLE question 
    ADD COLUMN IF NOT EXISTS additional_data JSON; 