ALTER TABLE account
    ALTER COLUMN currency TYPE VARCHAR(3);

ALTER TABLE account_hold
    ALTER COLUMN currency TYPE VARCHAR(3);
