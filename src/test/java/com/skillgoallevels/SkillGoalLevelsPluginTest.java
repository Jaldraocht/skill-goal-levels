package com.skillgoallevels;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SkillGoalLevelsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SkillGoalLevelsPlugin.class);
		RuneLite.main(args);
	}
}
