#!/usr/bin/env python3
"""Phase 39 invariants: the textured raid-reward panel (structure + placeholders, no real art yet).

The 3D mesh's retirement, the new reusable nine-slice utility, and the two new stat fields threaded
through the wire payload are all easy to silently regress, so they're asserted here rather than left
to review.
"""
import json
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/cobbleraids"
CLIENT = JAVA / "client"
REVEAL = CLIENT / "reveal"
RESOURCES = ROOT / "src/main/resources"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def validate_mesh_retired() -> None:
    assert not (REVEAL / "PokeBallMesh.java").exists(), "PokeBallMesh should have been deleted, not left dormant"
    screen = read(REVEAL / "RaidRewardRevealScreen.java")
    assert "PokeBallMesh" not in screen


def validate_sound_and_particles_survived() -> None:
    assert (REVEAL / "RevealSounds.java").is_file()
    assert (REVEAL / "RevealParticles.java").is_file()
    screen = read(REVEAL / "RaidRewardRevealScreen.java")
    assert "RevealSounds.playOpen(" in screen
    assert "RevealParticles.spawnBurst(" in screen
    assert "RevealParticles.renderAndCull(" in screen


def validate_nine_slice_renderer_exists() -> None:
    renderer = CLIENT / "gui" / "NineSliceRenderer.java"
    assert renderer.is_file()
    text = read(renderer)
    assert "GuiGraphics" in text and "ResourceLocation" in text, "must be ported to Mojang mappings, not Yarn"
    assert "DrawContext" not in text and "Identifier" not in text and "drawTexture" not in text, \
        "leftover Yarn-mapped names from the reference snippet"


def validate_new_stats_threaded_through() -> None:
    pending = read(JAVA / "reward/PendingRaidReward.java")
    service = read(JAVA / "lifecycle/RaidRewardService.java")
    eligibility = read(JAVA / "lifecycle/RaidRewardEligibility.java")
    payload = read(JAVA / "network/PendingRewardRevealPayload.java")
    gateway = read(JAVA / "reward/NativeRewardScreenGateway.java")

    assert "elapsedCombatTicks" in pending and "participantCount" in pending
    assert "eligibility.elapsedCombatTicks()" in service and "eligibility.participants().size()" in service
    assert "elapsedCombatTicks" in eligibility
    assert "elapsedCombatTicks" in payload and "participantCount" in payload
    assert "pending.elapsedCombatTicks()" in gateway and "pending.participantCount()" in gateway


def validate_physical_side_boundary() -> None:
    # Re-assert Phase 37/38's boundary: client-only APIs never leak outside com/cobbleraids/client/.
    for path in JAVA.rglob("*.java"):
        if CLIENT in path.parents or path.parent == CLIENT:
            continue
        text = read(path)
        assert "ClientPlayNetworking" not in text, f"{path} references ClientPlayNetworking outside client/"
        assert "SimpleSoundInstance" not in text, f"{path} references SimpleSoundInstance outside client/"
        assert "Minecraft.getInstance()" not in text, f"{path} references Minecraft.getInstance() outside client/"


def validate_server_side_untouched() -> None:
    service = read(JAVA / "lifecycle/RaidRewardService.java")
    engine = read(JAVA / "reward/RaidRewardGrantEngine.java")
    gateway = read(JAVA / "reward/NativeRewardScreenGateway.java")
    command = read(JAVA / "reward/RaidRewardCommand.java")

    assert "public static synchronized boolean claim(ServerPlayer player, String choiceId)" in service
    assert "public static RewardGrantResult grantChoice(" in engine
    assert "RaidRewardGrantEngine" not in gateway
    assert "RaidRewardService.claim(player, StringArgumentType.getString(ctx, \"choice\"))" in command


def validate_no_new_gradle_dependency() -> None:
    build_gradle = read(ROOT / "build.gradle")
    mod_implementations = [line for line in build_gradle.splitlines() if line.strip().startswith("modImplementation")]
    assert len(mod_implementations) == 4, mod_implementations


def validate_jar(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        assert "com/cobbleraids/client/gui/NineSliceRenderer.class" in names
        assert "com/cobbleraids/client/reveal/PokeBallMesh.class" not in names
        manifest = json.loads(archive.read("fabric.mod.json"))
        assert manifest["entrypoints"]["client"] == ["com.cobbleraids.client.CobbleRaidsClient"]


def main() -> None:
    validate_mesh_retired()
    validate_sound_and_particles_survived()
    validate_nine_slice_renderer_exists()
    validate_new_stats_threaded_through()
    validate_physical_side_boundary()
    validate_server_side_untouched()
    validate_no_new_gradle_dependency()
    for argument in sys.argv[1:]:
        validate_jar(Path(argument))
    print("Phase 39 textured reward panel validation: PASS")


if __name__ == "__main__":
    main()
