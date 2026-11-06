package com.skillgoallevels;

import com.google.inject.Provides;
import java.awt.Rectangle;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Skill Goal Levels",
	description = "Shows your in-game XP goal level as the denominator on the skills tab; hover a skill to see your real level",
	tags = {"skill", "goal", "target", "level", "stats", "xp"}
)
public class SkillGoalLevelsPlugin extends Plugin
{
	/**
	 * Fired by the skills tab script (395) every time it (re)writes a skill's real level text.
	 * Arguments on the int stack: [..., skillId, level].
	 */
	private static final String SKILL_TAB_BASE_LEVEL_CALLBACK = "skillTabBaseLevel";

	private static final Skill[] SKILLS = Skill.values();
	private static final Map<Integer, Skill> GOAL_VARP_TO_SKILL = new HashMap<>();

	static
	{
		for (Skill skill : SKILLS)
		{
			GOAL_VARP_TO_SKILL.put(goalEndVarp(skill), skill);
		}
	}

	/**
	 * What the skill tile's bottom-right text looked like before we changed it,
	 * so it can be put back on hover, when the goal goes away, or on shutdown.
	 */
	private static final class TileState
	{
		String originalText;
		int originalColor;
		int realLevel;
		String appliedText;
	}

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private SkillGoalLevelsConfig config;

	// All fields below are only touched on the client thread.
	private final Map<Skill, TileState> modified = new EnumMap<>(Skill.class);
	private final Set<Skill> dirty = EnumSet.noneOf(Skill.class);
	private Skill hoveredSkill;
	private int lastMouseX = Integer.MIN_VALUE;
	private int lastMouseY = Integer.MIN_VALUE;

