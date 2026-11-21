package dev.newyear.countdown.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JosaTest {
    @Test
    void picksParticleByFinalConsonant() {
        assertEquals("첫눈이", Josa.iGa("첫눈"));
        assertEquals("루돌프가", Josa.iGa("루돌프"));
        assertEquals("첫눈은", Josa.eunNeun("첫눈"));
        assertEquals("산타는", Josa.eunNeun("산타"));
        assertEquals("첫눈을", Josa.eulReul("첫눈"));
        assertEquals("첫눈과", Josa.gwaWa("첫눈"));
        assertEquals("산타와", Josa.gwaWa("산타"));
    }

    @Test
    void looksPastTrailingPunctuationAndHandlesNonHangul() {
        assertEquals("'첫눈'이", Josa.iGa("'첫눈'"));
        assertEquals("Santa가", Josa.iGa("Santa"));
        assertEquals("2027이", Josa.iGa("2027"));
        assertEquals("2022가", Josa.iGa("2022"));
        assertEquals("가", Josa.iGa(""));
        assertEquals("가", Josa.iGa(null));
    }
}
