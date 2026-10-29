import cv2
import yaml

from another_macro.cli import main
from another_macro.vision import Library, Vision

from helpers import ELEMENTS, REF, render


def test_capture_template_then_check(tmp_path, capsys):
    shot = tmp_path / "shot.png"
    cv2.imwrite(str(shot), render(["field_menu_button"]))
    lib = tmp_path / "lib" / "library.yaml"
    lib.parent.mkdir()
    lib.write_text("# header comment\ntemplates:\n  field_menu_button:\n    notes: menu\nscreens:\n  field:\n    require: [field_menu_button]\n")

    x, y, w, h = ELEMENTS["field_menu_button"]
    box = f"{int(x * REF[0])},{int(y * REF[1])},{int(w * REF[0])},{int(h * REF[1])}"
    assert main(["capture-template", "field_menu_button", "--from", str(shot), "--box", box, "--library", str(lib)]) == 0

    text = lib.read_text()
    assert text.startswith("# header comment")
    spec = yaml.safe_load(text)["templates"]["field_menu_button"]
    assert spec["notes"] == "menu" and spec["ref"] == list(REF) and spec["file"] == "field_menu_button.png"

    vision = Vision(Library.load(lib))
    assert vision.identify(render(["field_menu_button"], size=(1600, 720))) == "field"


def test_list_shows_grind_battles(capsys):
    assert main(["list"]) == 0
    assert "grind-battles" in capsys.readouterr().out