	@Override
	protected void startUp()
	{
		clientThread.invoke(this::markAllDirty);
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() ->
		{
			restoreAll();
			dirty.clear();
			hoveredSkill = null;
			lastMouseX = lastMouseY = Integer.MIN_VALUE;
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (SkillGoalLevelsConfig.GROUP.equals(event.getGroup()))
		{
			clientThread.invoke(() ->
			{
				restoreAll();
				markAllDirty();
			});
		}
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		// The game just rewrote this skill's level text; fix it up before the next frame is drawn.
		if (SKILL_TAB_BASE_LEVEL_CALLBACK.equals(event.getEventName()))
		{
			int[] intStack = client.getIntStack();
			int skillId = intStack[client.getIntStackSize() - 2];
			if (skillId >= 0 && skillId < SKILLS.length)
			{
				dirty.add(SKILLS[skillId]);
			}
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// A goal was set, changed or cleared.
		Skill skill = GOAL_VARP_TO_SKILL.get(event.getVarpId());
		if (skill != null)
		{
			dirty.add(skill);
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// XP gain can complete a goal, which matters for "Hide completed goals".
		dirty.add(event.getSkill());
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		Widget statsRoot = client.getWidget(InterfaceID.Stats.UNIVERSE);
		if (statsRoot == null || statsRoot.isHidden())
		{
			return;
		}

		if (config.revealOnHover())
		{
			updateHoveredSkill(statsRoot);
		}

		if (dirty.isEmpty())
		{
			return;
		}

		int goalColor = config.goalColor().getRGB() & 0xFFFFFF;
		for (Skill skill : dirty)
		{
			refresh(skill, goalColor);
		}
		dirty.clear();
	}

	/**
	 * Only does work when the mouse has moved, and only marks skills dirty
	 * when the hovered skill actually changes.
	 */
	private void updateHoveredSkill(Widget statsRoot)
	{
		Point mouse = client.getMouseCanvasPosition();
		if (mouse == null || (mouse.getX() == lastMouseX && mouse.getY() == lastMouseY))
		{
			return;
		}
		lastMouseX = mouse.getX();
		lastMouseY = mouse.getY();

		Skill newHovered = null;
		Rectangle rootBounds = statsRoot.getBounds();
		if (rootBounds != null && rootBounds.contains(lastMouseX, lastMouseY))
		{
			for (Skill skill : SKILLS)
			{
				Widget tile = client.getWidget(tileComponent(skill));
				if (tile != null && !tile.isHidden())
				{
					Rectangle bounds = tile.getBounds();
					if (bounds != null && bounds.contains(lastMouseX, lastMouseY))
					{
						newHovered = skill;
						break;
					}
				}
			}
		}

		if (newHovered != hoveredSkill)
		{
			if (hoveredSkill != null)
			{
				dirty.add(hoveredSkill);
			}
			if (newHovered != null)
			{
				dirty.add(newHovered);
			}
			hoveredSkill = newHovered;
		}
	}

	private void refresh(Skill skill, int goalColor)
	{
		Widget tile = client.getWidget(tileComponent(skill));
		Widget levelText = tile == null ? null : findBaseLevelText(tile);
		if (levelText == null)
		{
			return;
		}

		int goalLevel = getGoalLevel(skill);
		boolean hovered = config.revealOnHover() && skill == hoveredSkill;

		if (goalLevel > 0 && !hovered)
		{
			applyGoal(skill, levelText, goalLevel, goalColor);
		}
		else
		{
			restore(skill, levelText);
		}
	}

	private void applyGoal(Skill skill, Widget levelText, int goalLevel, int goalColor)
	{
		int realLevel = client.getRealSkillLevel(skill);
		String currentText = levelText.getText();
		TileState state = modified.get(skill);

		if (state == null)
		{
			state = new TileState();
			state.originalText = currentText;
			state.originalColor = levelText.getTextColor();
			modified.put(skill, state);
		}
		else
		{
			// The game redraws the level text when stats change or the tab is rebuilt;
			// keep our copy of the real text up to date when that happens.
			if (!currentText.equals(state.appliedText))
			{
				state.originalText = currentText;
			}
			else if (realLevel != state.realLevel)
			{
				state.originalText = String.valueOf(realLevel);
			}

			if (levelText.getTextColor() != goalColor)
			{
				state.originalColor = levelText.getTextColor();
			}
		}

		state.realLevel = realLevel;
		state.appliedText = String.valueOf(goalLevel);

		if (!state.appliedText.equals(currentText))
		{
			levelText.setText(state.appliedText);
		}
		if (levelText.getTextColor() != goalColor)
		{
			levelText.setTextColor(goalColor);
		}
	}

	private void restore(Skill skill, Widget levelText)
	{
		TileState state = modified.remove(skill);
		if (state == null)
		{
			return;
		}

		if (levelText.getText().equals(state.appliedText))
		{
			int realLevel = client.getRealSkillLevel(skill);
			levelText.setText(realLevel == state.realLevel ? state.originalText : String.valueOf(realLevel));
		}
		levelText.setTextColor(state.originalColor);
	}

	private void restoreAll()
	{
		for (Skill skill : SKILLS)
		{
			if (!modified.containsKey(skill))
			{
				continue;
			}

			Widget tile = client.getWidget(tileComponent(skill));
			Widget levelText = tile == null ? null : findBaseLevelText(tile);
			if (levelText != null)
			{
				restore(skill, levelText);
			}
		}
		modified.clear();
	}

	private void markAllDirty()
	{
		for (Skill skill : SKILLS)
		{
			dirty.add(skill);
		}
		// Force the hover check to run again on the next frame.
		lastMouseX = lastMouseY = Integer.MIN_VALUE;
	}

	/**
	 * Goal level from the in-game XP tracker goal, or -1 if there isn't one to show.
	 * The game stores goals as an end XP amount, even when set as a level.
	 */
	private int getGoalLevel(Skill skill)
	{
		int goalXp = client.getVarpValue(goalEndVarp(skill));
		if (goalXp <= 0)
		{
			return -1;
		}

		if (config.hideCompletedGoals() && client.getSkillExperience(skill) >= goalXp)
		{
			return -1;
		}

		return Experience.getLevelForXp(goalXp);
	}

	/**
	 * Each skill tile has two level texts: the boosted level (top-left) and the
	 * real level (bottom-right, the "denominator"). Pick the lower of the two.
	 */
	private static Widget findBaseLevelText(Widget tile)
	{
		Widget[] children = tile.getDynamicChildren();
		if (children == null)
		{
			return null;
		}

		Widget lowest = null;
		for (Widget child : children)
		{
			if (child != null && child.getType() == WidgetType.TEXT
				&& (lowest == null || child.getRelativeY() > lowest.getRelativeY()))
			{
				lowest = child;
			}
		}
		return lowest;
	}

	private static int tileComponent(Skill skill)
	{
		switch (skill)
		{
			case ATTACK: return InterfaceID.Stats.ATTACK;
			case STRENGTH: return InterfaceID.Stats.STRENGTH;
			case DEFENCE: return InterfaceID.Stats.DEFENCE;
			case RANGED: return InterfaceID.Stats.RANGED;
			case PRAYER: return InterfaceID.Stats.PRAYER;
			case MAGIC: return InterfaceID.Stats.MAGIC;
			case RUNECRAFT: return InterfaceID.Stats.RUNECRAFT;
			case CONSTRUCTION: return InterfaceID.Stats.CONSTRUCTION;
			case HITPOINTS: return InterfaceID.Stats.HITPOINTS;
			case AGILITY: return InterfaceID.Stats.AGILITY;
			case HERBLORE: return InterfaceID.Stats.HERBLORE;
			case THIEVING: return InterfaceID.Stats.THIEVING;
			case CRAFTING: return InterfaceID.Stats.CRAFTING;
			case FLETCHING: return InterfaceID.Stats.FLETCHING;
			case SLAYER: return InterfaceID.Stats.SLAYER;
			case HUNTER: return InterfaceID.Stats.HUNTER;
			case MINING: return InterfaceID.Stats.MINING;
			case SMITHING: return InterfaceID.Stats.SMITHING;
			case FISHING: return InterfaceID.Stats.FISHING;
			case COOKING: return InterfaceID.Stats.COOKING;
			case FIREMAKING: return InterfaceID.Stats.FIREMAKING;
			case WOODCUTTING: return InterfaceID.Stats.WOODCUTTING;
			case FARMING: return InterfaceID.Stats.FARMING;
			case SAILING: return InterfaceID.Stats.SAILING;
			default: throw new IllegalArgumentException(skill.name());
		}
	}

	private static int goalEndVarp(Skill skill)
	{
		switch (skill)
		{
			case ATTACK: return VarPlayerID.XPDROPS_ATTACK_END;
			case STRENGTH: return VarPlayerID.XPDROPS_STRENGTH_END;
			case DEFENCE: return VarPlayerID.XPDROPS_DEFENCE_END;
			case RANGED: return VarPlayerID.XPDROPS_RANGED_END;
			case PRAYER: return VarPlayerID.XPDROPS_PRAYER_END;
			case MAGIC: return VarPlayerID.XPDROPS_MAGIC_END;
			case RUNECRAFT: return VarPlayerID.XPDROPS_RUNECRAFT_END;
			case CONSTRUCTION: return VarPlayerID.XPDROPS_CONSTRUCTION_END;
			case HITPOINTS: return VarPlayerID.XPDROPS_HITPOINTS_END;
			case AGILITY: return VarPlayerID.XPDROPS_AGILITY_END;
			case HERBLORE: return VarPlayerID.XPDROPS_HERBLORE_END;
			case THIEVING: return VarPlayerID.XPDROPS_THIEVING_END;
			case CRAFTING: return VarPlayerID.XPDROPS_CRAFTING_END;
			case FLETCHING: return VarPlayerID.XPDROPS_FLETCHING_END;
			case SLAYER: return VarPlayerID.XPDROPS_SLAYER_END;
			case HUNTER: return VarPlayerID.XPDROPS_HUNTER_END;
			case MINING: return VarPlayerID.XPDROPS_MINING_END;
			case SMITHING: return VarPlayerID.XPDROPS_SMITHING_END;
			case FISHING: return VarPlayerID.XPDROPS_FISHING_END;
			case COOKING: return VarPlayerID.XPDROPS_COOKING_END;
			case FIREMAKING: return VarPlayerID.XPDROPS_FIREMAKING_END;
			case WOODCUTTING: return VarPlayerID.XPDROPS_WOODCUTTING_END;
			case FARMING: return VarPlayerID.XPDROPS_FARMING_END;
			case SAILING: return VarPlayerID.XPDROPS_SAILING_END;
			default: throw new IllegalArgumentException(skill.name());
		}
	}

	@Provides
	SkillGoalLevelsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SkillGoalLevelsConfig.class);
	}
}
