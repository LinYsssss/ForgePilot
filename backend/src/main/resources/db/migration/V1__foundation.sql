-- Phase 1 foundation: enable the PostgreSQL vector type only.
-- Business tables, dimensions, indexes, triggers, and seed data belong to later phases.
CREATE EXTENSION IF NOT EXISTS vector;
