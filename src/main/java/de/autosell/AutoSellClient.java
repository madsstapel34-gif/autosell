package de.autosell;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

public class AutoSellClient implements ClientModInitializer {

	private enum State { IDLE, WAIT_MENU, MOVING, CLOSING }
	private enum Mode { SHIFT, PICKUP }

	private boolean enabled = false;
	private boolean debug = true;
	private State state = State.IDLE;
	private Mode mode = Mode.SHIFT;

	private int timer = 0;
	private int roundDelay = 20;   // Pause zwischen Runden (Ticks)
	private int perTick = 4;       // Klicks pro Tick
	private int menuTimeout = 0;
	private int stuck = 0;
	private int clicks = 0;
	private Slot lastSource = null;
	private boolean savedPauseOnLostFocus = true;

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
				.then(ClientCommandManager.literal("speed")
					.then(ClientCommandManager.argument("klicks", IntegerArgumentType.integer(1, 36))
						.executes(ctx -> {
							perTick = IntegerArgumentType.getInteger(ctx, "klicks");
							msg(ctx.getSource().getClient(), "Klicks pro Tick: " + perTick);
							return 1;
						})))
				.then(ClientCommandManager.literal("mode")
					.then(ClientCommandManager.argument("art", StringArgumentType.word())
						.executes(ctx -> {
							String a = StringArgumentType.getString(ctx, "art").toLowerCase();
							mode = a.equals("pickup") ? Mode.PICKUP : Mode.SHIFT;
							msg(ctx.getSource().getClient(), "Modus: " + mode);
							return 1;
						})))));

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void toggle(MinecraftClient client) {
		enabled = !enabled;
		state = State.IDLE;
		timer = 0;
		stuck = 0;
		lastSource = null;
		if (enabled) {
			// Kein Pause-Menue bei Fokusverlust -> Makro laeuft auch im Hintergrund
			savedPauseOnLostFocus = client.options.pauseOnLostFocus;
			client.options.pauseOnLostFocus = false;
		} else {
			client.options.pauseOnLostFocus = savedPauseOnLostFocus;
		}
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
				Screen cur = client.currentScreen;
				if (cur instanceof HandledScreen<?> hs) {
					if (hs.getTitle().getString().toLowerCase().contains("sell")
							&& hs.getScreenHandler().slots.size() > 36) {
						dbg(client, "Offenes Sell-Menue erkannt");
						startMoving();
					} else {
						unstick(client);
					}
					return;
				}
				if (cur != null) {
					if (cur instanceof GameMenuScreen) {
						client.setScreen(null);
						dbg(client, "Pause-Menue geschlossen");
					} else {
						unstick(client);
					}
					return;
				}
				stuck = 0;
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
					startMoving();
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
				for (int i = 0; i < perTick; i++) {
					if (!step(client, handler)) {
						dbg(client, "Fertig nach " + clicks + " Klicks");
						state = State.CLOSING;
						timer = 3;
						return;
					}
				}
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

	private void startMoving() {
		state = State.MOVING;
		clicks = 0;
		timer = 3;
	}

	// Haengt ein fremdes Menue fest? Nach 3 Sekunden schliessen.
	private void unstick(MinecraftClient client) {
		if (++stuck > 60) {
			stuck = 0;
			if (client.currentScreen instanceof HandledScreen<?>) {
				client.player.closeHandledScreen();
			} else {
				client.setScreen(null);
			}
			dbg(client, "Festhaengendes Menue geschlossen");
		}
	}

	// Ein Schritt; false = fertig
	private boolean step(MinecraftClient client, ScreenHandler handler) {
		if (clicks++ > 200) return false;

		if (mode == Mode.SHIFT) {
			Slot src = findBerrySlot(handler, client);
			if (src == null || findTargetSlot(handler, client) == null) return false;
			clickSlot(client, handler, src, SlotActionType.QUICK_MOVE);
			return true;
		}

		// PICKUP: Stack aufnehmen und oben ablegen (ohne Mauszeiger)
		if (handler.getCursorStack().isEmpty()) {
			Slot src = findBerrySlot(handler, client);
			Slot dst = findTargetSlot(handler, client);
			if (src == null || dst == null) return false;
			lastSource = src;
			clickSlot(client, handler, src, SlotActionType.PICKUP);
			return true;
		} else {
			Slot dst = findTargetSlot(handler, client);
			if (dst == null) {
				if (lastSource != null) {
					clickSlot(client, handler, lastSource, SlotActionType.PICKUP);
				}
				return false;
			}
			clickSlot(client, handler, dst, SlotActionType.PICKUP);
			return true;
		}
	}

	private void clickSlot(MinecraftClient client, ScreenHandler handler, Slot slot, SlotActionType type) {
		client.interactionManager.clickSlot(handler.syncId, slot.id, 0, type, client.player);
	}

	// Berry-Slot im Spielerinventar (untere 36 Slots)
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
}
