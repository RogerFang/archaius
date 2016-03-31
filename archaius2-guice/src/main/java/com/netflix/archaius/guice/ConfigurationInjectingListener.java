package com.netflix.archaius.guice;

import javax.inject.Inject;
import javax.inject.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.ProvisionException;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import com.google.inject.spi.InjectionListener;
import com.google.inject.spi.TypeEncounter;
import com.google.inject.spi.TypeListener;
import com.netflix.archaius.ConfigMapper;
import com.netflix.archaius.api.CascadeStrategy;
import com.netflix.archaius.api.Config;
import com.netflix.archaius.api.ConfigLoader;
import com.netflix.archaius.api.IoCContainer;
import com.netflix.archaius.api.annotations.Configuration;
import com.netflix.archaius.api.annotations.ConfigurationSource;
import com.netflix.archaius.api.config.CompositeConfig;
import com.netflix.archaius.api.exceptions.ConfigException;
import com.netflix.archaius.api.inject.LibrariesLayer;
import com.netflix.archaius.cascade.NoCascadeStrategy;

public class ConfigurationInjectingListener implements TypeListener {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationInjectingListener.class);
    
    static class Holder {
        @Inject
        private Config            config;
        
        @Inject
        private Injector          injector;
        
        @Inject
        private ConfigLoader      loader;
        
        @Inject
        private @LibrariesLayer   CompositeConfig   libraries;
        
        @com.google.inject.Inject(optional = true)
        private CascadeStrategy   cascadeStrategy;
        
        CascadeStrategy getCascadeStrategy() {
            return cascadeStrategy != null ? cascadeStrategy : NoCascadeStrategy.INSTANCE;
        }
    }
    
    private ConfigMapper mapper = new ConfigMapper();
    
    @Override
    public <I> void hear(TypeLiteral<I> typeLiteral, final TypeEncounter<I> encounter) {
        Class<?> clazz = typeLiteral.getRawType();
        
        //
        // Configuration Loading
        //
        ConfigurationSource source = clazz.getAnnotation(ConfigurationSource.class);
        if (source != null) {
            final Provider<Holder> holder = encounter.getProvider(Holder.class);
            
            encounter.register(new InjectionListener<I>() {
                @Override
                public void afterInjection(I injectee) {
                    ConfigurationSource source = injectee.getClass().getAnnotation(ConfigurationSource.class);
                    CascadeStrategy strategy = source.cascading() != ConfigurationSource.NullCascadeStrategy.class
                                             ? holder.get().injector.getInstance(source.cascading()) 
                                             : holder.get().getCascadeStrategy();
                                             
                    if (source != null) {
                        for (String resourceName : source.value()) {
                            LOG.debug("Trying to loading configuration resource {}", resourceName);
                            try {
                                CompositeConfig loadedConfig = holder.get().loader.newLoader()
                                            .withCascadeStrategy(strategy)
                                            .load(resourceName);
                                holder.get().libraries.addConfig(resourceName, loadedConfig);
                            } 
                            catch (ConfigException e) {
                                throw new ProvisionException("Unable to load configuration for " + resourceName + " at source " + injectee.getClass(), e);
                            }
                        }
                    }
                }
            });
        }
        
        //
        // Configuration binding
        //
        Configuration configAnnot = clazz.getAnnotation(Configuration.class);
        if (configAnnot != null) {
            final Provider<Holder> holder = encounter.getProvider(Holder.class);
            
            encounter.register(new InjectionListener<I>() {
                @Override
                public void afterInjection(I injectee) {
                    try {
                        mapper.mapConfig(injectee, holder.get().config, new IoCContainer() {
                            @Override
                            public <T> T getInstance(String name, Class<T> type) {
                                return holder.get().injector.getInstance(Key.get(type, Names.named(name)));
                            }
                        });
                    }
                    catch (Exception e) {
                        throw new ProvisionException("Unable to bind configuration to " + injectee.getClass(), e);
                    }
                }
            });
        }        
    }
}