package com.skillgoallevels;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(SkillGoalLevelsConfig.GROUP)
public interface SkillGoalLevelsConfig extends Config
{
	String GROUP = "skillgoallevels";

	@ConfigItem(
		keyName = "goalColor",
		name = "Goal level color",
		description = "Color of the goal level shown in place of your real level on the skills tab."
			+ " Defaults to the green used by the \"green:\" chat effect.",
		position = 1
	)
	default Color goalColor()
	{
		// Same color as the "green:" public chat effect
		return new Color(0x00FF00);
	}

	@ConfigItem(
		keyName = "hideCompletedGoals",
		name = "Hide completed goals",
		description = "Show your real level again once you've reached the goal",
		position = 2
	)
	default boolean hideCompletedGoals()
	{
		return true;
	}

	@ConfigItem(
		keyName = "revealOnHover",
		name = "Show real level on hover",
		description = "Swap back to your real level while the mouse is over a skill",
		position = 3
	)
	default boolean revealOnHover()
	{
		return true;
	}
}
