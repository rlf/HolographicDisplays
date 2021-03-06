package com.gmail.filoghost.holographicdisplays.placeholder;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import com.gmail.filoghost.holographicdisplays.api.placeholder.PlaceholderReplacer;
import com.gmail.filoghost.holographicdisplays.bridge.bungeecord.BungeeServerTracker;
import com.gmail.filoghost.holographicdisplays.nms.interfaces.entity.NMSNameable;
import com.gmail.filoghost.holographicdisplays.object.line.CraftTextLine;
import com.gmail.filoghost.holographicdisplays.task.WorldPlayerCounterTask;
import com.gmail.filoghost.holographicdisplays.util.Utils;

public class PlaceholdersManager {
	
	private static long elapsedTenthsOfSecond;
	protected static Set<DynamicLineData> linesToUpdate = Utils.newSet();
	
	private static final Pattern BUNGEE_ONLINE_PATTERN = makePlaceholderWithArgsPattern("online");
	private static final Pattern ANIMATION_PATTERN = makePlaceholderWithArgsPattern("animation");
	private static final Pattern WORLD_PATTERN = makePlaceholderWithArgsPattern("world");
	
	private static Pattern makePlaceholderWithArgsPattern(String prefix) {
		return Pattern.compile("(\\{" + Pattern.quote(prefix) + ":)(.+?)(\\})");
	}
	
