package dev.newyear.countdown.client;

import dev.newyear.countdown.discord.DiscordSocialPackets;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs our small official-SDK host on the player's machine. Never runs a server-supplied command/path. */
public final class DiscordSocialBridge {
    private DiscordSocialBridge() {}
    public record Reply(String code, String redirect) {}
    public static final class Attempt implements AutoCloseable {
        public final CompletableFuture<Reply> result = new CompletableFuture<>();
        private volatile Process process;
        private volatile boolean closed;
        @Override public void close() {
            closed = true;
            Process p = process;
            if (p != null) p.destroyForcibly();
            result.cancel(false);
        }
    }

    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String cpu = arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : arch.equals("amd64") || arch.equals("x86_64") ? "x86_64" : "unsupported";
        return (os.startsWith("windows") ? "windows" : os.contains("mac") ? "macos" : os.contains("linux") ? "linux" : "unsupported") + "-" + cpu;
    }
    private static String executable() { return platform().startsWith("windows-") ? "newyear-discord-social.exe" : "newyear-discord-social"; }
    private static String library() { return platform().startsWith("windows-") ? "discord_partner_sdk.dll" : platform().startsWith("macos-") ? "libdiscord_partner_sdk.dylib" : "libdiscord_partner_sdk.so"; }
    private static String resource(String name) { return "/native/discord-social/" + platform() + "/" + name; }
    private static Path directory() { return FabricLoader.getInstance().getConfigDir().resolve("newyearcountdown-social-sdk").resolve(platform()); }
    public static boolean available() {
        if (platform().contains("unsupported")) return false;
        return (Files.isRegularFile(directory().resolve(executable())) && Files.isRegularFile(directory().resolve(library())))
                || (DiscordSocialBridge.class.getResource(resource(executable())) != null && DiscordSocialBridge.class.getResource(resource(library())) != null);
    }
    private static Path prepare() throws IOException {
        Path dir = directory();
        if (DiscordSocialBridge.class.getResource(resource(executable())) != null) {
            Files.createDirectories(dir);
            for (String name : new String[]{library(), executable()}) {
                try (InputStream input = DiscordSocialBridge.class.getResourceAsStream(resource(name))) {
                    if (input == null) throw new IOException("Social SDK 실행 파일이 없습니다");
                    Files.copy(input, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (!platform().startsWith("windows-") && !dir.resolve(executable()).toFile().setExecutable(true, true))
                throw new IOException("Social SDK 실행 권한을 설정하지 못했습니다");
        }
        return dir.resolve(executable()).toAbsolutePath();
    }
    public static Attempt authorize(DiscordSocialPackets.Challenge challenge) {
        Attempt attempt = new Attempt();
        Thread worker = new Thread(() -> {
            Process process = null;
            try {
                if (!validChallenge(challenge)) throw new IOException("올바르지 않은 인증 요청");
                Path helper = prepare();
                if (attempt.closed) return;
                process = new ProcessBuilder(helper.toString()).directory(helper.getParent().toFile())
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                attempt.process = process;
                if (attempt.closed) { process.destroyForcibly(); return; }
                String request = challenge.clientId() + "\n" + challenge.challenge() + "\n" + challenge.session() + "\n" + ProcessHandle.current().pid() + "\n";
                try (var out = process.getOutputStream()) { out.write(request.getBytes(StandardCharsets.US_ASCII)); }
                Process child = process;
                CompletableFuture.delayedExecutor(150, TimeUnit.SECONDS).execute(() -> { if (child.isAlive()) child.destroyForcibly(); });
                // Bounded output, including unwanted library logs; never forward native output into logs/chat.
                byte[] output = process.getInputStream().readNBytes(16_385);
                if (output.length > 16_384) throw new IOException("인증 응답이 너무 큽니다");
                if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("인증이 취소되었거나 완료되지 않았습니다");
                attempt.result.complete(parse(new String(output, StandardCharsets.UTF_8)));
            } catch (Exception e) {
                attempt.result.completeExceptionally(new IOException("Discord 인증창을 완료하지 못했습니다. 다시 연결하거나 코드로 연동하세요."));
            } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
        }, "NewYear-Discord-Social-SDK");
        worker.setDaemon(true); worker.start();
        return attempt;
    }
    static boolean validChallenge(DiscordSocialPackets.Challenge c) {
        return c.clientId().matches("[0-9]{1,20}") && c.challenge().matches("[A-Za-z0-9_-]{43}") && c.session().matches("[A-Za-z0-9_-]{43}");
    }
    static Reply parse(String output) throws IOException {
        String[] lines = output.strip().split("\\R");
        for (String line : lines) if (line.startsWith("NYDS1 OK ")) {
            String[] parts = line.split(" ", -1);
            if (parts.length != 4 || parts[2].length() > 4096 || parts[3].length() > 512) break;
            try {
                String code = new String(HexFormat.of().parseHex(parts[2]), StandardCharsets.UTF_8);
                String redirect = new String(HexFormat.of().parseHex(parts[3]), StandardCharsets.UTF_8);
                if (code.isBlank() || code.chars().anyMatch(Character::isISOControl)) break;
                return new Reply(code, redirect);
            } catch (IllegalArgumentException e) { break; }
        }
        throw new IOException("Discord 인증 결과가 없습니다");
    }
}
