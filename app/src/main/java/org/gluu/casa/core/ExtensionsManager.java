package org.gluu.casa.core;

import org.gluu.casa.conf.MainSettings;
import org.gluu.casa.conf.PluginInfo;
import org.gluu.casa.core.plugin.CasaPluginManager;
import org.gluu.casa.extension.AuthnMethod;
import org.gluu.casa.misc.Utils;
import org.pf4j.*;
import org.slf4j.Logger;
import org.zkoss.util.Pair;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarInputStream;
import java.util.stream.Collectors;

/**
 * @author jgomer
 */
@ApplicationScoped
@Named
public class ExtensionsManager {

    public static final String ASSETS_DIR = "assets";
    public static final String PLUGINS_EXTRACTION_DIR = "pl";

    private static final String PLUGINS_DIR_NAME = "plugins";
    private static final Class<AuthnMethod> AUTHN_METHOD_CLASS = AuthnMethod.class;

    @Inject
    private Logger logger;

    @Inject
    private ZKService zkService;

    @Inject
    private MainSettings mainSettings;

    @Inject
    private ResourceExtractor resourceExtractor;

    @Inject
    private RSRegistryHandler registryHandler;

    @Inject
    private LogService logService;

    private Path pluginsRoot;

    private PluginManager pluginManager;

    //Holds a mapping of plugin ids vs. authentication methods extensions (only started plugins are included)
    private Map<String, List<AuthnMethod>> plugExtensionMap;

    private List<PluginInfo> knownPlugins;

    private Path extractionDirectory;

    public List<PluginInfo> getKnownPlugins() {
        return knownPlugins;
    }

    public void setKnownPlugins(List<PluginInfo> knownPlugins) {
        this.knownPlugins = knownPlugins;
    }

    @PostConstruct
    private void inited() {

        pluginsRoot = Paths.get(System.getProperty("server.base"), PLUGINS_DIR_NAME);
        plugExtensionMap = new HashMap<>();    //It accepts null keys
        knownPlugins = new ArrayList<>();
        extractionDirectory = Paths.get(zkService.getAppFileSystemRoot(), PLUGINS_EXTRACTION_DIR);

        if (Files.isDirectory(pluginsRoot)) {
            purgePluginsExtractionPath();
        } else {
            pluginsRoot = null;
            logger.warn("External plugins directory does not exist: there is no valid location for searching");
        }
        pluginManager = new CasaPluginManager(pluginsRoot);

    }

    void scan() {

        //Load inner extensions
        List<AuthnMethod> actualAMEs = new ArrayList<>();
        List<AuthnMethod> authnMethodExtensions = pluginManager.getExtensions(AUTHN_METHOD_CLASS);
        if (authnMethodExtensions != null) {

            for (AuthnMethod ext : authnMethodExtensions) {
                String acr = ext.getAcr();
                String name = ext.getClass().getName();
                logger.info("Found system extension '{}' for {}", name, acr);
                actualAMEs.add(ext);
            }
        }
        plugExtensionMap.put(null, actualAMEs);

    }

    public void updatePlugins(List<Pair<String, File>> toBeAdded, List<Pair<String, File>> toBeRemoved) {

        List<PluginInfo> plugins = new ArrayList<>(knownPlugins);

        //Removed the undesired
        toBeRemoved.stream().map(Pair::getX).forEach(id -> {

            if (Utils.firstTrue(knownPlugins, pi -> pi.getId().equals(id)) >= 0) {
                logger.info("Removing plugin {}", id);

                if (deletePlugin(id)) {
                    int i = Utils.firstTrue(plugins, pi -> pi.getId().equals(id));
                    if (i >= 0) {
                        plugins.remove(i);
                    }
                } else {
                    logger.error("Plugin removal failure!");
                }
            }
        });

        if (toBeAdded.size() > 0) {
            logger.info("Loading external plugins...");
            //Add the ones that appeared recently
            List<Path> files = toBeAdded.stream().map(Pair::getY).map(File::toPath).collect(Collectors.toList());

            for (Path path : files) {
                PluginInfo pl = new PluginInfo();
                pl.setId(loadPlugin(path));
                if (pl.getId() != null) {
                    pl.setRelativePath(path.toString());
                    plugins.add(pl);
                }
            }
            logger.info("Total plugins loaded {}", plugins.size());

            if (files.size() > plugins.size()) {
                //Some plugins didn't start successfully, let's remove them then
                Set<String> loadedPaths = plugins.stream().map(PluginInfo::getRelativePath).collect(Collectors.toSet());
                files.stream().filter(p -> !loadedPaths.contains(p.toString())).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                });
                logger.warn("Some plugin files were removed from disk...");
            }