	private static String extractArgumentFromPlaceholder(Matcher matcher) {
		return matcher.group(2).trim();
	}
	
	
	public static void load(Plugin plugin) {
		
		Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
			
			@Override
			public void run() {
				
				for (Placeholder placeholder : PlaceholdersRegister.getPlaceholders()) {
					if (elapsedTenthsOfSecond % placeholder.getTenthsToRefresh() == 0) {
						placeholder.update();
					}
				}
				
				for (Placeholder placeholder : AnimationsRegister.getAnimations().values()) {
					if (elapsedTenthsOfSecond % placeholder.getTenthsToRefresh() == 0) {
						placeholder.update();
					}
				}
				
				Iterator<DynamicLineData> iter = linesToUpdate.iterator();
				DynamicLineData currentLineData;
				
				while (iter.hasNext()) {
					currentLineData = iter.next();
					
					if (currentLineData.getEntity().isDeadNMS()) {
						iter.remove();
					} else {
						updatePlaceholders(currentLineData);
					}
				}
				
				elapsedTenthsOfSecond++;
			}
			
		}, 2L, 2L);
	}
	
	
	public static void untrackAll() {
		linesToUpdate.clear();
	}
	
	public static void untrack(CraftTextLine line) {
		
		if (line == null || !line.isSpawned()) {
			return;
		}
		
		Iterator<DynamicLineData> iter = linesToUpdate.iterator();
		while (iter.hasNext()) {
			DynamicLineData data = iter.next();
			if (data.getEntity() == line.getNmsNameble()) {
				iter.remove();
				data.getEntity().setCustomNameNMS(data.getOriginalName());
			}
		}
	}
	
	public static void trackIfNecessary(CraftTextLine line) {
		
		NMSNameable nameableEntity = line.getNmsNameble();
		String name = line.getText();
		
		if (nameableEntity == null) {
			return;
		}
		
		boolean updateName = false;
		
		if (name == null || name.isEmpty()) {
			return;
		}

		// Lazy initialization.
		Set<Placeholder> normalPlaceholders = null;
		
		Map<String, PlaceholderReplacer> bungeeOnlinePlayersReplacers = null;
		Map<String, PlaceholderReplacer> worldsOnlinePlayersReplacers = null;
		Map<String, Placeholder> animationsPlaceholders = null;
		
		Matcher matcher;
		
		for (Placeholder placeholder : PlaceholdersRegister.getPlaceholders()) {
			
			if (name.contains(placeholder.getTextPlaceholder())) {
				
				if (normalPlaceholders == null) {
					normalPlaceholders = Utils.newSet();
				}
				
				normalPlaceholders.add(placeholder);
			}
		}
		
		
		// Players in a world count pattern.
		matcher = WORLD_PATTERN.matcher(name);
		while (matcher.find()) {
							
			if (worldsOnlinePlayersReplacers == null) {
				worldsOnlinePlayersReplacers = Utils.newMap();
			}
							
			final String worldName = extractArgumentFromPlaceholder(matcher);
			worldsOnlinePlayersReplacers.put(matcher.group(), new PlaceholderReplacer() {
				
				@Override
				public String update() {
					return WorldPlayerCounterTask.getCount(worldName);
				}
			});
		}
		
		// BungeeCord online pattern.
		matcher = BUNGEE_ONLINE_PATTERN.matcher(name);
		while (matcher.find()) {
			
			if (bungeeOnlinePlayersReplacers == null) {
				bungeeOnlinePlayersReplacers = Utils.newMap();
			}
			
			final String serverName = extractArgumentFromPlaceholder(matcher);
			BungeeServerTracker.track(serverName); // Track this server.
			
			// Add it to tracked servers.
			bungeeOnlinePlayersReplacers.put(matcher.group(), new PlaceholderReplacer() {
				
				@Override
				public String update() {
					return String.valueOf(BungeeServerTracker.getPlayersOnline(serverName));
				}
			});
		}
		
		// Animation pattern.
		matcher = ANIMATION_PATTERN.matcher(name);
		while (matcher.find()) {

			String fileName = extractArgumentFromPlaceholder(matcher);
			Placeholder animation = AnimationsRegister.getAnimation(fileName);
			
			// If exists...
			if (animation != null) {
				
				if (animationsPlaceholders == null) {
					animationsPlaceholders = Utils.newMap();
				}
				
				animationsPlaceholders.put(matcher.group(), animation);
				
			} else {
				name = name.replace(matcher.group(), "[Animation not found: " + fileName + "]");
				updateName = true;
			}
		}
		
		if (normalPlaceholders != null || bungeeOnlinePlayersReplacers != null || worldsOnlinePlayersReplacers != null || animationsPlaceholders != null) {
			DynamicLineData lineData = new DynamicLineData(nameableEntity, name);
			
			if (normalPlaceholders != null) {
				lineData.setPlaceholders(normalPlaceholders);
			}
			
			if (bungeeOnlinePlayersReplacers != null) {
				lineData.getReplacers().putAll(bungeeOnlinePlayersReplacers);
			}
			
			if (worldsOnlinePlayersReplacers != null) {
				lineData.getReplacers().putAll(worldsOnlinePlayersReplacers);
			}
			
			if (animationsPlaceholders != null) {
				lineData.getAnimations().putAll(animationsPlaceholders);
			}
			
			// It could be already tracked!
			if (!linesToUpdate.add(lineData)) {
				linesToUpdate.remove(lineData);
				linesToUpdate.add(lineData);
			}
			
			updatePlaceholders(lineData);
			
		} else {
			
			// The name needs to be updated anyways.
			if (updateName) {
				nameableEntity.setCustomNameNMS(name);
			}
		}
	}
	
	
	private static void updatePlaceholders(DynamicLineData lineData) {
		
		String oldCustomName = lineData.getEntity().getCustomNameNMS();
		String newCustomName = lineData.getOriginalName();
		
		if (!lineData.getPlaceholders().isEmpty()) {
			for (Placeholder placeholder : lineData.getPlaceholders()) {
				newCustomName = newCustomName.replace(placeholder.getTextPlaceholder(), placeholder.getCurrentReplacement());
			}
		}
		
		if (!lineData.getReplacers().isEmpty()) {
			for (Entry<String, PlaceholderReplacer> entry : lineData.getReplacers().entrySet()) {
				newCustomName = newCustomName.replace(entry.getKey(), entry.getValue().update());
			}
		}
		
		if (!lineData.getAnimations().isEmpty()) {
			for (Entry<String, Placeholder> entry : lineData.getAnimations().entrySet()) {
				newCustomName = newCustomName.replace(entry.getKey(), entry.getValue().getCurrentReplacement());
			}
		}
		
		// Update only if needed, don't send useless packets.
		if (!oldCustomName.equals(newCustomName)) {
			lineData.getEntity().setCustomNameNMS(newCustomName);
		}
	}

}
