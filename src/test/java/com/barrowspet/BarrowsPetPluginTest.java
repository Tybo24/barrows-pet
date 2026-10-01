package com.barrowspet;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BarrowsPetPluginTest
{
	public static void main(String[] args) throws Exception
	{
		// The dev tools plugin lives in src/test, so it is only ever loaded here and never ships.
		ExternalPluginManager.loadBuiltin(BarrowsPetPlugin.class, BarrowsPetDevToolsPlugin.class);
		RuneLite.main(args);
	}
}
