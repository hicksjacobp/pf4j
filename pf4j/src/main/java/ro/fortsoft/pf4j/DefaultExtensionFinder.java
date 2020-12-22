/*
 * Copyright 2013 Decebal Suiu
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this work except in compliance with
 * the License. You may obtain a copy of the License in the LICENSE file, or at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package ro.fortsoft.pf4j;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

/**
 * The default implementation for ExtensionFinder.
 * All extensions declared in a plugin are indexed in a file "META-INF/extensions.idx".
 * This class lookup extensions in all extensions index files "META-INF/extensions.idx".
 *
 * @author Decebal Suiu
 */
public class DefaultExtensionFinder implements ExtensionFinder, PluginStateListener {

	private static final Logger log = Logger.getLogger(DefaultExtensionFinder.class);

    private PluginManager pluginManager;
	private ExtensionFactory extensionFactory;
    private volatile Map<String, Set<String>> entries; // cache by pluginId

	public DefaultExtensionFinder(PluginManager pluginManager, ExtensionFactory extensionFactory) {
        this.pluginManager = pluginManager;
		this.extensionFactory = extensionFactory;
	}

    @Override
	public <T> List<ExtensionWrapper<T>> find(Class<T> type) {
        log.debug(String.format("Checking extension point '%s'", type.getName()));
        if (!isExtensionPoint(type)) {
            log.warn(String.format("'%s' is not an extension point", type.getName()));

            return Collections.emptyList(); // or return null ?!
        }

		log.debug(String.format("Finding extensions for extension point '%s'", type.getName()));
        readIndexFiles();

        List<ExtensionWrapper<T>> result = new ArrayList<ExtensionWrapper<T>>();
        for (Map.Entry<String, Set<String>> entry : entries.entrySet()) {
            String pluginId = entry.getKey();

            if (pluginId != null) {
                PluginWrapper pluginWrapper = pluginManager.getPlugin(pluginId);
                if (PluginState.STARTED != pluginWrapper.getPluginState()) {
                    continue;
                }
            }

            Set<String> extensionClassNames = entry.getValue();

            for (String className : extensionClassNames) {
                try {
                    ClassLoader classLoader;
                    if (pluginId != null) {
                        classLoader = pluginManager.getPluginClassLoader(pluginId);
                    } else {
                        classLoader = getClass().getClassLoader();
                    }
                    log.debug(String.format("Loading class '%s' using class loader '%s'", className, classLoader));
                    Class<?> extensionClass = classLoader.loadClass(className);

                    log.debug(String.format("Checking extension type '%s'", className));
                    if (type.isAssignableFrom(extensionClass) && extensionClass.isAnnotationPresent(Extension.class)) {
                        Extension extension = extensionClass.getAnnotation(Extension.class);
                        ExtensionDescriptor descriptor = new ExtensionDescriptor();
                        descriptor.setOrdinal(extension.ordinal());
                        descriptor.setExtensionClass(extensionClass);

                        ExtensionWrapper extensionWrapper = new ExtensionWrapper<T>(descriptor);
                        extensionWrapper.setExtensionFactory(extensionFactory);
                        result.add(extensionWrapper);
                        log.debug(String.format("Added extension '%s' with ordinal %s", className, extension.ordinal()));
                    } else {
                        log.debug(String.format("'%s' is not an extension for extension point '%s'", className, type.getName()));
                    }
                } catch (ClassNotFoundException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }

        if (entries.isEmpty()) {
        	log.debug(String.format("No extensions found for extension point '%s'", type.getName()));
        } else {
        	log.debug(String.format("Found %s extensions for extension point '%s'", entries.size(), type.getName()));
        }

        // sort by "ordinal" property
        Collections.sort(result);

		return result;
	}

    @Override
    public Set<String> findClassNames(String pluginId) {
    	readIndexFiles();
        return entries.get(pluginId);
    }

    @Override
	public void pluginStateChanged(PluginStateEvent event) {
        // TODO optimize (do only for some transitions)
        // clear cache
        entries = null;
    }

    private Map<String, Set<String>> readIndexFiles() {
        // checking cache
        if (entries != null) {
            return entries;
        }

        entries = new LinkedHashMap<String, Set<String>>();

        readClasspathIndexFiles();
        readPluginsIndexFiles();

        return entries;
    }

    private void readClasspathIndexFiles() {
        log.debug("Reading extensions index files from classpath");

        Set<String> bucket = new HashSet<String>();
        try {
            Enumeration<URL> urls = getClass().getClassLoader().getResources(ExtensionsIndexer.EXTENSIONS_RESOURCE);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                log.debug(String.format("Read '%s'", url.getFile()));
                Reader reader = new InputStreamReader(url.openStream(), "UTF-8");
                ExtensionsIndexer.readIndex(reader, bucket);
            }

            if (bucket.isEmpty()) {
                log.debug("No extensions found");
            } else {
                log.debug(String.format("Found possible %s extensions:", bucket.size()));
                for (String entry : bucket) {
                    log.debug("   " + entry);
                }
            }

            entries.put(null, bucket);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    private void readPluginsIndexFiles() {
        log.debug("Reading extensions index files from plugins");

        List<PluginWrapper> plugins = pluginManager.getPlugins();
        for (PluginWrapper plugin : plugins) {
            String pluginId = plugin.getDescriptor().getPluginId();
            log.debug(String.format("Reading extensions index file for plugin '%s'", pluginId));
            Set<String> bucket = new HashSet<String>();

            try {
                URL url = plugin.getPluginClassLoader().getResource(ExtensionsIndexer.EXTENSIONS_RESOURCE);
                if (url != null) {
                    log.debug(String.format("Read '%s'", url.getFile()));
                    Reader reader = new InputStreamReader(url.openStream(), "UTF-8");
                    ExtensionsIndexer.readIndex(reader, bucket);
                } else {
                    log.debug(String.format("Cannot find '%s'", ExtensionsIndexer.EXTENSIONS_RESOURCE));
                }

                if (bucket.isEmpty()) {
                    log.debug("No extensions found");
                } else {
                    log.debug(String.format("Found possible %s extensions:", bucket.size()));
                    for (String entry : bucket) {
                        log.debug("   " + entry);
                    }
                }

                entries.put(pluginId, bucket);
            } catch (IOException e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    private boolean isExtensionPoint(Class<?> type) {
        return ExtensionPoint.class.isAssignableFrom(type);
    }

}
