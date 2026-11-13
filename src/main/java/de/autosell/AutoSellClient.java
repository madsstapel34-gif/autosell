package de.autosell;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class AutoSellClient implements ClientModInitializer {

	private enum State { IDLE, WAIT_MENU, MOVING, CLOSING }

	private boolean enabled = false;
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
				if (client.currentScreen != null) return;
				client.getNetworkHandler().sendChatCommand("sell");
				state = State.WAIT_MENU;
				menuTimeout = 60;
			}
			case WAIT_MENU -> {
				if (isSellScreen(client)) {
					state = State.MOVING;
					timer = 5;
				} else if (--menuTimeout <= 0) {
					state = State.IDLE;
					timer = roundDelay;
				}
			}
			case MOVING -> {
				if (!isSellScreen(client)) {
					state = State.IDLE;
					timer = roundDelay;
					return;
				}
				GenericContainerScreen screen = (GenericContainerScreen) client.currentScreen;
				GenericContainerScreenHandler handler = screen.getScreenHandler();

				if (handler.getCursorStack().isEmpty()) {
					Slot src = findBerrySlot(handler, client);
					Slot dst = findTargetSlot(handler, client);
					if (src == null || dst == null) {
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
						// oben alles voll: Rest zurueck in den Ursprungs-Slot
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

	private void click(MinecraftClient client, GenericContainerScreenHandler handler, Slot slot) {
		client.interactionManager.clickSlot(
			handler.syncId, slot.id, 0, SlotActionType.PICKUP, client.player);
	}

	// Slot im Spielerinventar (unten), der Sweet Berries enthaelt
	private Slot findBerrySlot(GenericContainerScreenHandler handler, MinecraftClient client) {
		for (Slot slot : handler.slots) {
			if (slot.inventory == client.player.getInventory()
					&& slot.getStack().isOf(Items.SWEET_BERRIES)) {
				return slot;
			}
		}
		return null;
	}

	// Freier Slot im oberen Sell-Menue (leer oder mit nicht vollem Berry-Stack)
	private Slot findTargetSlot(GenericContainerScreenHandler handler, MinecraftClient client) {
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
	private void moveCursorTo(MinecraftClient client, GenericContainerScreen screen,
							  GenericContainerScreenHandler handler, Slot slot) {
		int bgWidth = 176;
		int bgHeight = 114 + handler.getRows() * 18;
		int left = (screen.width - bgWidth) / 2;
		int top = (screen.height - bgHeight) / 2;
		double scale = client.getWindow().getScaleFactor();
		GLFW.glfwSetCursorPos(client.getWindow().getHandle(),
			(left + slot.x + 8) * scale, (top + slot.y + 8) * scale);
	}

	private boolean isSellScreen(MinecraftClient client) {
		if (!(client.currentScreen instanceof GenericContainerScreen screen)) return false;
		return screen.getTitle().getString().toLowerCase().contains("sell");
	}
}
