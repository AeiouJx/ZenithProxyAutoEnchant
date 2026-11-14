package org.example;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import dev.zenithproxy.autoenchant.BuildConstants;
import org.example.command.AutoEnchantCommand;
import org.example.module.AutoEnchantModule;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Automates low-cost enchantItem enchanting with anvils and grindstones",
    url = "https://github.com/aeioujx/ZenithAutoEnchant",
    authors = {"aeioujx"},
    mcVersions = {BuildConstants.MC_VERSION}, // to indicate any MC version: @Plugin(mcVersions = "*")
    mixins = {BuildConstants.MAVEN_GROUP + ".mixin.**"}
)
public class AutoEnchantPlugin implements ZenithProxyPlugin {
    public static ComponentLogger LOG;
    public static AutoEnchantConfig PLUGIN_CONFIG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        LOG.info("ZenithProxy Auto Enchant Plugin loading...");
        // initialize any configurations before modules or commands might need to read them
        PLUGIN_CONFIG = pluginAPI.registerConfig("Auto Enchant", AutoEnchantConfig.class, AutoEnchantConfigSerializer.INSTANCE);
        pluginAPI.registerModule(new AutoEnchantModule());
        pluginAPI.registerCommand(new AutoEnchantCommand());
        LOG.info("ZenithProxy Auto Enchant Plugin loaded!");
    }
}
