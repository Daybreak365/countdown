package dev.newyear.countdown.ai;

import com.google.gson.JsonObject;
import dev.newyear.countdown.ai.script.Textures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AssetPersistenceTest {
    @TempDir Path dir;
    private Textures.Def texture(String id) {
        var t=new Textures.Def();t.id=id;t.w=t.h=2;t.rows=List.of("AA",".A");t.palette="A=ffccaa88";
        t.imageModel="gpt-image-2.5-flare";return t;
    }
    @Test void finalSaveCannotBeReplacedByAnOlderQueuedSave() {
        var storage=new AiStorage(dir);var state=new AiState();state.engineTick=10;
        var earlier=storage.snapshot(state,List.of(),new AiStorage.UsageFile(),null);
        state.engineTick=25;state.textures.put("img_saved",texture("img_saved"));
        var latest=storage.snapshot(state,List.of(),new AiStorage.UsageFile(),null);
        storage.write(latest);storage.write(earlier);
        var rejoined=new AiStorage(dir).loadState();
        assertEquals(25,rejoined.engineTick);assertTrue(rejoined.textures.containsKey("img_saved"));
    }
    @Test void independentDirtyEmbeddingRevisionIsNotLostWhenLatestSnapshotOmitsIt() {
        var storage=new AiStorage(dir);var state=new AiState();var embeddings=new JsonObject();embeddings.addProperty("revision",4);
        var earlier=storage.snapshot(state,List.of(),new AiStorage.UsageFile(),embeddings);
        var latest=storage.snapshot(state,List.of(),new AiStorage.UsageFile(),null);
        storage.write(latest);storage.write(earlier);
        assertEquals(4,storage.loadEmbeddings().get("revision").getAsInt());
        embeddings.addProperty("revision",5);storage.write(storage.snapshot(state,List.of(),new AiStorage.UsageFile(),embeddings));storage.write(earlier);
        assertEquals(5,storage.loadEmbeddings().get("revision").getAsInt());
    }
    @Test void generatedTextureSurvivesWithoutAnyStateSaveOrLiveScript() {
        var storage=new AiStorage(dir);storage.saveTexture(texture("img_orphan"));
        var restored=new AiStorage(dir).loadState().textures.get("img_orphan");
        assertNotNull(restored);assertEquals("A=ffccaa88",restored.palette);assertEquals("gpt-image-2.5-flare",restored.imageModel);
    }
    @Test void archiveWinsOverStaleStateAndFallsBackToBackup() throws Exception {
        var storage=new AiStorage(dir);var old=texture("sprite");var state=new AiState();state.textures.put(old.id,old);
        storage.write(storage.snapshot(state,List.of(),new AiStorage.UsageFile(),null));storage.saveTexture(old);
        var updated=texture("sprite");updated.palette="A=ff112233";storage.saveTexture(updated);
        assertEquals("A=ff112233",new AiStorage(dir).loadState().textures.get("sprite").palette);
        Files.writeString(dir.resolve("textures/sprite.json"),"broken json");
        assertEquals("A=ffccaa88",new AiStorage(dir).loadState().textures.get("sprite").palette);
        Files.writeString(dir.resolve("textures/sprite.json"),"{}");
        assertEquals("A=ffccaa88",new AiStorage(dir).loadState().textures.get("sprite").palette);
    }
    @Test void legacyStateTexturesAreMigratedOnFirstLoad() {
        var storage=new AiStorage(dir);var state=new AiState();state.textures.put("old_item",texture("old_item"));
        storage.write(storage.snapshot(state,List.of(),new AiStorage.UsageFile(),null));storage.loadState();
        assertTrue(Files.isRegularFile(dir.resolve("textures/old_item.json")));
    }
    @Test void assetNamesCannotEscapeArchiveAndInvalidGridsAreRejected() {
        var storage=new AiStorage(dir);var t=texture("../outside");
        assertThrows(IllegalArgumentException.class,()->storage.saveTexture(t));t.id="valid";t.w=65;
        assertThrows(IllegalArgumentException.class,()->storage.saveTexture(t));
    }
    @Test void retiredMaterialArchivesAreIgnoredWithoutDeletingOldWorldData() throws Exception {
        Files.createDirectories(dir.resolve("materials"));
        Path legacy=dir.resolve("materials/old.json");Files.writeString(legacy,"legacy archive");
        Files.writeString(dir.resolve("state.json"),"{\"engineTick\":42,\"materials\":{\"old\":{}},\"removedMaterialGroups\":[\"old\"]}");
        var storage=new AiStorage(dir);var state=storage.loadState();
        assertEquals(42,state.engineTick);
        storage.write(storage.snapshot(state,List.of(),new AiStorage.UsageFile(),null));
        assertEquals("legacy archive",Files.readString(legacy));
        assertFalse(com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("state.json"))).getAsJsonObject().has("materials"));
    }
}
