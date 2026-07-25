package top.wcpe.mc.mpmt.acceptance.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 矩阵 required 场景清单与 SPI 过滤契约。 */
class MatrixScenarioCatalogTest {

    @Test
    @DisplayName("R1–R4 仅产品三件套；R5/R6 含专属场景")
    void 矩阵清单() {
        List<String> common =
                Arrays.asList("product-handshake", "product-roundtrip", "client-hud");
        assertEquals(common, MatrixScenarioCatalog.requiredFor("R1"));
        assertEquals(common, MatrixScenarioCatalog.requiredFor("R4"));
        assertTrue(MatrixScenarioCatalog.requiredFor("R5").contains("hybrid-forge-bukkit"));
        assertTrue(MatrixScenarioCatalog.requiredFor("R6").contains("entity-scheduler"));
        assertFalse(MatrixScenarioCatalog.allowsInMatrix("R1", "real-round-trip"));
        assertFalse(MatrixScenarioCatalog.allowsInMatrix("R1", "smoke"));
        assertTrue(MatrixScenarioCatalog.allowsInMatrix("R6", "global-scheduler"));
    }

    @Test
    @DisplayName("未知矩阵立即拒绝")
    void 未知矩阵() {
        assertThrows(IllegalArgumentException.class, () -> MatrixScenarioCatalog.requiredFor("RX"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MatrixScenarioCatalog.allowsInMatrix("bukkit", "client-hud"));
    }
}
