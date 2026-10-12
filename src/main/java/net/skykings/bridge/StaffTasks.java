package net.skykings.bridge;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Staff actions asked for on Discord (promote, kick, ...). They're only shown
 * to you; nothing runs until you click [Run] and press Enter.
 *
 * The rules below match the bridge server's own checks. They're
 * checked here too, so even a hacked or fake bridge server can only ever get
 * you to run "/g promote|demote|invite|unmute|kick|mute <player>".
 */
public final class StaffTasks {
    private StaffTasks() {}

    private static final Pattern IGN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern DURATION = Pattern.compile("^[1-9][0-9]{0,2}[mhd]$");
    private static final Pattern REASON = Pattern.compile("^[A-Za-z0-9 .,!?'()-]{1,60}$");
    private static final long RESULT_WINDOW_MS = 4000;

    public record Task(long id, String action, String target, String extra, String requestedBy) {
        String describe() {
            return action + " " + target + (extra == null ? "" : " (" + extra + ")");
        }
    }

    private static final Map<Long, Task> open = new LinkedHashMap<>();
    private static Task running;
    private static long runningSince;
    private static final List<String> resultLines = new ArrayList<>();

    /** The /g command for a task, or null if anything about it isn't allowed. */
    public static String buildCommand(String action, String target, String extra) {
        if (action == null || target == null || !IGN.matcher(target).matches()) return null;
        switch (action) {
            case "promote", "demote", "invite", "unmute" -> {
                return extra == null ? "g " + action + " " + target : null;
            }
            case "kick" -> {
                return extra != null && REASON.matcher(extra).matches() ? "g kick " + target + " " + extra : null;
            }
            case "mute" -> {
                return extra != null && DURATION.matcher(extra).matches() ? "g mute " + target + " " + extra : null;
            }
            default -> {
                return null;
            }
        }
    }

    public static synchronized void add(Task task) {
        if (buildCommand(task.action(), task.target(), task.extra()) == null) return; // never show what we wouldn't run
        if (open.put(task.id(), task) != null) return; // already shown
        ChatDisplay.show(Component.literal("[Bridge] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Staff task: ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(ChatDisplay.safe(task.describe(), 120)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (asked by " + ChatDisplay.safe(task.requestedBy(), 32) + " on Discord) ").withStyle(ChatFormatting.GRAY))
                .append(ChatDisplay.button("Run", ChatFormatting.GREEN, "/gbridge run " + task.id(),
                        "Click, then press Enter to run /" + buildCommand(task.action(), task.target(), task.extra())))
                .append(Component.literal(" "))
                .append(ChatDisplay.button("Dismiss", ChatFormatting.RED, "/gbridge dismiss " + task.id(),
                        "Click, then press Enter to dismiss this for everyone")));
    }

    public static synchronized void remove(long id) {
        open.remove(id);
    }

    public static synchronized boolean isOpen(long id) {
        return open.containsKey(id);
    }

    public static synchronized List<Task> all() {
        return new ArrayList<>(open.values());
    }

    public static synchronized void clear() {
        open.clear();
        running = null;
    }

    /**
     * The server gave us the task (no other staff member took it first): run
     * it now, because the player just asked to. The command is rebuilt from
     * the task we showed them; the server's copy is never used.
     */
    public static void claimed(long id) {
        Task task;
        synchronized (StaffTasks.class) {
            task = open.remove(id);
        }
        if (task == null) return;
        String command = buildCommand(task.action(), task.target(), task.extra());
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (command == null || client.player == null) {
                GuildBridgeMod.connection().send("task_release", Map.of("id", id));
                return;
            }
            synchronized (StaffTasks.class) {
                running = task;
                runningSince = System.currentTimeMillis();
                resultLines.clear();
            }
            client.player.connection.sendCommand(command);
        });
    }

    // Hypixel's replies to /g commands are system lines about the player, or
    // errors starting like these. Anything else (DMs, party chat, other
    // players talking) is never reported, so it can't leak into Discord.
    private static final Pattern REPLY_ERROR = Pattern.compile(
            "^(You |You're |That player |This player |Can't |Cannot |Couldn't |Invalid |Unknown |Usage: |There is no ).{0,150}$");
    private static final Pattern CHAT_LINE = Pattern.compile(
            "^(From |To |Party > |Guild > |Officer > |Co-op > |\\[NPC\\] |\\[\\d+\\] )|^(\\[[A-Z+]+\\] )?[A-Za-z0-9_]{1,16}: ");

    /** Collect Hypixel's reply to the command we just ran, to report back to Discord. */
    public static void onChatLine(String line) {
        synchronized (StaffTasks.class) {
            if (running == null || resultLines.size() >= 3 || CHAT_LINE.matcher(line).find()) return;
            boolean aboutTarget = line.toLowerCase().contains(running.target().toLowerCase()) && line.length() <= 150;
            if (aboutTarget || REPLY_ERROR.matcher(line).matches()) resultLines.add(line);
        }
    }

    /** Called every tick: report the result a few seconds after running. */
    public static void tick() {
        Task task;
        String result;
        synchronized (StaffTasks.class) {
            if (running == null || System.currentTimeMillis() - runningSince < RESULT_WINDOW_MS) return;
            task = running;
            result = String.join(" ", resultLines);
            running = null;
        }
        GuildBridgeMod.connection().send("task_result", Map.of("id", task.id(), "result", ChatDisplay.safe(result, 200)));
    }
}
