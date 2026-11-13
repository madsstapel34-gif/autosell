package de.autosell;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class AutoSellClient implements ClientModInitializer {

	private enum State { IDLE, WAIT_MENU, MOVING, CLOSING }

	private boolean enabled = false;
	private boolean debug = true;
	private State state = State.IDLE;
	private int timer = 0;
	private int roundDelay = 40;   // Pause zwischen Runden (Ticks)
	private int clickDelay = 3;    // Pause zwischen Klicks (Ticks)
	private int menuTimeout = 0;
	private Slot lastSource = null;

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("autosell")
				.executes(ctx -> {
					toggle(ctx.getSource().getClient());
					return 1;
				})
				.then(ClientCommandManager.literal("debug")
					.executes(ctx -> {
						debug = !debug;
						msg(ctx.getSource().getClient(), "Debug " + (debug ? "AN" : "AUS"));
						return 1;
					}))
				.then(ClientCommandManager.literal("delay")
					.then(ClientCommandManager.argument("ticks", IntegerArgumentType.integer(5, 1200))
						.executes(ctx -> {
							roundDelay = IntegerArgumentType.getInteger(ctx, "ticks");
							msg(ctx.getSource().getClient(), "Pause zwischen Runden: " + roundDelay + " Ticks");
							return 1;
						})))
				.then(ClientCommandManager.literal("clickdelay")
					.then(ClientCommandManager.argument("ticks", IntegerArgumentType.integer(1, 20))
						.executes(ctx -> {
							clickDelay = IntegerArgumentType.getInteger(ctx, "ticks");
							msg(ctx.getSource().getClient(), "Pause zwischen Klicks: " + clickDelay + " Ticks");
							return 1;
						})))));

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void toggle(MinecraftClient client) {
		enabled = !enabled;
		state = State.IDLE;
		timer = 0;
		lastSource = null;
		msg(client, enabled ? "AN" : "AUS");
	}

	private void msg(MinecraftClient client, String text) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal("[AutoSell] " + text), false);
		}
	}

	private void dbg(MinecraftClient client, String text) {
		if (debug) msg(client, text);
	}

	private void tick(MinecraftClient client) {
		if (!enabled || client.player == null || client.getNetworkHandler() == null
				|| client.interactionManager == null) {
			return;
		}

		if (timer > 0) {
			timer--;
			return;
		}

		switch (state) {
			case IDLE -> {
				if (client.currentScreen instanceof HandledScreen<?> hs) {
					// Menue ist schon offen: nur loslegen, wenn es nach Sell aussieht
					if (hs.getTitle().getString().toLowerCase().contains("sell")
							&& hs.getScreenHandler().slots.size() > 36) {
						dbg(client, "Offenes Sell-Menue erkannt");
						state = State.MOVING;
						timer = 3;
					}
					return;
				}
				if (client.currentScreen != null) return;
				client.getNetworkHandler().sendChatCommand("sell");
				state = State.WAIT_MENU;
				menuTimeout = 60;
			}
			case WAIT_MENU -> {
				if (client.currentScreen instanceof HandledScreen<?> hs
						&& hs.getScreenHandler().slots.size() > 36) {
					dbg(client, "Menue erkannt: " + hs.getClass().getSimpleName()
						+ ", Titel=\"" + hs.getTitle().getString() + "\", Slots="
						+ hs.getScreenHandler().slots.size());
					state = State.MOVING;
					timer = 5;
				} else if (--menuTimeout <= 0) {
					dbg(client, "Kein Menue nach /sell erschienen, neuer Versuch");
					state = State.IDLE;
					timer = roundDelay;
				}
			}
			case MOVING -> {
				if (!(client.currentScreen instanceof HandledScreen<?> screen)
						|| screen.getScreenHandler().slots.size() <= 36) {
					state = State.IDLE;
					timer = roundDelay;
					return;
				}
				ScreenHandler handler = screen.getScreenHandler();

				if (handler.getCursorStack().isEmpty()) {
					Slot src = findBerrySlot(handler, client);
					Slot dst = findTargetSlot(handler, client);
					if (src == null || dst == null) {
						dbg(client, src == null
							? "Fertig: keine Berries mehr im Inventar"
							: "Fertig: oberes Menue ist voll");
						state = State.CLOSING;
						timer = 5;
						return;
					}
					lastSource = src;
					moveCursorTo(client, screen, handler, src);
					click(client, handler, src);
				} else {
					Slot dst = findTargetSlot(handler, client);
					if (dst == null) {
						if (lastSource != null) {
							moveCursorTo(client, screen, handler, lastSource);
							click(client, handler, lastSource);
						}
						state = State.CLOSING;
						timer = 5;
						return;
					}
					moveCursorTo(client, screen, handler, dst);
					click(client, handler, dst);
				}
				timer = clickDelay;
			}
			case CLOSING -> {
				if (client.currentScreen != null) {
					client.player.closeHandledScreen(); // Schliessen = Verkaufen
				}
				lastSource = null;
				state = State.IDLE;
				timer = roundDelay;
			}
		}
	}

	private void click(MinecraftClient client, ScreenHandler handler, Slot slot) {
		client.interactionManager.clickSlot(
			handler.syncId, slot.id, 0, SlotActionType.PICKUP, client.player);
	}

	// Slot im Spielerinventar (die unteren 36 Slots) mit Sweet Berries
	private Slot findBerrySlot(ScreenHandler handler, MinecraftClient client) {
		for (Slot slot : handler.slots) {
			if (slot.inventory == client.player.getInventory()
					&& slot.getStack().isOf(Items.SWEET_BERRIES)) {
				return slot;
			}
		}
		return null;
	}

	// Freier Slot im oberen Menue
	private Slot findTargetSlot(ScreenHandler handler, MinecraftClient client) {
		for (Slot slot : handler.slots) {
			if (slot.inventory == client.player.getInventory()) continue;
			var stack = slot.getStack();
			if (stack.isEmpty()
					|| (stack.isOf(Items.SWEET_BERRIES) && stack.getCount() < stack.getMaxCount())) {
				return slot;
			}
		}
		return null;
	}

	// Setzt den echten Mauszeiger mittig auf den Slot
	private void moveCursorTo(MinecraftClient client, HandledScreen<?> screen,
							  ScreenHandler handler, Slot slot) {
		int rows = (handler.slots.size() - 36) / 9;
		int bgWidth = 176;
		int bgHeight = 114 + rows * 18;
		int left = (screen.width - bgWidth) / 2;
		int top = (screen.height - bgHeight) / 2;
		double scale = client.getWindow().getScaleFactor();
		GLFW.glfwSetCursorPos(client.getWindow().getHandle(),
			(left + slot.x + 8) * scale, (top + slot.y + 8) * scale);
	}
}
