-- A6: exposure segments for a policy must be contiguous and non-overlapping.
--
-- Endorsements truncate the prior open segment at the new effective date and open a
-- fresh segment. If two segments ever overlap in time, claim coverage selection becomes
-- ambiguous (a claim in the overlap window can resolve to the stale earlier segment).
-- The application already rejects out-of-order endorsement effective dates, but this
-- constraint is the last line of defence at the storage layer.
--
-- Segments are modelled as half-open intervals [segment_start, segment_end): the shared
-- boundary between a truncated prior segment (ends at eff) and its successor (starts at
-- eff) does NOT count as an overlap, so contiguous segments are allowed.

CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE exposure_segment
    ADD CONSTRAINT exposure_segment_no_overlap
    EXCLUDE USING gist (
        policy_id WITH =,
        tstzrange(segment_start, segment_end, '[)') WITH &&
    );
