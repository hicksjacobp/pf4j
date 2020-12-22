/*
 * Copyright 2014 Decebal Suiu
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggerFactory;

/**
 * The default implementation for PluginFactory.
 * It uses Class.newInstance() method.
 *
 * @author Decebal Suiu
 */
public class DefaultPluginFactory implements PluginFactory {

    private static final Logger log = Logger.getLogger(DefaultExtensionFactory.class);

    /**
     * Creates a plugin instance. If an error occurs than that error is logged and the method returns null.
     * @param pluginWrapper
     * @return
     */
    @Override
    public Plugin create(final PluginWrapper pluginWrapper) {
        String pluginClassName = pluginWrapper.getDescriptor().getPluginClass();
        log.debug(String.format("Create instance for plugin '%s'", pluginClassName));

        Class<?> pluginClass;
        try {
            pluginClass = pluginWrapper.getPluginClassLoader().loadClass(pluginClassName);
        } catch (ClassNotFoundException e) {
            log.error(e.getMessage(), e);
            return null;
        }

        // once we have the class, we can do some checks on it to ensure
        // that it is a valid implementation of a plugin.
        int modifiers = pluginClass.getModifiers();
        if (Modifier.isAbstract(modifiers) || Modifier.isInterface(modifiers)
                || (!Plugin.class.isAssignableFrom(pluginClass))) {
            log.error(String.format("The plugin class '%s' is not valid", pluginClassName));
            return null;
        }

        // create the plugin instance
        try {
            Constructor<?> constructor = pluginClass.getConstructor(new Class[] { PluginWrapper.class });
            return (Plugin) constructor.newInstance(new Object[] { pluginWrapper });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        return null;
    }

}
