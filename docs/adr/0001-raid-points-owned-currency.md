# Raid Points is a currency this mod owns, not borrowed from CobbleDollars

CobbleDollars is already the general-purpose economy on servers running this mod, so paying raid
rewards through it was the obvious path, and an integration (`CobbleDollarsCurrencyBackend`) exists
and still works. We introduced Raid Points instead as the default, and made the raid shop accept
only Raid Points: a currency raids grant and only the raid shop spends cannot be earned any other
way, which keeps what a raid is worth — and what it can buy — a decision this mod makes rather than
one that drifts with whatever else a server's economy pays out for. The cost is a second balance
players have to track, and a currency backend abstraction (`RaidCurrencyBackends`) that has to keep
the CobbleDollars path alive for servers that prefer it.