            int started = 0;
            int index = 0;
            List<Integer> indexes = new ArrayList<>();

            for (PluginInfo pl : plugins) {
                String pluginId = pl.getId();

                if (startPlugin(pluginId, false)) {
                    started++;
                    PluginWrapper wrapper = pluginManager.getPlugin(pluginId);
                    logger.info("Plugin {} ({}) started", pluginId, wrapper.getDescriptor().getPluginClass());

                    Set<String> classNames = pluginManager.getExtensionClassNames(pluginId);
                    if (classNames.size() > 0) {
                        logger.info("Plugin's extensions are at: {}", classNames.toString());
                    }
                    //Add a breaking space
                    logger.info("");
                } else {
                    //We'd better remove it since it can cause unstability
                    logger.warn("Plugin {} failed to start, will be deleted", pluginId);
                    deletePlugin(pluginId);
                    indexes.add(index, 0);
                }
                index++;
            }

            indexes.forEach(i -> plugins.remove(i.intValue()));
            logger.info("Total plugins started: {}{}", started, started > 0 ? "\n" : "");
            zkService.refreshLabels();
        }

        long distinctAcrs = plugExtensionMap.values().stream().flatMap(List::stream).map(AuthnMethod::getAcr).distinct().count();
        if (distinctAcrs < plugExtensionMap.values().stream().mapToLong(List::size).sum()) {
            logger.warn("Several extensions pretend to handle the same acr.");
            logger.warn("Only the first one parsed for the plugin referenced in 'Enabled methods' of admin console will be effective");
            logger.warn("The system extension (if exists) will be used if no plugin can handle an acr");
        }
        knownPlugins = plugins;

    }

    public Optional<AuthnMethod> getExtensionForAcr(String acr) {
        String plugId = mainSettings.getAcrPluginMap().get(acr);
        //plugId can be null (means the set of system extensions)
        return plugExtensionMap.get(plugId).stream().filter(aMethod -> aMethod.getAcr().equals(acr)).findFirst();
    }

    public boolean pluginImplementsAuthnMethod(String acr, String plugId) {
        return plugExtensionMap.containsKey(plugId)
            && plugExtensionMap.get(plugId).stream().anyMatch(aMethod -> aMethod.getAcr().equals(acr));
    }

    public List<PluginDescriptor> authnMethodPluginImplementers() {
        return getPlugins().stream().filter(pl -> plugExtensionMap.keySet().contains(pl.getPluginId()))
                .map(PluginWrapper::getDescriptor).collect(Collectors.toList());
    }

    public List<AuthnMethod> getAuthnMethodExts(Set<String> plugIds) {
        return plugExtensionMap.entrySet().stream().filter(e -> plugIds.contains(e.getKey())).map(Map.Entry::getValue)
                .flatMap(List::stream).collect(Collectors.toList());
    }

    public ClassLoader getPluginClassLoader(String clsName) {

        ClassLoader clsLoader = null;
        for (PluginWrapper wrapper : pluginManager.getStartedPlugins()) {
            try {
                String pluginClassName = wrapper.getDescriptor().getPluginClass();
                ClassLoader loader = wrapper.getPluginClassLoader();

                Class<?> cls = loader.loadClass(pluginClassName);
                if (clsName.startsWith(cls.getPackage().getName())) {
                    clsLoader = loader;
                    break;
                }
            } catch (ClassNotFoundException e) {
                //Intentionally left empty
            }
        }
        return clsLoader;

    }

    public <T> List<Pair<String, T>> getPluginExtensionsForClass(Class<T> clazz) {

        List<Pair<String, T>> pairs = new ArrayList<>();
        for (PluginWrapper wrapper : getPlugins()) {
            String plId = wrapper.getPluginId();
            pluginManager.getExtensions(clazz, plId).forEach(cl -> pairs.add(new Pair<>(plId, cl)));
        }
        return pairs;

    }

    public Path getPluginsRoot() {
        return pluginManager.getPluginsRoot();
    }

    public List<PluginWrapper> getPlugins() {
        return pluginManager.getPlugins();
    }

    private String loadPlugin(Path path) {
        return pluginManager.loadPlugin(path);
    }

    private boolean deletePlugin(String pluginId) {

        PluginState state = pluginManager.stopPlugin(pluginId);
        try {
            if (state.equals(PluginState.STOPPED)) {
                plugExtensionMap.remove(pluginId);
                zkService.removePluginLabels(pluginId);
                registryHandler.remove(pluginId);
                resourceExtractor.recursiveDelete(getDestinationPathForPlugin(pluginId));
                //There is no need to remove loggers from LogService (several plugins may be using the same logger)
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return state.equals(PluginState.STOPPED) && pluginManager.deletePlugin(pluginId);

    }

    private boolean startPlugin(String pluginId, boolean refreshLabels) {

        boolean success = false;
        try {
            PluginState state = pluginManager.startPlugin(pluginId);
            Path path = pluginManager.getPlugin(pluginId).getPluginPath();

            if (PluginState.STARTED.equals(state)) {
                logger.info("Plugin {} started", pluginId);
                parsePluginAuthnMethodExtensions(pluginId);

                reconfigureServices(pluginId, path, pluginManager.getPluginClassLoader(pluginId));
                success = true;

                if (refreshLabels) {
                    zkService.refreshLabels();
                }
            } else {
                logger.warn("Plugin loaded from {} not started. Current state is {}", path.toString(), state == null ? null : state.toString());
            }
        } catch (Throwable e) {
            logger.error(e.getMessage(), e);
        }
        return success;

    }

    private void parsePluginAuthnMethodExtensions(String pluginId) {

        List<AuthnMethod> ames = pluginManager.getExtensions(AUTHN_METHOD_CLASS, pluginId);
        if (ames.size() > 0) {
            logger.info("Plugin extends {} at {} point(s)", AUTHN_METHOD_CLASS.getName(), ames.size());

            List<AuthnMethod> filteredAmes = new ArrayList<>();
            Set<String> acrs = ames.stream().map(AuthnMethod::getAcr).distinct().collect(Collectors.toSet());
            acrs.forEach(acr -> {
                //If this plugin implemented several AuthnMethods for the same acr, keep the first only
                filteredAmes.add(ames.stream().filter(ext -> ext.getAcr().equals(acr)).findFirst().get());
                logger.info("Extension point found to deal with acr value '{}'", acr);
            });

            plugExtensionMap.put(pluginId, filteredAmes);
        }

    }

    private Path getDestinationPathForPlugin(String pluginId) {
        return Paths.get(extractionDirectory.toString(), pluginId);
    }

    private void extractResources(String pluginId, Path path) throws IOException {

        Path destPath = getDestinationPathForPlugin(pluginId);
        logger.info("Extracting resources for plugin {} to {}", pluginId, destPath.toString());
        try (JarInputStream jis = new JarInputStream(new BufferedInputStream(new FileInputStream(path.toString())), false)) {
            resourceExtractor.createDirectory(jis, ASSETS_DIR + "/", destPath);
        }

    }

    private void reconfigureServices(String pluginId, Path path, ClassLoader cl) {

        if (Utils.isJarFile(path)) {

            try {
                extractResources(pluginId, path);
            } catch (IOException e) {
                logger.error("Error when extracting plugin resources");
                logger.error(e.getMessage(), e);
            }
            zkService.readPluginLabels(pluginId, path);
            registryHandler.scan(pluginId, path, cl);
            logService.addLoger(path);

        } else {
            logger.error("Expected a path to a jar file instead of '{}'", path);
        }

    }

    private void purgePluginsExtractionPath() {

        //Deletes all files in pl directory
        try {
            Files.list(extractionDirectory).filter(p -> Files.isDirectory(p))
                    .forEach(p -> {
                        try {
                            resourceExtractor.recursiveDelete(p);
                        } catch (Exception e) {
                            logger.error(e.getMessage());
                        }
                    });

        } catch (Exception e) {
            logger.error(e.getMessage());
        }

    }

}
