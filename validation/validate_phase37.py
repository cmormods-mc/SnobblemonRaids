#!/usr/bin/env python3
"""Phase 37 invariants: native raid-reward reveal screen (MVP slice).

The physical-side boundary (client code must never run on a dedicated server, server code must
never require a client) and the "server still rolls everything" trust model are easy to silently
break with a small edit, so the properties that make them work are asserted here rather than left
to review.
"""
import json
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/cobbleraids"
RESOURCES = ROOT / "src/main/resources"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def validate_physical_side_boundary() -> None:
    client_dir = JAVA / "client"
    assert client_dir.is_dir(), "no client package found"
    client_files = list(client_dir.rglob("*.java"))
    assert client_files, "client package is empty"

    # ClientPlayNetworking must never be referenced outside com/cobbleraids/client/.
    for path in JAVA.rglob("*.java"):
        if client_dir in path.parents or path.parent == client_dir:
            continue
        text = read(path)
        assert "ClientPlayNetworking" not in text, f"{path} references ClientPlayNetworking outside client/"

    initializer = read(JAVA / "CobbleRaids.java")
    manifest = json.loads(read(RESOURCES / "fabric.mod.json"))
    assert manifest["entrypoints"]["client"] == ["com.cobbleraids.client.CobbleRaidsClient"]

    # Payload-type registration must happen in common code (CobbleRaids.java), not only client-side --
    # both physical sides need to agree on the same codec before either side can send.
    assert "RaidRewardPayloads.registerPayloadTypes()" in initializer
    assert "ServerPlayNetworking.registerGlobalReceiver(RewardChoicePayload.TYPE" in initializer


def validate_server_authority_preserved() -> None:
    engine = read(JAVA / "reward/RaidRewardGrantEngine.java")
    gateway = read(JAVA / "reward/NativeRewardScreenGateway.java")
    service = read(JAVA / "lifecycle/RaidRewardService.java")
    command = read(JAVA / "reward/RaidRewardCommand.java")

    # grantChoice is still the sole grant entry point, and nothing outside RaidRewardService calls it.
    assert "public static RewardGrantResult grantChoice(" in engine
    assert "RaidRewardGrantEngine" not in gateway, "the network gateway must go through RaidRewardService, not the grant engine directly"

    # claim()'s signature/callers are unchanged -- RaidRewardCommand still drives it identically.
    assert "public static synchronized boolean claim(ServerPlayer player, String choiceId)" in service
    assert "RaidRewardService.claim(player, StringArgumentType.getString(ctx, \"choice\"))" in command

    # The native path's C2S payload is a choiceId only; the server always resolves its own queue.
    assert "static void handleChoice(ServerPlayer player, RewardChoicePayload payload)" in gateway
    assert "RaidRewardService.claimNative(player, payload.choiceId())" in gateway

    # The claim-failure safety net (restore the pending claim) must survive the claim/claimInternal split.
    assert "addFirst(pending);" in service


def validate_reveal_pipe_wiring() -> None:
    service = read(JAVA / "lifecycle/RaidRewardService.java")
    pending = read(JAVA / "reward/PendingRaidReward.java")
    gateway = read(JAVA / "reward/NativeRewardScreenGateway.java")

    # Tier is snapshotted onto PendingRaidReward at grant time, matching its existing reload-safety
    # rationale for every other field.
    assert "RaidRarityTier rarityTier" in pending
    assert "definition.rarityTier()" in service

    # openCurrent tries the native screen before the SkiesGUIs/chat-backend chain, and the chat
    # fallback text is still reachable if neither native nor SkiesGUIs can open.
    open_current = service.split("public static boolean openCurrent(", 1)[1].split("\n    }", 1)[0]
    native_index = open_current.find("NativeRewardScreenGateway.tryOpen(")
    backend_index = open_current.find("RewardGuiBackends.active().open(")
    assert native_index != -1 and backend_index != -1 and native_index < backend_index, \
        "native screen must be attempted before the SkiesGUIs/chat-backend fallback"
    assert "sendChatFallbackChoices(player, pending)" in open_current

    # Per-player capability gating, not a server-wide static switch.
    assert "ServerPlayNetworking.canSend(player, PendingRewardRevealPayload.TYPE)" in gateway


def validate_no_new_gradle_dependency() -> None:
    build_gradle = read(ROOT / "build.gradle")
    mod_implementations = [line for line in build_gradle.splitlines() if line.strip().startswith("modImplementation")]
    assert len(mod_implementations) == 4, mod_implementations


def validate_jar(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        manifest = json.loads(archive.read("fabric.mod.json"))
        assert manifest["entrypoints"]["client"] == ["com.cobbleraids.client.CobbleRaidsClient"]
        assert "com/cobbleraids/client/CobbleRaidsClient.class" in names
        assert "com/cobbleraids/network/RaidRewardPayloads.class" in names
        assert "com/cobbleraids/reward/NativeRewardScreenGateway.class" in names


def main() -> None:
    validate_physical_side_boundary()
    validate_server_authority_preserved()
    validate_reveal_pipe_wiring()
    validate_no_new_gradle_dependency()
    for argument in sys.argv[1:]:
        validate_jar(Path(argument))
    print("Phase 37 native reveal screen validation: PASS")


if __name__ == "__main__":
    main()
