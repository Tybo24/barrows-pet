package com.barrowspet;

import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;

/**
 * The pet transmogs. Each one renders the matching Barrows brother NPC's models,
 * using the same ready/walk animations the real NPC uses.
 */
@Getter
@RequiredArgsConstructor
public enum Brother
{
	AHRIM("Ahrim", NpcID.BARROWS_AHRIM, VarbitID.BARROWS_KILLED_AHRIM, AnimationID.HUMAN_STAFFREADY, AnimationID.HUMAN_HALBERDWALK_F),
	DHAROK("Dharok", NpcID.BARROWS_DHAROK, VarbitID.BARROWS_KILLED_DHAROK, AnimationID.BARROW_DHAROK_READY, AnimationID.BARROW_DHAROK_WALK),
	GUTHAN("Guthan", NpcID.BARROWS_GUTHAN, VarbitID.BARROWS_KILLED_GUTHAN, AnimationID.HUMAN_STAFFREADY, AnimationID.HUMAN_HALBERDWALK_F),
	KARIL("Karil", NpcID.BARROWS_KARIL, VarbitID.BARROWS_KILLED_KARIL, AnimationID.HUMAN_READY, AnimationID.HUMAN_WALK_F),
	TORAG("Torag", NpcID.BARROWS_TORAG, VarbitID.BARROWS_KILLED_TORAG, AnimationID.HUMAN_READY, AnimationID.HUMAN_WALK_F),
	VERAC("Verac", NpcID.BARROWS_VERAC, VarbitID.BARROWS_KILLED_VERAC, AnimationID.BARROW_GUTHAN_READY, AnimationID.BARROW_GUTHAN_WALK);

	private final String displayName;
	private final int npcId;
	/** Set while this brother has been killed on the current Barrows run. */
	private final int killedVarbit;
	private final int readyAnimation;
	private final int walkAnimation;

	@Override
	public String toString()
	{
		return displayName;
	}

	@Nullable
	static Brother forKilledVarbit(int varbitId)
	{
		for (Brother brother : values())
		{
			if (brother.killedVarbit == varbitId)
			{
				return brother;
			}
		}
		return null;
	}
}
