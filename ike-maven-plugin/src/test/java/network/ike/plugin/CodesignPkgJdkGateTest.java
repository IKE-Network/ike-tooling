package network.ike.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodesignPkgJdkGateTest {

    @Test
    void fixedOn_25_0_2() {
        assertTrue(CodesignPkgMojo.jpackageHasEntitlementsFix(
                Runtime.Version.parse("25.0.2")));
    }

    @Test
    void fixedOn_25_0_3() {
        assertTrue(CodesignPkgMojo.jpackageHasEntitlementsFix(
                Runtime.Version.parse("25.0.3+9")));
    }

    @Test
    void fixedOn_26_0_0() {
        assertTrue(CodesignPkgMojo.jpackageHasEntitlementsFix(
                Runtime.Version.parse("26")));
    }

    @Test
    void fixedOn_26_0_1() {
        assertTrue(CodesignPkgMojo.jpackageHasEntitlementsFix(
                Runtime.Version.parse("26.0.1")));
    }

    @Test
    void notFixedOn_25_0_0() {
        assertFalse(CodesignPkgMojo.jpackageHasEntitlementsFix(
                Runtime.Version.parse("25")));
    }

    @Test
    void notFixedOn_25_0_1() {
        assertFalse(CodesignPkgMojo.jpackageHasEntitlementsFix(
                Runtime.Version.parse("25.0.1")));
    }

    @Test
    void notFixedOn_21() {
        assertFalse(CodesignPkgMojo.jpackageHasEntitlementsFix(
                Runtime.Version.parse("21.0.5")));
    }

    @Test
    void notFixedOn_17() {
        assertFalse(CodesignPkgMojo.jpackageHasEntitlementsFix(
                Runtime.Version.parse("17.0.12")));
    }
}
