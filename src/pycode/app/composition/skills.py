"""Translate application settings into the selected Skills module input."""

from pycode.skills.module import SkillsSettings


def skill_settings(config):
    return SkillsSettings(
        config.workspace, config.user_data, config.project_trusted, config.skill_index_budget
    )
