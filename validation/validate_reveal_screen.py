#!/usr/bin/env python3
"""Invariants: the reward reveal path, and that the mod stays server-only.

The physical-side boundary (client code must never run on a dedicated server, server code must
never require a client) and the "server still rolls everything" trust model are easy to silently
break with a small edit, so the properties that make them work are asserted here rather than left
to review.
"""
import json
import sys
import zipfile
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/cobbleraids"
RESOURCES = ROOT / "src/main/resources"


BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")


def strip_comments(text: str) -> str:
    """Java source with comments removed, so assertions match code rather than prose."""
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def validate_physical_side_boundary() -> None:
    # The mod is server-only: no client code, no client entrypoint, and no required client companion. The radar display channel is capability-gated.
    assert not (JAVA / "client").exists(), "a client package has come back"
    assert not (JAVA / "mixin/client").exists(), "client mixins have come back"
    manifest = json.loads(read(RESOURCES / "fabric.mod.json"))
    assert "client" not in manifest["entrypoints"], "a client entrypoint has come back"
    mixins = json.loads(read(RESOURCES / "mixins/cobbleraids.mixins.json"))
    assert not mixins.get("client"), "client mixins are listed again"
    for path in JAVA.rglob("*.java"):
        text = strip_comments(read(path))
        assert "ClientPlayNetworking" not in text, f"{path} references ClientPlayNetworking"
        if "PayloadTypeRegistry" in text:
            assert path == JAVA / "radar/RadarHologramNetworking.java", f"{path} registers an unexpected packet"
            assert "ServerPlayNetworking.canSend(player, RadarHologramPayload.TYPE)" in text
            assert "if (canShow(player)) ServerPlayNetworking.send(player, payload);" in text
        assert "net.minecraft.client." not in text, f"{path} references client rendering code"


def validate_server_authority_preserved() -> None:
    engine = read(JAVA / "reward/RaidRewardGrantEngine.java")
    gateway = read(JAVA / "reward/NativeRewardScreenGateway.java")
    service = read(JAVA / "lifecycle/RaidRewardService.java")
    command = read(JAVA / "reward/RaidRewardCommand.java")

    # grantChoice is still the sole grant entry point, and nothing outside RaidRewardService calls it.
    assert "public static RewardGrantResult grantChoice(" in engine
    assert "RaidRewardGrantEngine" not in gateway, "the network gateway must go through RaidRewardService, not the grant engine directly"

    # claim()'s signature/callers are unchanged -- RaidRewardCommand still drives it identically.
    # Claiming must stay serialized per player, or a GUI click and a command arriving in the same
    # tick can both spend one claim token. It used to be a single static monitor for the whole
    # server, which serialized every player's claim behind every other on the server thread; the
    # scope is now per player. Assert the property -- one claim at a time per player -- rather than
    # the keyword that used to implement it.
    # Comments stripped first: this file's own javadoc explains the global monitor it replaced,
    # and matching prose instead of code is the exact mistake these scripts keep making.
    service_code = strip_comments(service)
    assert "synchronized (lockFor(player.getUUID()))" in service_code, "claims are no longer serialized per player"
    assert "static synchronized" not in service_code, "a global claim monitor has come back"
    assert "RaidRewardService.claim(player, StringArgumentType.getString(ctx, \"choice\"))" in command

    # The web page's request is a choiceId only; the server always resolves its own queue.
    assert "static RewardResultPayload resolveChoice(ServerPlayer player, RewardChoicePayload payload)" in gateway
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

    # openCurrent offers the web reveal page and, when the client cannot show it, falls back to
    # the chat choices. There is no third path any more.
    open_current = service.split("public static boolean openCurrent(", 1)[1].split("\n    }", 1)[0]
    assert "NativeRewardScreenGateway.tryOpen(" in open_current
    assert "sendChatFallbackChoices(player, pending)" in open_current

    # Per-player capability gating, not a server-wide static switch.
    assert "WebUi.canShow(player)" in gateway


def validate_no_new_gradle_dependency() -> None:
    build_gradle = read(ROOT / "build.gradle")
    mod_implementations = [line for line in build_gradle.splitlines() if line.strip().startswith("modImplementation")]
    expected = ("fabric-loader", "fabric-api", "MdwFAVRL", "polymer-core", "polymer-resource-pack", "polymer-common")
    assert len(mod_implementations) == len(expected), mod_implementations
    for needle in expected:
        assert any(needle in line for line in mod_implementations), f"missing dependency {needle}"


def validate_jar(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        manifest = json.loads(archive.read("fabric.mod.json"))
        assert "client" not in manifest["entrypoints"]
        assert not any(name.startswith("com/cobbleraids/client/") for name in names), "client classes in the jar"
        assert "com/cobbleraids/reward/NativeRewardScreenGateway.class" in names


def main() -> None:
    validate_physical_side_boundary()
    validate_server_authority_preserved()
    validate_reveal_pipe_wiring()
    validate_no_new_gradle_dependency()
    for argument in sys.argv[1:]:
        validate_jar(Path(argument))
    print("Reveal path and server-only validation: PASS")


if __name__ == "__main__":
    main()
