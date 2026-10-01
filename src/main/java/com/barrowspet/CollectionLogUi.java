package com.barrowspet;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.WidgetNode;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Text;

/**
 * Client-side collection log visuals: the "Collection log" popup, and an extra slot on the Barrows Chests page.
 * Both only change what this client draws; the real collection log is untouched.
 */
@Slf4j
@Singleton
class CollectionLogUi
{
	/** notification_display_init(title, text, colour): fills in the popup and starts its open animation. */
	private static final int NOTIFICATION_DISPLAY_INIT = 3343;
	/** Give up waiting for the popup to appear after this many client ticks. */
	private static final int POPUP_OPEN_TIMEOUT = 100;

	private static final String BARROWS_PAGE_TITLE = "Barrows Chests";
	private static final int ICON_SIZE = 25;
	private static final int DEFAULT_UNOBTAINED_OPACITY = 175;
	private static final Pattern OBTAINED_COUNT = Pattern.compile("(\\d+)/(\\d+)");

	private final Client client;
	private final ClientThread clientThread;

	private int pendingPopups;

	@Inject
	CollectionLogUi(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	void queuePopup()
	{
		pendingPopups++;
		// Scripts are not reentrant, and this can be reached from inside one (e.g. a :: command),
		// so open the popup once the current script has finished.
		clientThread.invokeLater(this::showPendingPopup);
	}

	void clearPendingPopups()
	{
		pendingPopups = 0;
	}

	/**
	 * Retry queued popups, e.g. if a real one was on screen when the pet was rolled.
	 */
	void onGameTick()
	{
		if (pendingPopups > 0)
		{
			showPendingPopup();
		}
	}

	private void showPendingPopup()
	{
		if (pendingPopups <= 0 || client.getGameState() != GameState.LOGGED_IN || client.getWidget(InterfaceID.NotificationDisplay.UNIVERSE) != null)
		{
			return;
		}

		int parent = notificationParent();
		if (parent == -1)
		{
			return;
		}

		pendingPopups--;
		WidgetNode node = client.openInterface(parent, InterfaceID.NOTIFICATION_DISPLAY, WidgetModalMode.MODAL_CLICKTHROUGH);
		client.runScript(NOTIFICATION_DISPLAY_INIT, "Collection log", "New item:<br><br><col=ffffff>" + BarrowsPetPlugin.PET_NAME + "</col>", -1);

		// The game's scripts animate the popup open and then shrink it back to nothing; close it once it has shrunk.
		int[] ticksWaiting = {0};
		boolean[] opened = {false};
		clientThread.invokeLater(() ->
		{
			if (client.getWidget(InterfaceID.NotificationDisplay.UNIVERSE) == null)
			{
				return true;
			}

			Widget container = client.getWidget(InterfaceID.NotificationDisplay.CONTAINER);
			if (container != null && container.getWidth() > 0)
			{
				opened[0] = true;
				return false;
			}

			if (!opened[0] && ++ticksWaiting[0] < POPUP_OPEN_TIMEOUT)
			{
				return false;
			}

			client.closeInterface(node, true);
			return true;
		});
	}

	private int notificationParent()
	{
		switch (client.getTopLevelInterfaceId())
		{
			case InterfaceID.TOPLEVEL:
				return InterfaceID.Toplevel.NOTIFICATIONS;
			case InterfaceID.TOPLEVEL_OSRS_STRETCH:
				return InterfaceID.ToplevelOsrsStretch.NOTIFICATIONS;
			case InterfaceID.TOPLEVEL_PRE_EOC:
				return InterfaceID.ToplevelPreEoc.NOTIFICATIONS;
			case InterfaceID.TOPLEVEL_OSM:
				return InterfaceID.ToplevelOsm.NOTIFICATIONS;
			case InterfaceID.TOPLEVEL_DISPLAY:
				return InterfaceID.ToplevelDisplay.NOTIFICATIONS;
			default:
				log.debug("Unknown top level interface {}", client.getTopLevelInterfaceId());
				return -1;
		}
	}

	/**
	 * Called after the collection log draws a page. If it is the Barrows Chests page, append the pet slot.
	 */
	void onCollectionLogDrawn(boolean unlocked)
	{
		Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
		Widget items = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (header == null || items == null || header.getChild(0) == null
			|| !BARROWS_PAGE_TITLE.equals(Text.removeTags(header.getChild(0).getText())))
		{
			return;
		}

		Widget[] children = items.getDynamicChildren();
		List<Widget> slots = new ArrayList<>();
		int unobtainedOpacity = DEFAULT_UNOBTAINED_OPACITY;
		for (Widget child : children)
		{
			if (BarrowsPetPlugin.PET_NAME.equals(child.getName()))
			{
				// Already added since the page was last drawn.
				return;
			}

			if (child.getItemId() > 0)
			{
				slots.add(child);
				if (child.getOpacity() > 0)
				{
					unobtainedOpacity = child.getOpacity();
				}
			}
		}

		if (slots.isEmpty())
		{
			return;
		}

		// Work out the grid from the existing item slots, then use the next free cell.
		TreeSet<Integer> columns = new TreeSet<>();
		TreeSet<Integer> rows = new TreeSet<>();
		for (Widget slot : slots)
		{
			columns.add(slot.getOriginalX());
			rows.add(slot.getOriginalY());
		}

		Widget first = slots.get(0);
		int rowStep = rows.size() > 1 ? rows.higher(rows.first()) - rows.first() : first.getOriginalHeight() + 4;
		List<Integer> columnXs = new ArrayList<>(columns);
		int index = slots.size();
		int x = columnXs.get(index % columnXs.size());
		int y = rows.first() + (index / columnXs.size()) * rowStep;

		Widget pet = items.createChild(-1, WidgetType.GRAPHIC);
		pet.setSpriteId(SpriteID.IconBoss25x25.BARROWS_CHESTS);
		pet.setOriginalX(x + (first.getOriginalWidth() - ICON_SIZE) / 2);
		pet.setOriginalY(y + (first.getOriginalHeight() - ICON_SIZE) / 2);
		pet.setOriginalWidth(ICON_SIZE);
		pet.setOriginalHeight(ICON_SIZE);
		pet.setOpacity(unlocked ? 0 : unobtainedOpacity);
		pet.setName(BarrowsPetPlugin.PET_NAME);
		pet.revalidate();

		int bottom = y + first.getOriginalHeight();
		if (bottom > items.getScrollHeight())
		{
			items.setScrollHeight(bottom);
			items.revalidateScroll();
			// This runs inside the collection log's draw script and scripts are not reentrant,
			// so resize the scrollbar once that script has finished.
			clientThread.invokeLater(() ->
			{
				Widget contents = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
				if (contents != null)
				{
					client.runScript(ScriptID.UPDATE_SCROLLBAR, InterfaceID.Collection.ITEMS_SCROLLBAR, InterfaceID.Collection.ITEMS_CONTENTS, contents.getScrollY());
				}
			});
		}

		updateObtainedCount(header, unlocked);
	}

	/**
	 * Bump the page's "Obtained: x/y" header to include the pet.
	 */
	private static void updateObtainedCount(Widget header, boolean unlocked)
	{
		for (Widget line : header.getDynamicChildren())
		{
			String text = line.getText();
			if (text == null || !text.contains("Obtained"))
			{
				continue;
			}

			Matcher matcher = OBTAINED_COUNT.matcher(text);
			if (matcher.find())
			{
				int obtained = Integer.parseInt(matcher.group(1)) + (unlocked ? 1 : 0);
				int total = Integer.parseInt(matcher.group(2)) + 1;
				line.setText(text.substring(0, matcher.start()) + obtained + "/" + total + text.substring(matcher.end()));
			}
			return;
		}
	}
}
