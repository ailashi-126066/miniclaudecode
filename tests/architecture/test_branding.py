from tools.check_architecture import check_branding


def test_no_product_brand_residue():
    assert check_branding() == []


def test_residue_in_generated_configuration_is_detected(tmp_path):
    output = tmp_path / "config.json"
    output.write_text('{"name":"' + "MEW" + 'CODE"}', encoding="utf-8")
    assert len(check_branding(tmp_path)) == 1


def test_source_attribution_and_license_are_allowed(tmp_path):
    source_map = tmp_path / "docs" / "source-map.md"
    source_map.parent.mkdir()
    source_map.write_text("mew" + "code source mapping", encoding="utf-8")
    source = tmp_path / "source.py"
    source.write_text("# Source: " + "mew" + "code local SHA-256 baseline\n", encoding="utf-8")
    assert check_branding(tmp_path) == []
