-- Allow the same IP address to exist in distinct customer/context subnets,
-- while preserving uniqueness inside a single subnet.

ALTER TABLE addresses
    DROP CONSTRAINT IF EXISTS addresses_address_key;

ALTER TABLE addresses
    ADD CONSTRAINT uq_addresses_address_subnet UNIQUE (address, subnet_id);

