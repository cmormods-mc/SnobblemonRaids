# External Encounter API — Implementation Contract

This branch introduces a small public API for addons such as CobbleTowers. The API is intentionally not a wrapper that exposes `RaidSession` or other implementation classes.

## Required semantics before `startEncounter` becomes public

An addon-managed encounter must use the existing CobbleRaids battle engine while changing only ownership of terminal side effects.

### Always retained from CobbleRaids

- participant validation and cooperative battle construction;
- Showdown raid integration and actor mapping;
- shared boss health/contribution tracking;
- player withdrawal/disconnect safety;
- battle-state carryover to each participant's real party;
- terminal deduplication and cleanup ordering;
- server-thread/fault containment.

### Suppressed for addon-managed encounters

- `RaidProgressionTransfer.grant`;
- raid history/catch offer processing;
- `RaidRewardService.grant`;
- failed-attempt accounting and post-defeat boss retention.

The addon is responsible for its own progression/reward policy after receiving the terminal result.

### Boss lifetime

A boss created for an addon-managed encounter must be discarded on victory, defeat, timeout, abort, or loss of all participants. It must never enter CobbleRaids' ordinary "boss remains for another attempt" flow. This is required for private Tower instances and prevents orphan bosses from occupying a generated floor.

## Internal implementation shape

Add one explicit completion-policy value to raid runtime state. Existing natural/admin CobbleRaids paths use the current/default policy. The external API uses an addon-managed policy.

The lifecycle coordinator remains the single finalization authority. It branches only where terminal side effects differ; battle termination and cleanup order remain centralized.

A terminal public result is emitted only for addon-managed encounters and only from the existing finalization path. The result is an immutable snapshot of stable ids and contribution data. Listener exceptions are isolated so they cannot interrupt raid cleanup or another listener.

## Public API surface

Initial stable types:

- `RaidBossDescriptor`
- `RaidEncounterHandle`
- `RaidEncounterOutcome`
- `RaidEncounterResult`

Planned callable surface after lifecycle-policy implementation:

- resolve/list boss descriptors;
- spawn a canonical CobbleRaids boss for an addon-owned location;
- start an addon-managed encounter for a frozen 1–4 player snapshot;
- withdraw a participant by opaque encounter handle;
- subscribe/unsubscribe to terminal addon encounter results.

A later customization contract may add supported pre-battle Tower modifiers. It must operate before battle start and must not expose CobbleRaids internal configuration/session objects.

## Validation gate

Do not merge this API into `main` until all of the following pass:

1. existing CobbleRaids unit tests;
2. new policy tests proving normal raids retain existing reward/catch/retry behavior;
3. new policy tests proving addon encounters suppress those side effects and discard bosses on every terminal path;
4. dedicated-server smoke test with one normal raid and one addon-managed raid;
5. 1-, 2-, 3-, and 4-player addon encounter startup/termination tests;
6. disconnect/withdraw/timeout/abort tests;
7. listener-throws test proving cleanup still completes;
8. no active-encounter map or listener dispatch is scanned from the server tick.
