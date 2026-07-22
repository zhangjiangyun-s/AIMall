# Legacy SQL Archive

The SQL files in this directory are historical inputs only. They are not an
executable migration location and must not be mounted into MySQL initialization
directories or added to Spring SQL initialization.

Production schema changes are owned exclusively by Flyway under
`classpath:db/migration`. The reconciliation migration
`V20260717_1100__legacy_business_schema_reconciliation.sql` records and safely
replays the effects of these former manual scripts for pre-Flyway databases.
