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

/**
 * AutoSell: tippt /sell, packt alle Sweet Berries per Shift-Klick ins Menue,
 * schliesst das Menue (verkauft) und wiederholt das Ganze.
 *
 * Steuerung im Spiel:
 *   /autosell            -> an/aus
 *   /autosell delay <t>  -> Pause zwischen den Runden in Ticks (20 = 1 Sekunde)
 */
public class AutoSellClient implements ClientModInitializer {

	private enum State { IDLE, WAIT_MENU, MOVING, CLOSING }

	private boolean enabled = false;
	private State state = State.IDLE;
	private int timer = 0;
	private int roundDelay = 40;     // Pause zwischen zwei Runden (Ticks)
	private int clickDelay = 1;      // Ticks zwischen einzelnen Klicks
	private int menuTimeout = 0;

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
						})))));

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void toggle(MinecraftClient client) {
		enabled = !enabled;
		state = State.IDLE;
		timer = 0;
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
				// Nur /sell senden, wenn kein anderes Menue offen ist
				if (client.currentScreen != null) return;
				client.getNetworkHandler().sendChatCommand("sell");
				state = State.WAIT_MENU;
				menuTimeout = 60; // 3 Sekunden auf das Menue warten
			}
			case WAIT_MENU -> {
				if (isSellScreen(client)) {
					state = State.MOVING;
					timer = 3; // kurz warten, bis alle Slots synchronisiert sind
				} else if (--menuTimeout <= 0) {
					// Menue ging nicht auf -> neu versuchen
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
				GenericContainerScreenHandler handler =
					((GenericContainerScreen) client.currentScreen).getScreenHandler();

				Slot berrySlot = null;
				for (Slot slot : handler.slots) {
					// nur Slots aus dem Spielerinventar (unterer Teil), nicht aus dem Sell-Menue
					if (slot.inventory == client.player.getInventory()
							&& slot.getStack().isOf(Items.SWEET_BERRIES)) {
						berrySlot = slot;
						break;
					}
				}

				if (berrySlot != null) {
					client.interactionManager.clickSlot(
						handler.syncId, berrySlot.id, 0, SlotActionType.QUICK_MOVE, client.player);
					timer = clickDelay;
				} else {
					state = State.CLOSING;
					timer = 5;
				}
			}
			case CLOSING -> {
				// Viele /sell-Menues verkaufen beim Schliessen
				if (client.currentScreen != null) {
					client.player.closeHandledScreen();
				}
				state = State.IDLE;
				timer = roundDelay;
			}
		}
	}

	private boolean isSellScreen(MinecraftClient client) {
		if (!(client.currentScreen instanceof GenericContainerScreen screen)) return false;
		return screen.getTitle().getString().toLowerCase().contains("sell");
	}
}
