package app.freerouting.settings;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GlobalSettingsTest {
    @Test
    public void testRandomSeedFromCommandLine() {
        GlobalSettings settings = new GlobalSettings();
        String[] args = {"--router.random_seed=12345"};
        settings.applyCommandLineArguments(args);
        assertEquals(12345L, settings.routerSettings.random_seed);
    }
}
