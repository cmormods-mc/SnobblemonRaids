#!/usr/bin/env python3
"""Phase 40 invariants: real texture art wired into the raid-reward reveal screen.

The masked/edited assets (summary_panel.png, claim_button_blank.png) exist specifically to avoid
colliding baked placeholder text with live data -- shipping the original unmasked "_exact" files by
mistake would silently reintroduce that bug, so this is asserted here rather than left to review.
"""
import json
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/cobbleraids"
CLIENT = JAVA / "client"
REVEAL = CLIENT / "reveal"
GUI_TEXTURES = ROOT / "src/main/resources/assets/cobbleraids/textures/gui/raid_rewards"

EXPECTED_TEXTURES = (
    "outer_frame_top", "outer_frame_bottom", "outer_frame_left", "outer_frame_right",
    "summary_panel", "chamber_background", "claim_button", "claim_button_blank", "icons",
)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def validate_textures_present() -> None:
    for name in EXPECTED_TEXTURES:
        png = GUI_TEXTURES / f"{name}.png"
        mcmeta = GUI_TEXTURES / f"{name}.png.mcmeta"
        assert png.is_file(), f"missing {png}"
        assert mcmeta.is_file(), f"missing {mcmeta}"
        meta = json.loads(read(mcmeta))
        assert meta["texture"]["blur"] is False, f"{mcmeta} must disable blur for pixel art"


def validate_no_unmasked_originals_shipped() -> None:
    # The delivered pack's raw "_exact" crops (summary_panel_exact.png in particular) have real
    # placeholder data baked in (a specific boss name, tier, timer, percentages) -- only the masked
    # copies without that suffix should ever be bundled.
    leftover = list(GUI_TEXTURES.glob("*_exact.png")) + list(GUI_TEXTURES.glob("*_exact.png.mcmeta"))
    assert not leftover, f"unmasked reference crops must not be shipped: {leftover}"


def validate_screen_uses_real_textures() -> None:
    screen = read(REVEAL / "RaidRewardRevealScreen.java")
    for name in EXPECTED_TEXTURES:
        if name == "icons":
            continue  # icon atlas is bundled for future use, not wired into the screen yet
        assert f'texture("{name}")' in screen, f"RaidRewardRevealScreen must reference {name}"


def validate_physical_side_boundary() -> None:
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
        for name in EXPECTED_TEXTURES:
            assert f"assets/cobbleraids/textures/gui/raid_rewards/{name}.png" in names, f"missing {name}.png in jar"
        manifest = json.loads(archive.read("fabric.mod.json"))
        assert manifest["entrypoints"]["client"] == ["com.cobbleraids.client.CobbleRaidsClient"]


def main() -> None:
    validate_textures_present()
    validate_no_unmasked_originals_shipped()
    validate_screen_uses_real_textures()
    validate_physical_side_boundary()
    validate_server_side_untouched()
    validate_no_new_gradle_dependency()
    for argument in sys.argv[1:]:
        validate_jar(Path(argument))
    print("Phase 40 real texture art validation: PASS")


if __name__ == "__main__":
    main()
