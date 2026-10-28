# Surface speed while metadata operations are pending

The typed NPC speed reader used mutation readiness as read readiness. An already
accepted DIRT_PATH therefore returned its configured 1.3 multiplier, then 1.0
while a queued footstep or an unrelated foreign block clear was pending, then
1.3 again. Dedicated integration tests reproduce both transitions. Ordinary
in-memory counter writes and asynchronous PDC flushes alone do not cause this
transition.

The reader now retains at most 4096 accepted positive readings, keyed by world
UUID and exact block coordinates. A pending read may reuse one only when the
block data, biome, metadata identity, settings instance and store instance still
match, and no read barrier failed. It performs no new asynchronous operation or
terrain scan. Eviction falls back to ordinary speed during pending operations.

A foreign clear of at most 128 blocks invalidates only their exact keys before
the operation yields, including identical-material replacements. This covers
ordinary 16-cell construction batches and keeps unrelated accepted roads warm.
The bound allows at most 128 synchronous position captures with no block-data,
shape or chunk scan. Larger events invalidate the whole read cache conservatively
and retain the existing resumable capture; they do not synchronously traverse
their full block set. Native replacement, accepted metadata removal, cold
or unregistered roads, different worlds, disabled worlds, reload and failed read
barriers retain ordinary speed. Mutation, protection and durable journal gates
remain unchanged. Public API and Core dependencies do not change.

Configured road multipliers, work-action intervals, output quantities, prices
and rewards remain unchanged. Direct delta is 0 Vault, 0 tokens and 0 new items.
For movement-only sensitivity, pending fractions 0%, 10% and 50% previously give
effective multipliers 1.3, 1.27 and 1.15; preserving 1.3 changes travel throughput
by 0%, 2.36% and 13.04%. These are assumptions, not measured total production or
income. Work, food, input supply and capacity still limit output. Existing SELL
and the planned SELL-to-contract transition are unchanged.

Source tests establish the fallback mechanism and safety fences. Native packet
QA measured short 0.2236-to-0.172 movement dips, but did not record Trails'
returned multiplier at those exact moments. The live event-to-dip attribution
and post-release client acceptance must therefore be checked separately.
