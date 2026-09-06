#!/usr/bin/env python3
"""Phase 38 invariants: the cinematic Poke Ball reveal (client-only presentation over Phase 37's pipe).

Both properties -- the physical-side boundary and "the server still rolls everything, this is just
presentation" -- are easy to silently break with a small edit, so they're asserted here rather than
left to review.
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


def validate_new_files_exist() -> None:
    for name in ("PokeBallMesh.java", "RevealSounds.java", "RevealParticles.java", "RaidRewardRevealScreen.java"):
        assert (REVEAL / name).is_file(), f"missing {name}"


def validate_physical_side_boundary() -> None:
    # ClientPlayNetworking must still never leak outside com/cobbleraids/client/ (Phase 37 invariant,
    # re-asserted so Phase 38's new files can't quietly regress it).
    for path in JAVA.rglob("*.java"):
        if CLIENT in path.parents or path.parent == CLIENT:
            continue
        assert "ClientPlayNetworking" not in read(path), f"{path} references ClientPlayNetworking outside client/"

    # The sound/rendering APIs Phase 38 introduces are client-only by construction (Minecraft.getInstance(),
    # SimpleSoundInstance, GuiGraphics) -- confirm they stay inside client/ too.
    for path in JAVA.rglob("*.java"):
        if CLIENT in path.parents or path.parent == CLIENT:
            continue
        text = read(path)
        assert "SimpleSoundInstance" not in text, f"{path} references SimpleSoundInstance outside client/"
        assert "Minecraft.getInstance()" not in text, f"{path} references Minecraft.getInstance() outside client/"


def validate_server_side_untouched() -> None:
    # Re-assert Phase 37's known-good server-side invariants are still present, unchanged by this
    # presentation-only phase.
    service = read(JAVA / "lifecycle/RaidRewardService.java")
    engine = read(JAVA / "reward/RaidRewardGrantEngine.java")
    gateway = read(JAVA / "reward/NativeRewardScreenGateway.java")
    command = read(JAVA / "reward/RaidRewardCommand.java")

    assert "public static synchronized boolean claim(ServerPlayer player, String choiceId)" in service
    assert "public static RewardGrantResult grantChoice(" in engine
    assert "RaidRewardGrantEngine" not in gateway
    assert "RaidRewardService.claim(player, StringArgumentType.getString(ctx, \"choice\"))" in command


def validate_state_machine() -> None:
    screen = read(REVEAL / "RaidRewardRevealScreen.java")
    assert "enum State { CHOOSING, WAITING, OPENING, RESULT }" in screen
    apply_result = screen.split("public static void applyResult(", 1)[1].split("\n    }", 1)[0]
    assert "state = State.OPENING" in apply_result
    assert "RevealSounds.playOpen(" in apply_result
    assert "RevealParticles.spawnBurst(" in apply_result


def validate_tier_reuse() -> None:
    screen = read(REVEAL / "RaidRewardRevealScreen.java")
    mesh = read(REVEAL / "PokeBallMesh.java")
    # RaidTierPresentation stays the single source of truth for tier color -- no duplicate mapping
    # invented in the new reveal-screen files.
    assert "RaidTierPresentation.color(" in screen
    assert "RaidTierPresentation" not in mesh, "PokeBallMesh should stay tier-agnostic; theming lives in the screen"


def validate_mesh_generated_once() -> None:
    mesh = read(REVEAL / "PokeBallMesh.java")
    assert "private static final GeneratedMesh MESH = generate();" in mesh, \
        "mesh geometry must be generated once, not regenerated per frame"


def validate_no_new_gradle_dependency() -> None:
    build_gradle = read(ROOT / "build.gradle")
    mod_implementations = [line for line in build_gradle.splitlines() if line.strip().startswith("modImplementation")]
    assert len(mod_implementations) == 4, mod_implementations


def validate_no_new_assets() -> None:
    assert not (RESOURCES / "assets/cobbleraids/textures").exists()
    assert not (RESOURCES / "assets/cobbleraids/models").exists()
    assert not (RESOURCES / "assets/cobbleraids/sounds").exists()


def validate_jar(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        assert "com/cobbleraids/client/reveal/PokeBallMesh.class" in names
        assert "com/cobbleraids/client/reveal/RevealSounds.class" in names
        assert "com/cobbleraids/client/reveal/RevealParticles.class" in names
        manifest = json.loads(archive.read("fabric.mod.json"))
        assert manifest["entrypoints"]["client"] == ["com.cobbleraids.client.CobbleRaidsClient"]


def main() -> None:
    validate_new_files_exist()
    validate_physical_side_boundary()
    validate_server_side_untouched()
    validate_state_machine()
    validate_tier_reuse()
    validate_mesh_generated_once()
    validate_no_new_gradle_dependency()
    validate_no_new_assets()
    for argument in sys.argv[1:]:
        validate_jar(Path(argument))
    print("Phase 38 cinematic reveal validation: PASS")


if __name__ == "__main__":
    main()
