package dev.newyear.countdown.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** UUID based, independent of Discord linking. Commit to memory only after the file is saved. */
public final class DisplayNameStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path file;
    private Map<UUID, DisplayNameStyle> names = new LinkedHashMap<>();
    private boolean writable;

    public DisplayNameStore(Path file) { this.file = file; }

    public void load() throws IOException {
        writable = false;
        Map<UUID, DisplayNameStyle> loaded = new LinkedHashMap<>();
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<String, DisplayNameStyle> raw = GSON.fromJson(reader, new TypeToken<Map<String, DisplayNameStyle>>() {}.getType());
                if (raw == null) throw new IllegalArgumentException("Empty display-name data");
                for (var entry : raw.entrySet()) loaded.put(UUID.fromString(entry.getKey()), entry.getValue().checked());
            } catch (RuntimeException e) {
                throw new IOException("표시 이름 파일을 읽지 못했습니다. 파일을 확인하고 서버를 다시 시작하세요.", e);
            }
        }
        names = loaded;
        writable = true;
    }

    public DisplayNameStyle get(UUID id) { return names.get(id); }

    public void set(UUID id, DisplayNameStyle style) throws IOException {
        if (!writable) throw new IOException("표시 이름 파일 오류로 저장할 수 없습니다. 서버 로그를 확인하세요.");
        Map<UUID, DisplayNameStyle> next = new LinkedHashMap<>(names);
        if (style == null) next.remove(id);
        else next.put(id, style.checked());
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { GSON.toJson(next, writer); }
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        names = next;
    }
}
