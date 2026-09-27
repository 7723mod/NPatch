package top.nkbe.npatch.loader;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for GmsRedirector.
 * Note: These tests modify global static state (targetGms) via setTargetGmsForTest
 * and should not be executed concurrently in parallel test runners.
 */
public class GmsRedirectorTest {

    private static final String TARGET_GMS = "app.revanced.android.gms";

    @Before
    public void setUp() {
        GmsRedirector.setTargetGmsForTest(TARGET_GMS);
    }

    @After
    public void tearDown() {
        GmsRedirector.setTargetGmsForTest(null);
    }

    @Test
    public void testChimeraAuthorityExcluded() {
        // Verify Chimera authority returns null so the caller preserves the original authority
        String chimeraAuth = "com.google.android.gms.chimera";
        Assert.assertNull("Chimera authority must return null to avoid Uri mismatch",
                GmsRedirector.redirectAuthority(chimeraAuth));

        // Normal authorities should be redirected properly
        String normalAuth = "com.google.android.gms.auth.api";
        Assert.assertEquals(TARGET_GMS + ".auth.api", GmsRedirector.redirectAuthority(normalAuth));

        // Exact match of REAL_GMS
        Assert.assertEquals(TARGET_GMS, GmsRedirector.redirectAuthority("com.google.android.gms"));
    }

    @Test
    public void testChooseAccountActionIdentification() {
        // Standard action
        Assert.assertTrue(GmsRedirector.isChooseAccountAction("com.google.android.gms.common.account.CHOOSE_ACCOUNT"));
        // Target GMS namespaced action
        Assert.assertTrue(GmsRedirector.isChooseAccountAction(TARGET_GMS + ".common.account.CHOOSE_ACCOUNT"));
        // Unrelated actions
        Assert.assertFalse(GmsRedirector.isChooseAccountAction("com.google.android.gms.auth.GOOGLE_SIGN_IN"));
        Assert.assertFalse(GmsRedirector.isChooseAccountAction(null));
    }

    @Test
    public void testRedirectActionWithoutOffByOne() {
        // Standard GMS action transformation
        String sourceAction = "com.google.android.gms.auth.GOOGLE_SIGN_IN";
        String expectedAction = "app.revanced.android.gms.auth.GOOGLE_SIGN_IN";
        Assert.assertEquals("Semantic string concatenation must not have off-by-one errors",
                expectedAction, GmsRedirector.redirectAction(sourceAction));

        String gamesAction = "com.google.android.gms.games.service.START";
        Assert.assertEquals(TARGET_GMS + ".games.service.START", GmsRedirector.redirectAction(gamesAction));

        // CHOOSE_ACCOUNT action must return null to allow system account picker routing
        Assert.assertNull(GmsRedirector.redirectAction("com.google.android.gms.common.account.CHOOSE_ACCOUNT"));
    }

    @Test
    public void testC2dmActionRedirect() {
        String receive = "com.google.android.c2dm.intent.RECEIVE";
        String expected = "app.revanced.android.c2dm.intent.RECEIVE";
        Assert.assertEquals(expected, GmsRedirector.redirectAction(receive));

        String register = "com.google.android.c2dm.intent.REGISTER";
        String expectedRegister = "app.revanced.android.c2dm.intent.REGISTER";
        Assert.assertEquals(expectedRegister, GmsRedirector.redirectAction(register));
    }

    @Test
    public void testRedirectPackage() {
        Assert.assertEquals(TARGET_GMS, GmsRedirector.redirectPackage("com.google.android.gms"));
        Assert.assertEquals(TARGET_GMS, GmsRedirector.redirectPackage("com.google.android.gsf"));
        Assert.assertNull(GmsRedirector.redirectPackage("com.android.vending"));
    }
}
