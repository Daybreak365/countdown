package dev.newyear.countdown.ai;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiConfigMigrationTest {
    private static AiConfig migrated(String json) {
        AiConfig c = AiConfig.readConfig(new StringReader(json));
        c.migrate();
        return c;
    }

    @Test
    void untouchedLegacySpiritNameMovesToTheNewDefaultOnce() {
        AiConfig c = migrated("{\"configVersion\":2,\"spiritName\":\"새벽별\"}");
        assertEquals("첫눈", c.spiritName);
        assertEquals(3, c.configVersion);

        // 관리자가 예전 이름으로 되돌린 뒤에는 다시 옮기지 않는다
        c.spiritName = AiConfig.LEGACY_SPIRIT_NAME;
        c.migrate();
        assertEquals("새벽별", c.spiritName);
    }

    @Test
    void customSpiritNameIsKept() {
        AiConfig c = migrated("{\"configVersion\":2,\"spiritName\":\"루돌프\"}");
        assertEquals("루돌프", c.spiritName);
        assertEquals(3, c.configVersion);
    }

    @Test
    void versionOneConfigStillGetsTheEarlierMigrations() {
        AiConfig c = migrated("{\"configVersion\":1,\"scriptMaxBlocks\":512,\"approvalBlockThreshold\":128,\"spiritName\":\"새벽별\"}");
        assertEquals(4096, c.scriptMaxBlocks);
        assertEquals(1024, c.approvalBlockThreshold);
        assertEquals("첫눈", c.spiritName);
    }

    @Test
    void currentVersionBlockLimitsAreNotRewritten() {
        AiConfig c = migrated("{\"configVersion\":2,\"scriptMaxBlocks\":512}");
        assertEquals(512, c.scriptMaxBlocks);
    }
}
